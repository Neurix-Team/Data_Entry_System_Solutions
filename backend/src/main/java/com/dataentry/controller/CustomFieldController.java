package com.dataentry.controller;

import com.dataentry.dto.CustomFieldDtos;
import com.dataentry.service.CustomFieldService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CustomFieldController {

    private final CustomFieldService service;

    public CustomFieldController(CustomFieldService service) {
        this.service = service;
    }

    @GetMapping("/admin/fields")
    public List<CustomFieldDtos.FieldResponse> adminList(
            @RequestParam(required = false) Long subcategoryId) {
        return service.listAll(subcategoryId);
    }

    @PostMapping("/admin/fields")
    public ResponseEntity<CustomFieldDtos.FieldResponse> create(
            @Valid @RequestBody CustomFieldDtos.UpsertFieldRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @PatchMapping("/admin/fields/{id}")
    public ResponseEntity<CustomFieldDtos.FieldResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody CustomFieldDtos.UpsertFieldRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/admin/fields/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/fields")
    public List<CustomFieldDtos.FieldResponse> activeList(
            @RequestParam(required = false) Long subcategoryId) {
        return service.listActive(subcategoryId);
    }
}
