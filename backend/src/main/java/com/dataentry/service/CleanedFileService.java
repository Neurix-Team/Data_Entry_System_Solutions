package com.dataentry.service;

import com.dataentry.dto.CleanedFileDtos;
import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.model.CleanedFile;
import com.dataentry.model.CleanedFile.Status;
import com.dataentry.repository.*;
import com.dataentry.security.TenantContext;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

@Service
@Transactional(readOnly = true)
public class CleanedFileService {
    private static final List<String> EXTENSIONS = List.of("txt", "md", "csv", "tsv", "json", "jsonl", "ndjson", "zip", "pdf", "docx", "xlsx", "parquet");
    private static final Set<String> MIME = Set.of("text/plain", "text/csv", "text/tab-separated-values", "text/markdown",
            "application/json", "application/x-ndjson", "application/zip", "application/x-zip-compressed",
            "application/pdf", "application/x-tika-ooxml", "application/x-parquet", "application/vnd.apache.parquet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Tika TIKA = new Tika();
    private final CleanedFileRepository files;
    private final ProjectRepository projects;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final UploadQuotaService quota;
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final long maxBytes;

    public CleanedFileService(CleanedFileRepository files, ProjectRepository projects, DepartmentRepository departments,
                             UserRepository users, UploadQuotaService quota, JdbcTemplate jdbc,
                             @Value("${app.cleaned-files.dir:${user.dir}/data/cleaned-files}") String directory,
                             @Value("${app.cleaned-files.max-file-bytes:52428800}") long maxBytes) {
        this.files = files; this.projects = projects; this.departments = departments; this.users = users;
        this.quota = quota; this.jdbc = jdbc; this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public CleanedFileDtos.Options options() {
        requireSuper();
        var ps = jdbc.query("SELECT p.id, p.name, p.team_id, t.name FROM projects p LEFT JOIN teams t ON t.id=p.team_id WHERE p.deleted_at IS NULL ORDER BY p.name",
                (rs, n) -> new CleanedFileDtos.ProjectOption(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class), rs.getString(4)));
        var ds = jdbc.query("SELECT d.id, d.name, d.project_id FROM departments d JOIN projects p ON p.id=d.project_id WHERE d.active=TRUE AND p.deleted_at IS NULL ORDER BY d.name",
                (rs, n) -> new CleanedFileDtos.DepartmentOption(rs.getLong(1), rs.getString(2), rs.getLong(3)));
        return new CleanedFileDtos.Options(ps, ds, maxBytes, EXTENSIONS);
    }

    public CleanedFileDtos.Page list(Long projectId, Long departmentId, Status status, LocalDate from, LocalDate to,
                                     String search, int page) {
        requireSuper();
        if (page < 0 || page > 100000) bad("Invalid page");
        Specification<CleanedFile> spec = filters(projectId, departmentId, status, from, to, search);
        var result = files.findAll(spec, PageRequest.of(page, 30, Sort.by("id").descending()));
        return new CleanedFileDtos.Page(result.getContent().stream().map(this::row).toList(), result.getTotalElements(),
                page, result.getTotalPages(), count(spec, Status.READY), count(spec, Status.IN_PROGRESS), count(spec, Status.COMPLETED));
    }

    private Specification<CleanedFile> filters(Long projectId, Long departmentId, Status status, LocalDate from, LocalDate to, String search) {
        if (from != null && to != null && from.isAfter(to)) bad("Invalid date range");
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 250) bad("Search must not exceed 250 characters");
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (projectId != null) predicates.add(cb.equal(root.get("project").get("id"), projectId));
            if (departmentId != null) predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("cleanedOn"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("cleanedOn"), to));
            if (!term.isEmpty()) {
                String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
                        cb.like(cb.lower(root.get("originalFilename")), pattern, '\\'),
                        cb.like(cb.lower(root.get("sourceReference")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    public DataExplorerDtos.Manifest manifest(Long projectId, Long departmentId, Status status, LocalDate from,
                                              LocalDate to, String search, boolean includeText) {
        requireSuper();
        return manifest(files.findAll(filters(projectId, departmentId, status, from, to, search), Sort.by("id").descending()), includeText);
    }

    private DataExplorerDtos.Manifest manifest(List<CleanedFile> selected, boolean includeText) {
        var entries = new ArrayList<DataExplorerDtos.ManifestEntry>();
        var notes = new ArrayList<DataExplorerDtos.ManifestTicket>();
        long bytes = 0;
        for (var entity : selected) {
            var file = row(entity);
            entries.add(new DataExplorerDtos.ManifestEntry(file.id(), file.title(), file.uploadedAt(), file.teamId(), file.teamName(),
                    file.projectId(), file.projectName(), file.departmentId(), file.departmentName(), null, null, file.uploadedBy(),
                    file.id(), file.title(), file.originalFilename(), null, file.sizeBytes(), file.sha256()));
            bytes += file.sizeBytes();
            if (includeText) {
                notes.add(new DataExplorerDtos.ManifestTicket(file.id(), file.title(), file.notes(), null, null, file.status().name(),
                        file.uploadedAt(), file.uploadedBy(), file.teamName(), file.projectName(), file.departmentName(), null,
                        List.of(new DataExplorerDtos.FieldValue(null, "Cleaned on", file.cleanedOn().toString()),
                                new DataExplorerDtos.FieldValue(null, "Due date", Objects.toString(file.dueOn(), "")),
                                new DataExplorerDtos.FieldValue(null, "Source reference", file.sourceReference()),
                                new DataExplorerDtos.FieldValue(null, "SHA-256", file.sha256()))));
            }
        }
        return new DataExplorerDtos.Manifest(entries, notes, entries.size(), bytes, entries.size());
    }

    public StreamingResponseBody archive(Long projectId, Long departmentId, Status status, LocalDate from, LocalDate to,
                                         String search, String fileType, boolean prefixNames, boolean includeText) {
        requireSuper();
        if (!Set.of("all", "documents", "pdf").contains(fileType)) bad("Unsupported file type filter");
        var selected = files.findAll(filters(projectId, departmentId, status, from, to, search), Sort.by("id").descending());
        var manifest = manifest(selected, includeText);
        var paths = new LinkedHashMap<DataExplorerDtos.ManifestEntry, Path>();
        for (int i = 0; i < selected.size(); i++) {
            var entry = manifest.files().get(i);
            if (ExplorerArchiveService.ExportPaths.matchesType(entry, fileType)) paths.put(entry, storedPath(selected.get(i).getStorageKey()));
        }
        Set<Long> exportedIds = new HashSet<>();
        paths.keySet().forEach(entry -> exportedIds.add(entry.ticketId()));
        var filtered = new DataExplorerDtos.Manifest(List.copyOf(paths.keySet()), manifest.tickets().stream()
                .filter(note -> exportedIds.contains(note.id())).toList(), paths.size(), paths.keySet().stream()
                .mapToLong(DataExplorerDtos.ManifestEntry::sizeBytes).sum(), paths.size());
        return out -> {
            var used = new HashSet<String>();
            try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zip.setLevel(Deflater.BEST_SPEED);
                for (var entry : paths.entrySet()) {
                    Path path = entry.getValue();
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Stored file is unavailable");
                    var item = new ZipEntry(ExplorerArchiveService.ExportPaths.filePath(entry.getKey(), false, prefixNames, used));
                    item.setTime(entry.getKey().submittedAt().toEpochMilli());
                    zip.putNextEntry(item);
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { input.transferTo(zip); }
                    zip.closeEntry();
                }
                if (includeText) {
                    for (var note : filtered.tickets()) {
                        zip.putNextEntry(new ZipEntry(ExplorerArchiveService.ExportPaths.ticketNotePath(note, false, used)));
                        zip.write(ExplorerArchiveService.ExportPaths.ticketMarkdown(note).getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                    zip.putNextEntry(new ZipEntry("index.csv"));
                    zip.write(ExplorerArchiveService.ExportPaths.indexCsv(filtered).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        };
    }

    @Transactional
    public CleanedFileDtos.Deleted delete(CleanedFileDtos.DeleteRequest request) {
        requireSuper();
        boolean explicit = request.ids() != null && !request.ids().isEmpty();
        if (explicit == (request.filters() != null)) bad("Choose file IDs or matching filters");
        if (explicit && request.excludedIds() != null && !request.excludedIds().isEmpty()) bad("Exclusions require matching filters");
        Specification<CleanedFile> spec;
        if (explicit) {
            if (new HashSet<>(request.ids()).size() != request.ids().size()) bad("Duplicate file IDs");
            spec = (root, query, cb) -> root.get("id").in(request.ids());
        } else {
            var f = request.filters();
            spec = filters(f.projectId(), f.departmentId(), f.status(), f.from(), f.to(), f.search());
            if (request.excludedIds() != null && !request.excludedIds().isEmpty())
                spec = spec.and((root, query, cb) -> cb.not(root.get("id").in(request.excludedIds())));
        }
        var selected = files.findAll(spec, Sort.by("id"));
        if (selected.size() != request.expectedCount() || (explicit && selected.size() != request.ids().size()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Files changed. Refresh and confirm the selection again.");
        var staged = new LinkedHashMap<Path, Path>();
        Path trash;
        try {
            trash = Files.isDirectory(directory) ? Files.createTempDirectory(directory.toRealPath(), ".deleting-") : null;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int completion) {
                    for (var entry : staged.entrySet()) {
                        try {
                            if (completion == STATUS_COMMITTED) Files.deleteIfExists(entry.getValue());
                            else Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(CleanedFileService.class).error("Could not finish cleaned-file deletion transaction", ex); }
                    }
                    if (trash != null) remove(trash);
                }
            });
            for (var entity : selected) {
                Path original = safeStoredPath(entity.getStorageKey());
                if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) continue;
                original = storedPath(entity.getStorageKey());
                Path target = trash.resolve(entity.getStorageKey());
                Files.move(original, target, StandardCopyOption.ATOMIC_MOVE);
                staged.put(original, target);
            }
            files.deleteAllInBatch(selected);
            files.flush();
            return new CleanedFileDtos.Deleted(selected.size());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete files. Please retry.");
        }
    }

    @Transactional
    public CleanedFileDtos.Row upload(Long projectId, Long departmentId, CleanedFileDtos.Metadata metadata, MultipartFile file) {
        requireSuper(); validate(metadata);
        var project = projects.findWithMembersById(projectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        var department = departments.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        if (!department.isActive() || department.getProject() == null || !projectId.equals(department.getProject().getId())
                || !Objects.equals(project.getTeam() == null ? null : project.getTeam().getId(), department.getTeam() == null ? null : department.getTeam().getId())) {
            bad("Choose an active department belonging to the selected project");
        }
        if (file == null || file.isEmpty()) bad("File is required");
        if (file.getSize() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Cleaned files must be at most " + maxBytes / 1048576 + " MB each");
        String name = Objects.toString(file.getOriginalFilename(), "file").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.length() > 250) bad("Invalid filename");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) bad("Unsupported cleaned file format");
        var actor = users.findById(TenantContext.getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        quota.chargeOrThrow(actor.getId(), file.getSize());
        Path stored = null;
        try {
            Files.createDirectories(directory);
            Path root = directory.toRealPath();
            String key = UUID.randomUUID() + "." + extension;
            stored = root.resolve(key);
            Path rollbackFile = stored;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) remove(rollbackFile);
                }
            });
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied;
            try (var input = new DigestInputStream(file.getInputStream(), digest)) { copied = Files.copy(input, stored); }
            if (copied != file.getSize() || copied > maxBytes) bad("File size does not match the upload");
            try (var input = Files.newInputStream(stored)) {
                if (!MIME.contains(TIKA.detect(input))) bad("File content is not a supported data or document format");
            }
            var entity = new CleanedFile();
            entity.setProject(project); entity.setDepartment(department); entity.setOriginalFilename(name);
            entity.setStorageKey(key); entity.setSizeBytes(copied); entity.setSha256(HexFormat.of().formatHex(digest.digest()));
            entity.setUploadedBy(actor.getUsername()); apply(entity, metadata);
            return row(files.saveAndFlush(entity));
        } catch (IOException | NoSuchAlgorithmException ex) {
            if (stored != null) remove(stored);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file. Please retry.");
        }
    }

    @Transactional
    public CleanedFileDtos.Row update(Long id, CleanedFileDtos.Update update) {
        requireSuper(); validate(update.metadata());
        var entity = find(id);
        if (entity.getVersion() != update.version()) throw new ResponseStatusException(HttpStatus.CONFLICT, "File changed. Refresh and try again.");
        apply(entity, update.metadata());
        if (entity.getStatus() != update.status()) {
            Instant now = Instant.now();
            if (update.status() != Status.READY && entity.getStartedAt() == null) entity.setStartedAt(now);
            entity.setCompletedAt(update.status() == Status.COMPLETED ? now : null);
            if (update.status() == Status.READY) entity.setStartedAt(null);
            entity.setStatus(update.status());
        }
        entity.setUpdatedAt(Instant.now());
        try { return row(files.saveAndFlush(entity)); }
        catch (ObjectOptimisticLockingFailureException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "File changed. Refresh and try again."); }
    }

    public record Download(Resource resource, String filename, long size) {}
    public Download download(Long id) {
        requireSuper(); var entity = find(id);
        Path path = storedPath(entity.getStorageKey());
        try {
            return new Download(new UrlResource(path.toUri()), entity.getOriginalFilename(), entity.getSizeBytes());
        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file is unavailable"); }
    }

    private Path safeStoredPath(String key) {
        Path path = directory.resolve(key).normalize();
        if (!path.startsWith(directory) || !path.getParent().equals(directory)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file is unavailable");
        return path;
    }
    private Path storedPath(String key) {
        Path path = safeStoredPath(key);
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().startsWith(directory.toRealPath())) throw new IOException("Missing file");
            return path;
        } catch (IOException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file is unavailable"); }
    }

    private long count(Specification<CleanedFile> spec, Status status) {
        return files.count(spec.and((root, query, cb) -> cb.equal(root.get("status"), status)));
    }
    private CleanedFile find(Long id) { return files.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found")); }
    private static void requireSuper() { if (!TenantContext.isSuperAdmin() || TenantContext.isImpersonating()) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private static void bad(String reason) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static void remove(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(CleanedFileService.class).warn("Could not remove rolled-back cleaned file {}", path.getFileName()); }
    }
    private static void validate(CleanedFileDtos.Metadata metadata) {
        if (metadata.cleanedOn().isAfter(LocalDate.now(ZoneId.of("Africa/Cairo")))) bad("Cleaning date cannot be in the future");
        if (metadata.dueOn() != null && metadata.dueOn().isBefore(metadata.cleanedOn())) bad("Due date cannot precede cleaning date");
    }
    private static void apply(CleanedFile entity, CleanedFileDtos.Metadata metadata) {
        entity.setTitle(metadata.title().trim()); entity.setCleanedOn(metadata.cleanedOn()); entity.setDueOn(metadata.dueOn());
        entity.setSourceReference(metadata.sourceReference()); entity.setNotes(metadata.notes());
    }
    private CleanedFileDtos.Row row(CleanedFile entity) {
        var p = entity.getProject(); var d = entity.getDepartment(); var team = p.getTeam();
        return new CleanedFileDtos.Row(entity.getId(), entity.getTitle(), entity.getOriginalFilename(), entity.getSizeBytes(), entity.getSha256(),
                team == null ? null : team.getId(), team == null ? null : team.getName(), p.getId(), p.getName(), d.getId(), d.getName(),
                entity.getCleanedOn(), entity.getDueOn(), entity.getSourceReference(), entity.getNotes(), entity.getStatus(), entity.getUploadedBy(),
                entity.getUploadedAt(), entity.getUpdatedAt(), entity.getStartedAt(), entity.getCompletedAt(), entity.getVersion());
    }
}
