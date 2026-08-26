package com.dataentry.controller;

import com.dataentry.dto.NotificationDtos;
import com.dataentry.model.User;
import com.dataentry.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * User-scoped notification feed. The service enforces "you can only see / act on your
 * own notifications" — the endpoints just wrap the current auth principal.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationDtos.Feed list(@AuthenticationPrincipal User current) {
        return service.list(current);
    }

    @PostMapping("/{id}/read")
    public NotificationDtos.Item markRead(
            @PathVariable Long id,
            @AuthenticationPrincipal User current) {
        return service.markRead(current, id);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead(@AuthenticationPrincipal User current) {
        int updated = service.markAllRead(current);
        return ResponseEntity.ok(Map.of("updated", updated));
    }
}
