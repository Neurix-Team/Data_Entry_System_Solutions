package com.dataentry.controller;

import com.dataentry.dto.ProjectDtos;
import com.dataentry.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectDtos.ProjectResponse> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<ProjectDtos.ProjectResponse> create(
            @Valid @RequestBody ProjectDtos.UpsertProjectRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProjectDtos.ProjectResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ProjectDtos.UpsertProjectRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
