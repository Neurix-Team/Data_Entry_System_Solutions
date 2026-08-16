package com.dataentry.controller;

import com.dataentry.dto.DepartmentDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.service.DepartmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DepartmentController {

    private final DepartmentService service;
    private final ProjectRepository projectRepository;

    public DepartmentController(DepartmentService service, ProjectRepository projectRepository) {
        this.service = service;
        this.projectRepository = projectRepository;
    }

    // Admin: full list including inactive
    @GetMapping("/admin/departments")
    public List<DepartmentDtos.DepartmentResponse> adminList() {
        return service.listAll();
    }

    @PostMapping("/admin/departments")
    public ResponseEntity<DepartmentDtos.DepartmentResponse> create(
            @Valid @RequestBody DepartmentDtos.UpsertDepartmentRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PatchMapping("/admin/departments/{id}")
    public ResponseEntity<DepartmentDtos.DepartmentResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody DepartmentDtos.UpsertDepartmentRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/admin/departments/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Any authenticated user: only active departments for use in the form.
    // Cascading filters:
    //   - projectId given             → only departments of that project (scoped to member for USER)
    //   - ADMIN with no projectId     → all active departments
    //   - USER member of ≥1 project   → union of departments of the user's member projects
    //   - USER member of no projects  → empty list (admin hasn't scoped them yet)
    @GetMapping("/departments")
    public List<DepartmentDtos.DepartmentResponse> userList(
            @RequestParam(required = false) Long projectId,
            @AuthenticationPrincipal User current) {
        boolean isAdmin = current != null && current.getRole() == Role.ADMIN;
        if (projectId != null) {
            if (!isAdmin && !isMemberOf(current, projectId)) {
                return List.of();
            }
            return service.listActiveByProject(projectId);
        }
        if (isAdmin || current == null) return service.listActive();
        List<Long> memberProjectIds = projectRepository.findAllByMemberId(current.getId())
                .stream().map(p -> p.getId()).toList();
        if (memberProjectIds.isEmpty()) {
            return List.of();
        }
        return service.listActiveByProjects(memberProjectIds);
    }

    private boolean isMemberOf(User user, Long projectId) {
        if (user == null) return false;
        return projectRepository.findAllByMemberId(user.getId()).stream()
                .anyMatch(p -> p.getId().equals(projectId));
    }
}
