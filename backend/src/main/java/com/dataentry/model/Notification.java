package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * In-app notification. Currently only "your ticket was approved" is emitted, but the
 * shape is generic so more event types can plug in without a schema change:
 * {@code type} identifies the event, {@code refType}/{@code refId} link to the entity,
 * and {@code message} is a short human string the frontend can render as-is.
 *
 * <p>Not tenant-scoped via the Hibernate {@code teamFilter} because it's keyed by user
 * (and users already carry a team_id via {@code User.team}). The endpoint that lists
 * notifications only ever returns the caller's own rows, so cross-tenant reads are
 * impossible without a full JPQL query bypass.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "ix_notifications_recipient_unread",
               columnList = "recipient_id, read_at, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** Discriminator for the frontend (e.g. "TICKET_APPROVED"). Kept as a string so a
     *  new type doesn't require an enum migration. */
    @Column(nullable = false, length = 40)
    private String type;

    /** Short pre-rendered human message. The frontend can fall back on this when it
     *  doesn't know how to interpret a new {@code type}. */
    @Column(nullable = false, length = 500)
    private String message;

    /** Optional pointer to the entity this notification is about — {@code refType} = "TICKET"
     *  + {@code refId} = 42 lets the UI deep-link "your ticket #42 was approved" straight
     *  to the ticket view. */
    @Column(name = "ref_type", length = 40)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    /** Optional project id — when set, the notification bell can open the folder view
     *  directly instead of forcing the user to navigate to a generic tickets list. */
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Null until the recipient marks it read (either explicitly or via mark-all-read). */
    @Column(name = "read_at")
    private Instant readAt;
}
