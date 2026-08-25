package com.dataentry.controller;

import com.dataentry.dto.DataExplorerDtos;
import com.dataentry.service.DataExplorerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

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

    public DataExplorerController(DataExplorerService service) {
        this.service = service;
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
