package com.dataentry.service;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
 
@Service
public class ChatFilesService {

    private static final Logger log = LoggerFactory.getLogger(ChatFilesService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "gif",          // images
            "pdf",                                        // PDF
            "doc", "docx"                                 // Word
    );

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf", "text/plain", "text/rtf"
    );

    private static final Tika TIKA = new Tika();

    private final Path baseDir;
    private final long maxFileBytes;
    private final UploadQuotaService quota;

    public ChatFilesService(@Value("${app.attachments.dir:${user.dir}/data/attachments}") String baseDir,
                            @Value("${app.chat.max-file-bytes:26214400}") long maxFileBytes,
                            UploadQuotaService quota) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize().resolve("chat");
        this.maxFileBytes = maxFileBytes;
        this.quota = quota;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            log.warn("Could not create chat attachments directory {}: {}", this.baseDir, e.getMessage());
        }
    }

    public record SavedFile(String originalFilename, String storedName,
                            String contentType, long sizeBytes, Path path) {}
// __PART2__

    /** Validates, charges the daily upload budget and stores the file under chat/{conversationId}/. */
    public SavedFile store(Long conversationId, MultipartFile file, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        String original = TicketDocumentService.sanitiseFilename(file.getOriginalFilename());
        String ext = extensionOf(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only images (PNG/JPG/WEBP/GIF), PDF and Word files are allowed in chat.");
        }
        if (file.getSize() > maxFileBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File too large. Maximum " + (maxFileBytes / (1024 * 1024)) + " MB per file in chat.");
        }
        String detected;
        try (InputStream in = file.getInputStream()) {
            detected = TIKA.detect(in, original);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read file");
        }
        if (!ALLOWED_MIME_TYPES.contains(detected)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "File content (" + detected + ") is not an allowed type.");
        }
        quota.chargeOrThrow(uploaderId, file.getSize());

        Path dir = baseDir.resolve(String.valueOf(conversationId)).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("Could not create chat dir {}: {}", dir, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String storedName = UUID.randomUUID() + (ext.isBlank() ? "" : "." + ext);
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to store chat attachment {}: {}", target, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new SavedFile(original, storedName, detected, file.getSize(), target);
    }

    public record DownloadHandle(Resource resource, String filename, String contentType, long size) {}

    public DownloadHandle resolveStored(Long conversationId, String storedName, String filename,
                                        String contentType, long size) {
        Path dir = baseDir.resolve(String.valueOf(conversationId)).normalize();
        if (!dir.startsWith(baseDir)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path path = dir.resolve(storedName).normalize();
        if (!path.startsWith(baseDir) || !Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment file not found");
        }
        try {
            Resource resource = new UrlResource(path.toUri());
            return new DownloadHandle(resource, filename, contentType, size);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** IMAGE | PDF | DOC — drives the frontend bubble rendering. */
    public static String kindOf(String contentType, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (contentType != null && contentType.startsWith("image/")) return "IMAGE";
        if (lower.endsWith(".pdf") || "application/pdf".equals(contentType)) return "PDF";
        return "DOC";
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot >= filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
