package com.dataentry.dto;

import java.time.Instant;
import java.util.List;

public class AuditLogDtos {

    public record AuditLogResponse(
            Long id,
            Long actorId,
            String actorUsername,
            String action,
            String entityType,
            Long entityId,
            String details,
            Instant createdAt
    ) {}

    public record AuditLogPage(
            List<AuditLogResponse> items,
            long totalItems,
            int totalPages,
            int page,
            int size
    ) {}
}
