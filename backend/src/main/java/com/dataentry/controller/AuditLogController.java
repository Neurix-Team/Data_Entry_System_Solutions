package com.dataentry.controller;

import com.dataentry.dto.AuditLogDtos;
import com.dataentry.model.AuditLog;
import com.dataentry.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {

    private final AuditLogRepository repository;

    public AuditLogController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public AuditLogDtos.AuditLogPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long actorId
    ) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(size, 1), 200);   // same clamp pattern as ticket list
        PageRequest pr = PageRequest.of(p, s);

        Page<AuditLog> rows;
        if (entityType != null && entityId != null) {
            rows = repository.findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId, pr);
        } else if (actorId != null) {
            rows = repository.findAllByActorIdOrderByCreatedAtDesc(actorId, pr);
        } else {
            rows = repository.findAllByOrderByCreatedAtDesc(pr);
        }
        return new AuditLogDtos.AuditLogPage(
                rows.getContent().stream().map(this::toDto).toList(),
                rows.getTotalElements(), rows.getTotalPages(), rows.getNumber(), rows.getSize()
        );
    }

    private AuditLogDtos.AuditLogResponse toDto(AuditLog a) {
        return new AuditLogDtos.AuditLogResponse(
                a.getId(), a.getActorId(), a.getActorUsername(),
                a.getAction(), a.getEntityType(), a.getEntityId(),
                a.getDetails(), a.getCreatedAt()
        );
    }
}
