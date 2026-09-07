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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TicketDocumentService {

    private static final Logger log = LoggerFactory.getLogger(TicketDocumentService.class);

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "com", "bat", "cmd", "sh", "bash", "zsh", "ps1", "psm1", "vbs", "vbe",
            "js", "jse", "jar", "msi", "msp", "scr", "cpl", "dll", "so", "dylib",
            "app", "apk", "ipa", "deb", "rpm", "pkg", "run", "bin", "elf",
            "reg", "hta", "chm", "lnk", "url", "wsf", "wsh"
    );

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

    private static final Tika TIKA = new Tika();

    public record IncomingFile(Path path, String originalFilename, long size) {}

    private final TicketRepository ticketRepository;
    private final TicketDocumentRepository documentRepository;
    private final UploadQuotaService quota;
    private final ExtractionStagingService staging;
    private final Path baseDir;

    private final Path incomingDir;

    private final long maxFileBytes;

    public TicketDocumentService(TicketRepository ticketRepository,
                                 TicketDocumentRepository documentRepository,
                                 UploadQuotaService quota,
                                 ExtractionStagingService staging,
                                 @Value("${app.attachments.dir:./data/attachments}") String baseDir,
                                 @Value("${app.uploads.incoming-dir:./data/attachments/.incoming}") String incomingDir,
                                 @Value("${app.attachments.max-file-bytes:524288000}") long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
        this.ticketRepository = ticketRepository;
        this.documentRepository = documentRepository;
        this.quota = quota;
        this.staging = staging;
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.incomingDir = Paths.get(incomingDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
            Files.createDirectories(this.incomingDir);
        } catch (IOException e) {
            log.warn("Could not create attachments directories {} / {}: {}",
                    this.baseDir, this.incomingDir, e.getMessage());
        }
    }

    @Transactional
    public TicketDtos.DocumentResponse upload(Long ticketId, String name, MultipartFile file, User currentUser, boolean isAdmin) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is required");
        }
        if (file.getSize() > maxFileBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds " + (maxFileBytes / (1024 * 1024)) + " MB limit");
        }
        String originalFilename = sanitiseFilename(file.getOriginalFilename());
        assertExtensionAllowed(originalFilename);

        Path temp = incomingDir.resolve(UUID.randomUUID() + ".part");
        try {
            try {
                file.transferTo(temp);
            } catch (IOException | IllegalStateException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file");
            }
            long actualSize;
            try {
                actualSize = Files.size(temp);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save file");
            }
            return attach(ticketId, name, new IncomingFile(temp, originalFilename, actualSize), currentUser, isAdmin);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log.warn("Could not remove temp upload {}: {}", temp, e.getMessage());
            }
        }
    }

    @Transactional
    public TicketDtos.DocumentResponse attach(Long ticketId, String name, IncomingFile incoming,
                                              User currentUser, boolean isAdmin) {
        Ticket ticket = loadForAttach(ticketId, currentUser, isAdmin);

        String originalFilename = sanitiseFilename(incoming.originalFilename());
        assertExtensionAllowed(originalFilename);
        String safeName = (name == null || name.isBlank()) ? originalFilename : name.trim();
        if (safeName.length() > 250) safeName = safeName.substring(0, 250);

        long actualSize = incoming.size();
        if (actualSize <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (actualSize > maxFileBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds " + (maxFileBytes / (1024 * 1024)) + " MB limit");
        }
        Path source = incoming.path();

        String detectedMime;
        try (InputStream sniff = new BufferedInputStream(Files.newInputStream(source))) {
            detectedMime = TIKA.detect(sniff, originalFilename).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file");
        }
        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type '" + detectedMime + "' is not allowed");
        }

        String contentHash = sha256HexOf(source);
        TicketDocument duplicate = findDuplicate(ticket, contentHash);
        if (duplicate != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, describeDuplicate(duplicate));
        }

        quota.chargeOrThrow(currentUser.getId(), actualSize);

        Path ticketDir = baseDir.resolve(String.valueOf(ticketId));
        try {
            Files.createDirectories(ticketDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare storage");
        }
        String storedName = UUID.randomUUID().toString() + suffixOf(originalFilename);
        Path target = ticketDir.resolve(storedName);

        TicketDocument doc = TicketDocument.builder()
                .ticket(ticket)
                .name(safeName)
                .originalFilename(originalFilename)
                .contentType(detectedMime)
                .sizeBytes(actualSize)
                .storagePath(ticketId + "/" + storedName)
                .contentHash(contentHash)
                .uploadedAt(Instant.now())
                .build();
        documentRepository.save(doc);

        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save file");
        }

        return new TicketDtos.DocumentResponse(
                doc.getId(), doc.getName(), doc.getOriginalFilename(),
                doc.getContentType(), doc.getSizeBytes(), doc.getUploadedAt()
        );
    }

    @Transactional(readOnly = true)
    public void assertCanAttach(Long ticketId, User currentUser, boolean isAdmin) {
        loadForAttach(ticketId, currentUser, isAdmin);
    }

    public void assertExtensionAllowed(String filename) {
        String ext = extensionOf(filename);
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File type '." + ext + "' is not allowed");
        }
    }

    private Ticket loadForAttach(Long ticketId, User currentUser, boolean isAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        com.dataentry.security.TenantGuard.assertOwnership(ticket);
        if (!isAdmin && !ticket.getSubmittedBy().getId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return ticket;
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
            String hash = null;
            try {
                size = Files.size(target);
                hash = sha256Hex(Files.readAllBytes(target));
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
                    .contentHash(hash)
                    .uploadedAt(Instant.now())
                    .build();
            documentRepository.save(doc);
        }

        touchedExtractions.forEach(staging::discard);
    }

    private static String sha256HexOf(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save file");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private TicketDocument findDuplicate(Ticket ticket, String hash) {
        if (hash == null || hash.isBlank()) return null;
        List<TicketDocument> matches;
        if (ticket.getProject() != null) {
            matches = documentRepository.findByProjectAndHash(ticket.getProject().getId(), hash);
        } else if (ticket.getTeam() != null) {
            matches = documentRepository.findByTeamAndHashWithoutProject(
                    ticket.getTeam().getId(), hash);
        } else {
            return null;
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String describeDuplicate(TicketDocument existing) {
        String name = existing.getOriginalFilename() != null
                ? existing.getOriginalFilename()
                : existing.getName();
        return "This file already exists in the project — uploaded as \""
                + name + "\" on ticket #" + existing.getTicket().getId() + ".";
    }

    private String guessContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

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

    public static String sanitiseFilename(String s) {
        if (s == null || s.isBlank()) return "file";
        String cleaned = s.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        cleaned = cleaned.replaceAll("[\\r\\n\\t]", "").trim();
        if (cleaned.isEmpty()) return "file";
        if (cleaned.length() > 250) cleaned = cleaned.substring(0, 250);
        return cleaned;
    }

    private static String suffixOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) return "";
        String ext = filename.substring(dot + 1);
        if (!ext.matches("[A-Za-z0-9]{1,10}")) return "";
        return "." + ext.toLowerCase();
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record DownloadHandle(Resource resource, String filename, String contentType, long size) {}
}
