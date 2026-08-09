package com.dataentry.controller;

import com.dataentry.dto.DepartmentDtos;
import com.dataentry.service.DepartmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DepartmentController {

    private final DepartmentService service;

    public DepartmentController(DepartmentService service) {
        this.service = service;
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

    // Any authenticated user: only active departments for use in the form
    @GetMapping("/departments")
    public List<DepartmentDtos.DepartmentResponse> userList() {
        return service.listActive();
    }
}
