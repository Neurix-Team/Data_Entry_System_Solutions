package com.dataentry.controller;

import com.dataentry.dto.SubcategoryDtos;
import com.dataentry.service.SubcategoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SubcategoryController {

    private final SubcategoryService service;

    public SubcategoryController(SubcategoryService service) {
        this.service = service;
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

    @GetMapping("/subcategories")
    public List<SubcategoryDtos.SubcategoryResponse> userList(
            @RequestParam(required = false) Long departmentId) {
        return service.listAll(departmentId, true);
    }
}
