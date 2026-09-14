package com.dataentry.controller;

import com.dataentry.dto.AssignmentDtos;
import com.dataentry.model.User;
import com.dataentry.service.AssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/admin/assignments — team leader hands work to an agent and tracks it.
 * /api/user/assignments  — the agent's own list; mark done / reopen.
 * Authorisation comes from the /api/admin/** and /api/user/** matchers in SecurityConfig.
 */
@RestController
@RequestMapping("/api")
public class AssignmentController {

    private final AssignmentService service;

    public AssignmentController(AssignmentService service) {
        this.service = service;
    }

    // ---- team leader

    @GetMapping("/admin/assignments")
    public AssignmentDtos.ListResponse listAll() {
        return service.listForTeam();
    }

    @PostMapping("/admin/assignments")
    public ResponseEntity<AssignmentDtos.Response> create(
            @AuthenticationPrincipal User current,
            @Valid @RequestBody AssignmentDtos.CreateRequest req) {
        return ResponseEntity.ok(service.create(current, req));
    }

    @PatchMapping("/admin/assignments/{id}")
    public AssignmentDtos.Response update(
            @PathVariable Long id,
            @Valid @RequestBody AssignmentDtos.UpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/admin/assignments/{id}/reopen")
    public AssignmentDtos.Response reopen(@PathVariable Long id) {
        return service.reopenByLeader(id);
    }

    @DeleteMapping("/admin/assignments/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- agent

    @GetMapping("/user/assignments")
    public AssignmentDtos.ListResponse listMine(@AuthenticationPrincipal User current) {
        return service.listForUser(current);
    }

    @PostMapping("/user/assignments/{id}/done")
    public AssignmentDtos.Response markDone(
            @PathVariable Long id,
            @AuthenticationPrincipal User current) {
        return service.markDone(id, current);
    }

    @PostMapping("/user/assignments/{id}/reopen")
    public AssignmentDtos.Response reopenMine(
            @PathVariable Long id,
            @AuthenticationPrincipal User current) {
        return service.reopenByAssignee(id, current);
    }
}
