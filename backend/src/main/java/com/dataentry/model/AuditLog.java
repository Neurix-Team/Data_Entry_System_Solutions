package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per admin write action.  Records who did what, when, to which entity.  Kept append-only
 * (no updates, no deletes from application code) so tampering shows up in the audit trail itself.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created_at", columnList = "createdAt"),
        @Index(name = "idx_audit_actor", columnList = "actorId"),
        @Index(name = "idx_audit_entity", columnList = "entityType,entityId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    /** CREATE / UPDATE / DELETE / STATUS_CHANGE etc. */
    @Column(nullable = false, length = 50)
    private String action;

    /** DEPARTMENT / SUBCATEGORY / CUSTOM_FIELD / PROJECT / USER / TICKET */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    /** Free-form JSON or plain summary of what changed.  Small — this is not the diff engine. */
    @Column(length = 2000)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
