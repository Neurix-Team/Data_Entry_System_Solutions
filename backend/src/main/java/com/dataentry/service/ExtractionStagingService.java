package com.dataentry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns the {@code data/extractions/{extractionId}/} directory tree where images pulled
 * out of an uploaded PDF live between the extraction API call and the ticket-submit call.
 * <p>
 * Each staging folder holds:
 * <ul>
 *   <li>the image files themselves ({@code image-01.png}, {@code image-02.png}, ...);</li>
 *   <li>a {@code .owner} marker containing the owning user id — used by
 *       {@link #assertOwned(String, Long)} to gate serving and promotion.</li>
 * </ul>
 * Old folders are swept lazily whenever a new staging is created, so we don't need to wire
 * up Spring's scheduling infrastructure just for this.
 */
@Service
public class ExtractionStagingService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionStagingService.class);
    private static final String OWNER_FILE = ".owner";

    private final Path baseDir;
    private final long ttlHours;

    public ExtractionStagingService(
            @Value("${app.pdf.extractions-dir:./data/extractions}") String baseDir,
            @Value("${app.pdf.extractions-ttl-hours:24}") long ttlHours) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        this.ttlHours = ttlHours;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create extractions staging directory: " + this.baseDir, e);
        }
    }

    /** Create a fresh staging directory owned by {@code userId} and return its id + path. */
    public Handle create(Long userId) {
        sweepExpired();
        String extractionId = UUID.randomUUID().toString();
        Path dir = baseDir.resolve(extractionId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(OWNER_FILE), String.valueOf(userId));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not prepare image staging directory");
        }
        return new Handle(extractionId, dir);
    }

    /**
     * Resolve a staged image file, first checking that the current user owns the extraction
     * and that the filename does not escape the folder. Throws 404 / 403 as appropriate.
     */
    public Path resolveOwned(String extractionId, String filename, Long userId) {
        Path dir = resolveDir(extractionId);
        assertOwned(extractionId, userId);
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir)) {
            // Traversal attempt (e.g., filename="../.owner"). Refuse before touching disk.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staged image not found");
        }
        if (OWNER_FILE.equals(target.getFileName().toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return target;
    }

    /**
     * Move a staged file out of the staging folder into an arbitrary destination.
     * Used by TicketService to promote images into a ticket's attachments folder.
     * The staging entry is deleted after a successful move.
     * <p>
     * Silently ignored (returns false) if the file has already been consumed — this keeps
     * the submit call idempotent when the same image is referenced twice.
     */
    public boolean moveOut(String extractionId, String filename, Long userId, Path destination) {
        Path source = resolveDir(extractionId).resolve(filename).normalize();
        if (!source.startsWith(resolveDir(extractionId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!Files.exists(source)) return false;
        assertOwned(extractionId, userId);
        try {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not promote extracted image: " + e.getMessage());
        }
    }

    /** Delete a whole extraction folder — called after all images have been consumed. */
    public void discard(String extractionId) {
        Path dir = baseDir.resolve(extractionId).normalize();
        if (!dir.startsWith(baseDir) || !Files.exists(dir)) return;
        deleteRecursively(dir);
    }

    private Path resolveDir(String extractionId) {
        if (extractionId == null || extractionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path dir = baseDir.resolve(extractionId).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!Files.exists(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Extraction has expired or does not exist");
        }
        return dir;
    }

    private void assertOwned(String extractionId, Long userId) {
        Path marker = baseDir.resolve(extractionId).resolve(OWNER_FILE);
        if (!Files.exists(marker)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String stored;
        try {
            stored = Files.readString(marker).trim();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (userId == null || !stored.equals(String.valueOf(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void sweepExpired() {
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        deleteRecursively(dir);
                        log.debug("Swept expired staging dir {}", dir.getFileName());
                    }
                } catch (IOException e) {
                    log.debug("Skipping unreadable staging dir {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to sweep expired staging dirs: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to remove staging dir {}: {}", dir, e.getMessage());
        }
    }

    public record Handle(String extractionId, Path directory) {}
}
