package com.dataentry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Wire types for {@code /api/super/api-tokens}. Kept in one class so the shape of the
 * token-management endpoints is easy to see at a glance.
 */
public class ApiTokenDtos {

    /**
     * Row shown on the list page. Never includes the plaintext token — that's exposed once
     * at creation time in {@link CreateResponse}.
     */
    public record Row(
            Long id,
            String name,
            String prefix,
            Long createdByUserId,
            String createdByUsername,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt,
            boolean active
    ) {}

    /**
     * Create request. {@code expiresInDays} of 0 or null means never expires.
     */
    public record CreateRequest(
            @NotBlank @Size(max = 150) String name,
            @Min(0) Integer expiresInDays
    ) {}

    /**
     * Response from create — includes the plaintext token exactly once. Callers must copy
     * it immediately; there's no way to see it again after this response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateResponse(
            Row token,
            String plaintext
    ) {}
}
