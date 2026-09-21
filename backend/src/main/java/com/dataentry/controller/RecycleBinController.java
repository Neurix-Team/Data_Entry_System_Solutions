package com.dataentry.controller;

import com.dataentry.dto.RecycleBinDtos;
import com.dataentry.model.User;
import com.dataentry.service.RecycleBinService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recycle bin endpoints. /admin/** sees the whole team's bin (plus projects);
 * /user/** is the personal bin — an agent can list and restore their own entries.
 * Route security is already enforced by SecurityConfig (/api/admin/** vs /api/user/**).
 */
@RestController
@RequestMapping("/api")
public class RecycleBinController {

    private final RecycleBinService service;

    public RecycleBinController(RecycleBinService service) {
        this.service = service;
    }

    @GetMapping("/admin/recycle-bin")
    public RecycleBinDtos.BinPage adminList(
            @RequestParam(defaultValue = "tickets") String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if ("projects".equalsIgnoreCase(type)) {
            return service.listProjects(Math.max(page, 0), size);
        }
        return service.listTickets(Math.max(page, 0), size);
    }

    @GetMapping("/user/recycle-bin")
    public RecycleBinDtos.BinPage myList(
            @AuthenticationPrincipal User current,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listOwnTickets(current, Math.max(page, 0), size);
    }

    @PostMapping("/admin/recycle-bin/tickets/{id}/restore")
    public ResponseEntity<Void> restoreTicket(@PathVariable Long id,
                                              @AuthenticationPrincipal User current) {
        service.restoreTicket(id, current, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/user/recycle-bin/tickets/{id}/restore")
    public ResponseEntity<Void> restoreMyTicket(@PathVariable Long id,
                                                @AuthenticationPrincipal User current) {
        service.restoreTicket(id, current, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/recycle-bin/projects/{id}/restore")
    public ResponseEntity<Void> restoreProject(@PathVariable Long id) {
        service.restoreProject(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/recycle-bin/tickets/{id}")
    public ResponseEntity<Void> purgeTicket(@PathVariable Long id) {
        service.purgeTicket(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admin/recycle-bin/projects/{id}")
    public ResponseEntity<Void> purgeProject(@PathVariable Long id) {
        service.purgeProject(id);
        return ResponseEntity.noContent().build();
    }
}