package com.dataentry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public class NotificationDtos {

    public record Item(
            Long id,
            String type,
            String message,
            String refType,
            Long refId,
            Long projectId,
            Instant createdAt,
            Instant readAt
    ) {}

    public record Feed(
            List<Item> items,
            long unread
    ) {}

    // ─── Browser push (Web Push) ──────────────────────────────────────────────

    /** The application-server key the browser needs to create a subscription. */
    public record PushKeyResponse(
            boolean enabled,
            String publicKey
    ) {}

    /** What the frontend collects from the browser PushSubscription and posts back. */
    public record PushSubscribeRequest(
            @NotBlank @Size(max = 2048) String endpoint,
            @NotBlank @Size(max = 512) String p256dh,
            @NotBlank @Size(max = 128) String auth,
            @Size(max = 300) String userAgent
    ) {}

    public record PushUnsubscribeRequest(
            @NotBlank @Size(max = 2048) String endpoint
    ) {}
}
