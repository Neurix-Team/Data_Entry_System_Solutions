package com.dataentry.controller;

import com.dataentry.dto.SubcategoryDtos;
import com.dataentry.model.Role;
import com.dataentry.model.User;
import com.dataentry.repository.ProjectRepository;
import com.dataentry.service.SubcategoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SubcategoryController {

    private final SubcategoryService service;
    private final ProjectRepository projectRepository;

    public SubcategoryController(SubcategoryService service, ProjectRepository projectRepository) {
        this.service = service;
        this.projectRepository = projectRepository;
    }

    @GetMapping("/admin/subcategories")
    public List<SubcategoryDtos.SubcategoryResponse> adminList(
            @RequestParam(required = false) Long departmentId) {
        return service.listAll(departmentId, false);
    }

    @GetMapping("/admin/subcategories/{id}")
    public SubcategoryDtos.SubcategoryResponse adminGet(@PathVariable Long id) {
        return service.getOne(id);
    }

    @PostMapping("/admin/subcategories")
    public ResponseEntity<SubcategoryDtos.SubcategoryResponse> create(
            @Valid @RequestBody SubcategoryDtos.UpsertSubcategoryRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PatchMapping("/admin/subcategories/{id}")
    public ResponseEntity<SubcategoryDtos.SubcategoryResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SubcategoryDtos.UpsertSubcategoryRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/admin/subcategories/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Cascading lookup:
     * <ul>
     *   <li>{@code departmentId} → active subcategories in that department (any user).</li>
     *   <li>{@code projectId} → active subcategories from any department in that project.</li>
     *   <li>Nothing set + USER role → active subcategories from any of the user's
     *       member projects (fallback to all active if the user isn't a member of any).</li>
     *   <li>Nothing set + ADMIN → all active subcategories.</li>
     * </ul>
     */
    @GetMapping("/subcategories")
    public List<SubcategoryDtos.SubcategoryResponse> userList(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long projectId,
            @AuthenticationPrincipal User current) {
        if (departmentId != null) {
            return service.listAll(departmentId, true);
        }
        if (projectId != null) {
            return service.listActiveByProjects(java.util.List.of(projectId));
        }
        boolean isAdmin = current != null && current.getRole() == Role.ADMIN;
        if (isAdmin || current == null) {
            return service.listAll(null, true);
        }
        List<Long> memberProjectIds = projectRepository.findAllByMemberId(current.getId())
                .stream().map(p -> p.getId()).toList();
        if (memberProjectIds.isEmpty()) {
            return service.listAll(null, true);
        }
        return service.listActiveByProjects(memberProjectIds);
    }
}
