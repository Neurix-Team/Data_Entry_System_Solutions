package com.dataentry.controller;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.service.DataExplorerService;
import com.dataentry.service.ExplorerArchiveService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Super-admin data explorer: one paginated view of every ticket in every team with the
 * uploads, custom fields, and submitter attached. URL protection comes from
 * {@code /api/super/**} in SecurityConfig.
 *
 * <p>Downloads reuse the existing {@code /api/tickets/{ticketId}/documents/{id}} path so
 * the browser can hit them with the session cookie — no need for a token from the UI.
 */
@RestController
@RequestMapping("/api/super/data")
public class DataExplorerController {

    private final DataExplorerService service;
    private final ExplorerArchiveService archive;

    public DataExplorerController(DataExplorerService service, ExplorerArchiveService archive) {
        this.service = service;
        this.archive = archive;
    }

    /**
     * Flat list of every attachment matching the filters (plus, optionally, every ticket's
     * text). The browser uses it to mirror files into a local folder tree with live progress.
     */
    @GetMapping("/manifest")
    public DataExplorerDtos.Manifest manifest(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeText
    ) {
        DataExplorerService.Filters f = new DataExplorerService.Filters(teamId, projectId, userId, from, to, search);
        return service.manifest(f, includeText);
    }

    /**
     * Same selection as {@link #manifest}, streamed as one ZIP laid out as
     * {@code Project/Department[/Subcategory]/file}. Fallback for browsers that cannot write
     * into a local folder directly.
     */
    @GetMapping(value = "/archive", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> archive(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean subcategoryFolders,
            @RequestParam(defaultValue = "false") boolean includeText
    ) {
        DataExplorerService.Filters f = new DataExplorerService.Filters(teamId, projectId, userId, from, to, search);
        String filename = "neurix-export-" + LocalDate.now() + ".zip";
        ContentDisposition cd = ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
        StreamingResponseBody body = out -> archive.writeZip(f, subcategoryFolders, includeText, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
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
        DataExplorerService.Filters f = new DataExplorerService.Filters(teamId, projectId, userId, from, to, search);
        // No downloadUrl prefix — the UI uses the session-authenticated
        // /api/tickets/{id}/documents/{docId} route which it already knows about.
        return service.search(f, cursor, size, null);
    }

    @GetMapping("/tickets/{id}")
    public DataExplorerDtos.Row ticket(@PathVariable Long id) {
        return service.byId(id, null);
    }

    @GetMapping("/facets")
    public DataExplorerDtos.Facets facets() {
        return service.facets();
    }
}
