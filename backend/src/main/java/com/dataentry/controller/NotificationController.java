package com.dataentry.controller;

import com.dataentry.dto.NotificationDtos;
import com.dataentry.model.User;
import com.dataentry.service.NotificationService;
import com.dataentry.service.WebPushService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final WebPushService pushService;

    public NotificationController(NotificationService service, WebPushService pushService) {
        this.service = service;
        this.pushService = pushService;
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

    // ─── Browser push (Web Push) ──────────────────────────────────────────────

    /** The application-server key, plus whether push is configured at all. */
    @GetMapping("/push/key")
    public NotificationDtos.PushKeyResponse pushKey() {
        return new NotificationDtos.PushKeyResponse(pushService.isEnabled(),
                pushService.vapidPublicKey());
    }

    @PostMapping("/push/subscribe")
    public ResponseEntity<Void> subscribe(
            @Valid @RequestBody NotificationDtos.PushSubscribeRequest req,
            @AuthenticationPrincipal User current) {
        pushService.subscribe(current, req.endpoint(), req.p256dh(), req.auth(), req.userAgent());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/push/unsubscribe")
    public ResponseEntity<Void> unsubscribe(
            @Valid @RequestBody NotificationDtos.PushUnsubscribeRequest req,
            @AuthenticationPrincipal User current) {
        pushService.unsubscribe(current, req.endpoint());
        return ResponseEntity.noContent().build();
    }
}
