package com.dataentry.service;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.model.TicketDocument;
import com.dataentry.repository.TicketDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the explorer's matching attachments as one ZIP laid out as
 * {@code Project/Department[/Subcategory]/file}. This is the fallback for browsers without
 * the File System Access API (Firefox, Safari); Chromium browsers write straight into a
 * folder the operator picks, using the same {@link ExportPaths} naming rules so both paths
 * produce identical trees.
 *
 * <p>Entries are written straight to the servlet output stream — nothing is buffered on
 * disk or in memory, so a multi-gigabyte export costs the server one file at a time.
 */
@Service
public class ExplorerArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ExplorerArchiveService.class);

    private final DataExplorerService explorer;
    private final TicketDocumentRepository documentRepository;
    private final Path baseDir;

    public ExplorerArchiveService(DataExplorerService explorer,
                                  TicketDocumentRepository documentRepository,
                                  @Value("${app.attachments.dir:./data/attachments}") String baseDir) {
        this.explorer = explorer;
        this.documentRepository = documentRepository;
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public void writeZip(DataExplorerService.Filters filters,
                         boolean subcategoryFolders,
                         boolean includeText,
                         OutputStream out) throws IOException {
        DataExplorerDtos.Manifest manifest = explorer.manifest(filters, includeText);
        Set<String> usedPaths = new HashSet<>();
        int written = 0, missing = 0;

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            // Attachments are mostly PDFs and images that are already compressed; deflating
            // them again burns CPU for nothing, so favour throughput.
            zip.setLevel(Deflater.BEST_SPEED);

            for (DataExplorerDtos.ManifestEntry e : manifest.files()) {
                TicketDocument doc = documentRepository.findById(e.documentId()).orElse(null);
                if (doc == null || doc.getStoragePath() == null) { missing++; continue; }
                Path abs = baseDir.resolve(doc.getStoragePath()).normalize();
                if (!abs.startsWith(baseDir) || !Files.exists(abs)) { missing++; continue; }

                String path = ExportPaths.filePath(e, subcategoryFolders, usedPaths);
                ZipEntry entry = new ZipEntry(path);
                entry.setTime(e.submittedAt() != null ? e.submittedAt().toEpochMilli() : Instant.now().toEpochMilli());
                zip.putNextEntry(entry);
                Files.copy(abs, zip);
                zip.closeEntry();
                written++;
            }

            if (includeText) {
                for (DataExplorerDtos.ManifestTicket t : manifest.tickets()) {
                    String path = ExportPaths.ticketNotePath(t, subcategoryFolders, usedPaths);
                    zip.putNextEntry(new ZipEntry(path));
                    zip.write(ExportPaths.ticketMarkdown(t).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                zip.putNextEntry(new ZipEntry("index.csv"));
                zip.write(ExportPaths.indexCsv(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        log.info("Explorer archive streamed: {} files written, {} missing on disk, filters={}", written, missing, filters);
    }

    /**
     * Naming rules shared by the ZIP export and (mirrored in TypeScript) the in-browser
     * folder export. Keep the two in sync — operators expect a ZIP unpacked next to a folder
     * download to line up file for file.
     */
    public static final class ExportPaths {
        private ExportPaths() {}

        private static final int MAX_SEGMENT = 120;

        /** Windows/macOS-safe path segment: strips reserved characters and trailing dots. */
        public static String safe(String raw, String fallback) {
            if (raw == null) return fallback;
            String s = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            s = s.replaceAll("[. ]+$", "").trim();
            if (s.isEmpty()) return fallback;
            if (s.length() > MAX_SEGMENT) s = s.substring(0, MAX_SEGMENT).trim();
            return s;
        }

        public static String folder(String project, String department, String subcategory, boolean subcategoryFolders) {
            StringBuilder sb = new StringBuilder();
            sb.append(safe(project, "No project")).append('/');
            sb.append(safe(department, "No department")).append('/');
            if (subcategoryFolders && subcategory != null && !subcategory.isBlank()) {
                sb.append(safe(subcategory, "No subcategory")).append('/');
            }
            return sb.toString();
        }

        public static String filePath(DataExplorerDtos.ManifestEntry e, boolean subcategoryFolders, Set<String> used) {
            String original = e.originalFilename() != null && !e.originalFilename().isBlank()
                    ? e.originalFilename() : (e.name() != null ? e.name() : "file");
            String ext = "";
            int dot = original.lastIndexOf('.');
            if (dot > 0 && dot < original.length() - 1 && original.length() - dot <= 12) {
                ext = original.substring(dot);
                original = original.substring(0, dot);
            }
            String title = safe(e.ticketTitle(), "");
            String base = "#" + e.ticketId() + (title.isEmpty() ? "" : " - " + title) + " - " + safe(original, "file");
            if (base.length() > 180) base = base.substring(0, 180).trim();
            return unique(folder(e.projectName(), e.departmentName(), e.subcategoryName(), subcategoryFolders), base, ext, used);
        }

        public static String ticketNotePath(DataExplorerDtos.ManifestTicket t, boolean subcategoryFolders, Set<String> used) {
            String title = safe(t.title(), "");
            String base = "#" + t.id() + (title.isEmpty() ? "" : " - " + title);
            return unique(folder(t.projectName(), t.departmentName(), t.subcategoryName(), subcategoryFolders), base, ".md", used);
        }

        private static String unique(String folder, String base, String ext, Set<String> used) {
            String candidate = folder + base + ext;
            int n = 2;
            while (!used.add(candidate.toLowerCase())) {
                candidate = folder + base + " (" + n++ + ")" + ext;
            }
            return candidate;
        }

        public static String ticketMarkdown(DataExplorerDtos.ManifestTicket t) {
            StringBuilder md = new StringBuilder();
            md.append("# ").append(t.title() == null || t.title().isBlank() ? "Entry #" + t.id() : t.title()).append("\n\n");
            md.append("- **Entry:** #").append(t.id()).append('\n');
            if (t.teamName() != null) md.append("- **Team:** ").append(t.teamName()).append('\n');
            if (t.projectName() != null) md.append("- **Project:** ").append(t.projectName()).append('\n');
            if (t.departmentName() != null) md.append("- **Department:** ").append(t.departmentName()).append('\n');
            if (t.subcategoryName() != null) md.append("- **Subcategory:** ").append(t.subcategoryName()).append('\n');
            if (t.submittedBy() != null) md.append("- **Submitted by:** ").append(t.submittedBy()).append('\n');
            if (t.submittedAt() != null) md.append("- **Submitted at:** ").append(t.submittedAt()).append('\n');
            if (t.status() != null) md.append("- **Status:** ").append(t.status()).append('\n');
            if (t.websiteName() != null || t.websiteLink() != null) {
                md.append("- **Website:** ").append(t.websiteName() != null ? t.websiteName() : "")
                        .append(t.websiteLink() != null ? " <" + t.websiteLink() + ">" : "").append('\n');
            }
            if (t.customFields() != null && !t.customFields().isEmpty()) {
                md.append("\n## Custom fields\n\n");
                for (DataExplorerDtos.FieldValue f : t.customFields()) {
                    md.append("- **").append(f.fieldName() == null ? "Field" : f.fieldName()).append(":** ")
                            .append(f.value() == null ? "" : f.value()).append('\n');
                }
            }
            md.append("\n## Content\n\n").append(t.content() == null ? "" : t.content()).append('\n');
            return md.toString();
        }

        public static String indexCsv(DataExplorerDtos.Manifest manifest) {
            StringBuilder csv = new StringBuilder("﻿");
            csv.append("entry_id,title,team,project,department,subcategory,submitted_by,submitted_at,status,files,website\n");
            Map<Long, Integer> fileCounts = new HashMap<>();
            for (DataExplorerDtos.ManifestEntry e : manifest.files()) fileCounts.merge(e.ticketId(), 1, Integer::sum);
            List<DataExplorerDtos.ManifestTicket> tickets = manifest.tickets();
            for (DataExplorerDtos.ManifestTicket t : tickets) {
                csv.append(t.id()).append(',')
                        .append(cell(t.title())).append(',')
                        .append(cell(t.teamName())).append(',')
                        .append(cell(t.projectName())).append(',')
                        .append(cell(t.departmentName())).append(',')
                        .append(cell(t.subcategoryName())).append(',')
                        .append(cell(t.submittedBy())).append(',')
                        .append(t.submittedAt() == null ? "" : t.submittedAt()).append(',')
                        .append(cell(t.status())).append(',')
                        .append(fileCounts.getOrDefault(t.id(), 0)).append(',')
                        .append(cell(t.websiteLink())).append('\n');
            }
            return csv.toString();
        }

        private static String cell(String v) {
            if (v == null) return "";
            String s = v.replace("\r", " ").replace("\n", " ");
            if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
            return s;
        }
    }
}
