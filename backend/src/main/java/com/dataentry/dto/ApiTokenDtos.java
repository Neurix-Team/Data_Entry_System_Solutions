package com.dataentry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class ApiTokenDtos {

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

    public record CreateRequest(
            @NotBlank @Size(max = 150) String name,
            @Min(0) Integer expiresInDays
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateResponse(
            Row token,
            String plaintext
    ) {}
}
