package com.dataentry.controller;

import com.dataentry.dto.ApiTokenDtos;
import com.dataentry.service.ApiTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Super-admin CRUD for personal-access tokens used by the external export API.
 * URL-guarded to SUPER_ADMIN by {@code SecurityConfig} ({@code /api/super/**}).
 */
@RestController
@RequestMapping("/api/super/api-tokens")
public class ApiTokenAdminController {

    private final ApiTokenService service;

    public ApiTokenAdminController(ApiTokenService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiTokenDtos.Row> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<ApiTokenDtos.CreateResponse> create(
            @Valid @RequestBody ApiTokenDtos.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/revoke")
    public ApiTokenDtos.Row revoke(@PathVariable Long id) {
        return service.revoke(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
