package com.dataentry.controller;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.model.TicketDocument;
import com.dataentry.repository.TicketDocumentRepository;
import com.dataentry.service.DataExplorerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * External export API. Authenticated by personal-access token
 * (see {@link com.dataentry.security.ApiTokenAuthFilter}); role gate is {@code ROLE_API}
 * enforced in SecurityConfig. Cross-team by design — a token holder pulls every ticket
 * across the whole install into their own downstream (typically an AI ingest pipeline).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/export/tickets} — cursor-paginated ticket rows with document
 *       metadata and per-document download URLs.</li>
 *   <li>{@code GET /api/v1/export/tickets/{id}} — single ticket detail.</li>
 *   <li>{@code GET /api/v1/export/documents/{id}/download} — stream the raw file bytes.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/export")
public class ExportApiController {

    /** Prefix baked into the download URLs returned by /tickets so consumers don't have
     *  to build them themselves. */
    private static final String DOWNLOAD_PREFIX = "/api/v1/export/documents/";

    private final DataExplorerService service;
    private final TicketDocumentRepository documentRepository;
    private final Path attachmentsBase;

    public ExportApiController(DataExplorerService service,
                               TicketDocumentRepository documentRepository,
                               @Value("${app.attachments.dir:./data/attachments}") String baseDir) {
        this.service = service;
        this.documentRepository = documentRepository;
        this.attachmentsBase = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @GetMapping("/tickets")
    public DataExplorerDtos.Page tickets(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return service.search(
                new DataExplorerService.Filters(teamId, projectId, userId, from, to, search),
                cursor, size, DOWNLOAD_PREFIX);
    }

    @GetMapping("/tickets/{id}")
    public DataExplorerDtos.Row ticket(@PathVariable Long id) {
        return service.byId(id, DOWNLOAD_PREFIX);
    }

    /**
     * Stream the raw file bytes of a single attachment. Cross-team — the token holder
     * can download any document across the install (that's the whole point of the export
     * API). Same path-traversal guard as {@link com.dataentry.service.TicketDocumentService}.
     */
    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        TicketDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        Path abs = attachmentsBase.resolve(doc.getStoragePath()).normalize();
        if (!abs.startsWith(attachmentsBase)) {
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

        MediaType type;
        try {
            type = doc.getContentType() != null
                    ? MediaType.parseMediaType(doc.getContentType())
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }

        ContentDisposition cd = ContentDisposition.attachment()
                .filename(doc.getOriginalFilename() == null ? "file" : doc.getOriginalFilename(),
                        StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(doc.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(resource);
    }
}
