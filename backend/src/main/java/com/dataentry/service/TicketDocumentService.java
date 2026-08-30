package com.dataentry.service;

import com.dataentry.dto.TicketDtos;
import com.dataentry.model.Ticket;
import com.dataentry.model.TicketDocument;
import com.dataentry.model.User;
import com.dataentry.repository.TicketDocumentRepository;
import com.dataentry.repository.TicketRepository;
import java.util.List;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Persists uploaded files attached to a ticket. Bytes go on disk, metadata to DB. */
@Service
public class TicketDocumentService {

    private static final Logger log = LoggerFactory.getLogger(TicketDocumentService.class);

    /** Per-file cap. Anything larger is rejected before we start streaming to disk. */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024; // 50 MB

    /** Extensions we explicitly refuse — executables, scripts, and other risky types.
     *  Compared case-insensitively against the trailing suffix of the original filename. */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "com", "bat", "cmd", "sh", "bash", "zsh", "ps1", "psm1", "vbs", "vbe",
            "js", "jse", "jar", "msi", "msp", "scr", "cpl", "dll", "so", "dylib",
            "app", "apk", "ipa", "deb", "rpm", "pkg", "run", "bin", "elf",
            "reg", "hta", "chm", "lnk", "url", "wsf", "wsh"
    );

    /**
     * Narrow allowlist. `text/html`, `image/svg+xml`, `application/xhtml+xml`, and other
     * script-carrying types are intentionally absent — attachments are served back on the
     * API origin, so allowing HTML would let a user store a script that runs with any
     * viewer's session (Stored XSS).
     */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/tiff",
            "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/webm",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime",
            "text/plain", "text/csv", "text/markdown",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/rtf",
            "application/json",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-7z-compressed",
            "application/x-rar-compressed",
            "application/x-tar",
            "application/gzip"
    );

    /** Tika instance for magic-byte detection. Safe to share — Tika.detect is thread-safe. */
    private static final Tika TIKA = new Tika();

    private final TicketRepository ticketRepository;
    private final TicketDocumentRepository documentRepository;
    private final UploadQuotaService quota;
    private final ExtractionStagingService staging;
    private final Path baseDir;

    public TicketDocumentService(TicketRepository ticketRepository,
                                 TicketDocumentRepository documentRepository,
                                 UploadQuotaService quota,
                                 ExtractionStagingService staging,
                                 @Value("${app.attachments.dir:./data/attachments}") String baseDir) {
        this.ticketRepository = ticketRepository;
        this.documentRepository = documentRepository;
        this.quota = quota;
        this.staging = staging;
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            log.warn("Could not create attachments directory {}: {}", this.baseDir, e.getMessage());
        }
    }

    @Transactional
    public TicketDtos.DocumentResponse upload(Long ticketId, String name, MultipartFile file, User currentUser, boolean isAdmin) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds " + (MAX_FILE_BYTES / (1024 * 1024)) + " MB limit");
        }
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        com.dataentry.security.TenantGuard.assertOwnership(ticket);
        if (!isAdmin && !ticket.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        String safeName = (name == null || name.isBlank())
                ? sanitiseFilename(file.getOriginalFilename())
                : name.trim();
        if (safeName.length() > 250) safeName = safeName.substring(0, 250);
        String originalFilename = sanitiseFilename(file.getOriginalFilename());

        // Reject risky extensions first — cheap and covers most obvious cases.
        String ext = extensionOf(originalFilename);
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type '." + ext + "' is not allowed");
        }

        // Sniff the real MIME by content, not the header the browser sent. This is what
        // catches a `.jpg`-renamed HTML payload that would otherwise be served as an image.
        String detectedMime;
        try (InputStream sniff = file.getInputStream()) {
            detectedMime = TIKA.detect(sniff, originalFilename).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file");
        }
        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type '" + detectedMime + "' is not allowed");
        }

        quota.chargeOrThrow(currentUser.getId(), file.getSize());

        Path ticketDir = baseDir.resolve(String.valueOf(ticketId));
        try {
            Files.createDirectories(ticketDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare storage");
        }

        String storedName = UUID.randomUUID().toString() + suffixOf(originalFilename);
        Path target = ticketDir.resolve(storedName);
        try {
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save file");
        }

        TicketDocument doc = TicketDocument.builder()
                .ticket(ticket)
                .name(safeName)
                .originalFilename(originalFilename)
                // Store the *detected* type, not the client-supplied one. This is what the
                // download endpoint replays, so it must reflect what's really on disk.
                .contentType(detectedMime)
                .sizeBytes(file.getSize())
                .storagePath(ticketId + "/" + storedName)
                .uploadedAt(Instant.now())
                .build();
        documentRepository.save(doc);

        return new TicketDtos.DocumentResponse(
                doc.getId(), doc.getName(), doc.getOriginalFilename(),
                doc.getContentType(), doc.getSizeBytes(), doc.getUploadedAt()
        );
    }

    @Transactional(readOnly = true)
    public DownloadHandle download(Long ticketId, Long docId, User currentUser, boolean isAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        com.dataentry.security.TenantGuard.assertOwnership(ticket);
        if (!isAdmin && !ticket.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        TicketDocument doc = documentRepository.findByIdAndTicketId(docId, ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Guard against path-traversal in storagePath — stored value must resolve inside baseDir.
        Path abs = baseDir.resolve(doc.getStoragePath()).normalize();
        if (!abs.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!Files.exists(abs)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
        }
        Resource resource;
        try {
            resource = new UrlResource(abs.toUri());
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new DownloadHandle(resource, doc.getOriginalFilename(), doc.getContentType(), doc.getSizeBytes());
    }

    @Transactional
    public void delete(Long ticketId, Long docId, User currentUser, boolean isAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        com.dataentry.security.TenantGuard.assertOwnership(ticket);
        if (!isAdmin && !ticket.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        TicketDocument doc = documentRepository.findByIdAndTicketId(docId, ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        Path abs = baseDir.resolve(doc.getStoragePath()).normalize();
        if (abs.startsWith(baseDir)) {
            try { Files.deleteIfExists(abs); } catch (IOException ignored) { }
        }
        documentRepository.delete(doc);
    }

    /**
     * Promote a batch of images that were previously written to the extractions staging
     * area into permanent attachments on {@code ticket}. Each staged file is moved onto the
     * ticket's attachments folder and a {@link TicketDocument} row is inserted.
     * <p>
     * References that point at an already-consumed or missing staged file are skipped
     * silently — the client is allowed to resubmit the same list without erroring.
     * <p>
     * The staging folder is discarded once every reference has been walked, whether or not
     * every individual move succeeded, so we don't leak on-disk state.
     */
    @Transactional
    public void attachExtractedImages(Ticket ticket,
                                      List<TicketDtos.ExtractedImageRef> refs,
                                      User currentUser) {
        if (refs == null || refs.isEmpty()) return;

        Path ticketDir = baseDir.resolve(String.valueOf(ticket.getId()));
        try {
            Files.createDirectories(ticketDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not prepare attachments storage for ticket");
        }

        java.util.Set<String> touchedExtractions = new java.util.HashSet<>();
        for (TicketDtos.ExtractedImageRef ref : refs) {
            touchedExtractions.add(ref.extractionId());

            String suffix = suffixOf(ref.filename());
            String storedName = UUID.randomUUID() + suffix;
            Path target = ticketDir.resolve(storedName);

            boolean moved = staging.moveOut(ref.extractionId(), ref.filename(),
                    currentUser.getId(), target);
            if (!moved) {
                log.debug("Staged image {}/{} already consumed — skipping", ref.extractionId(), ref.filename());
                continue;
            }

            long size;
            try {
                size = Files.size(target);
            } catch (IOException e) {
                size = 0;
            }

            String safeName = ref.name() == null || ref.name().isBlank()
                    ? ref.filename()
                    : ref.name().trim();
            if (safeName.length() > 250) safeName = safeName.substring(0, 250);

            TicketDocument doc = TicketDocument.builder()
                    .ticket(ticket)
                    .name(safeName)
                    .originalFilename(ref.filename())
                    .contentType(guessContentType(ref.filename()))
                    .sizeBytes(size)
                    .storagePath(ticket.getId() + "/" + storedName)
                    .uploadedAt(Instant.now())
                    .build();
            documentRepository.save(doc);
        }

        // Cleanup the staging folders once we're done — image files are gone but the
        // .owner marker and empty dir still sit around.
        touchedExtractions.forEach(staging::discard);
    }

    private String guessContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    /** Best-effort recursive wipe of every file stored under {ticketId}/.
     *  Called from TicketService.delete so we don't leak disk when tickets are removed
     *  (the DB rows go via JPA cascade, but the bytes on disk would otherwise linger). */
    public void purgeTicketDirectory(Long ticketId) {
        Path dir = baseDir.resolve(String.valueOf(ticketId)).normalize();
        if (!dir.startsWith(baseDir) || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        } catch (IOException e) {
            log.warn("Failed to purge attachments directory for ticket {}: {}", ticketId, e.getMessage());
        }
    }

    private String sanitiseFilename(String s) {
        if (s == null || s.isBlank()) return "file";
        String cleaned = s.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[\\r\\n\\t]", "").trim();
        if (cleaned.isEmpty()) return "file";
        if (cleaned.length() > 250) cleaned = cleaned.substring(0, 250);
        return cleaned;
    }

    private String suffixOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) return "";
        String ext = filename.substring(dot + 1);
        if (!ext.matches("[A-Za-z0-9]{1,10}")) return "";
        return "." + ext.toLowerCase();
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record DownloadHandle(Resource resource, String filename, String contentType, long size) {}
}
