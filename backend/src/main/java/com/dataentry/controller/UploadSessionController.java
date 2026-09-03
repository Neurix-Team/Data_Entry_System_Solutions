package com.dataentry.controller;

import com.dataentry.dto.UploadSessionDtos;
import com.dataentry.model.User;
import com.dataentry.service.ChunkedUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Chunked upload sessions. Mounted under the shared /api/ prefix so both USER and ADMIN
 * can use it; ownership and target access are enforced in the service.
 *
 * <p>Flow: {@code POST} opens a session and returns the chunk layout → the client
 * {@code PUT}s each chunk (several at a time) as a raw body → {@code POST .../complete}
 * validates and attaches the file. {@code GET} reports which chunks landed so an
 * interrupted client can resume; {@code DELETE} abandons the session and frees its disk.
 */
@RestController
@RequestMapping("/api/uploads/sessions")
public class UploadSessionController {

    private final ChunkedUploadService service;

    public UploadSessionController(ChunkedUploadService service) {
        this.service = service;
    }

    @PostMapping
    public UploadSessionDtos.SessionResponse create(
            @Valid @RequestBody UploadSessionDtos.CreateRequest req,
            @AuthenticationPrincipal User current) {
        return service.create(current, req);
    }

    @GetMapping("/{id}")
    public UploadSessionDtos.SessionResponse status(
            @PathVariable String id,
            @AuthenticationPrincipal User current) {
        return service.status(current, id);
    }

    /**
     * Raw chunk body — no multipart framing, so the bytes go from the socket straight
     * into the payload file. Any Content-Type is accepted; the client sends
     * {@code application/octet-stream}.
     */
    @PutMapping("/{id}/chunks/{index}")
    public UploadSessionDtos.ChunkAck chunk(
            @PathVariable String id,
            @PathVariable int index,
            HttpServletRequest request,
            @AuthenticationPrincipal User current) throws IOException {
        try (InputStream body = request.getInputStream()) {
            return service.writeChunk(current, id, index, body);
        }
    }

    @PostMapping("/{id}/complete")
    public UploadSessionDtos.CompleteResponse complete(
            @PathVariable String id,
            @AuthenticationPrincipal User current) {
        return service.complete(current, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> abort(
            @PathVariable String id,
            @AuthenticationPrincipal User current) {
        service.abort(current, id);
        return ResponseEntity.noContent().build();
    }
}
