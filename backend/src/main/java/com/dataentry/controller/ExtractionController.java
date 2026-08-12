package com.dataentry.controller;

import com.dataentry.model.User;
import com.dataentry.service.ExtractionStagingService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Serves images that were pulled out of a user's uploaded PDF and parked in the staging
 * area. Access is gated on the {@code .owner} marker inside the staging folder — a user
 * can only see their own in-flight extractions.
 * <p>
 * Only extension names are permitted in the URL to head off any attempt to fetch the
 * {@code .owner} marker itself.
 */
@RestController
@RequestMapping("/api/user/extractions")
public class ExtractionController {

    private final ExtractionStagingService staging;

    public ExtractionController(ExtractionStagingService staging) {
        this.staging = staging;
    }

    @GetMapping("/{extractionId}/images/{filename:.+}")
    public ResponseEntity<Resource> serveImage(
            @PathVariable String extractionId,
            @PathVariable String filename,
            @AuthenticationPrincipal User current) {

        if (current == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        // Only serve images. Blocks a caller from asking for `.owner` or any injected file.
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path file = staging.resolveOwned(extractionId, filename, current.getId());
        Resource resource;
        try {
            resource = new UrlResource(file.toUri());
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        MediaType type = mediaTypeFor(lower);
        long size;
        try {
            size = Files.size(file);
        } catch (java.io.IOException e) {
            size = -1;
        }

        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(size)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(resource);
    }

    private MediaType mediaTypeFor(String lowerName) {
        if (lowerName.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lowerName.endsWith(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
