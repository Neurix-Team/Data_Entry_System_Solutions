package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One browser the user opted in to push notifications from. Deliberately not
 * team-scoped: a subscription belongs to a person, and only that person (or
 * WebPushService acting on a notification addressed to them) ever touches it.
 */
@Entity
@Table(name = "push_subscriptions",
        uniqueConstraints = @UniqueConstraint(name = "uq_push_subscriptions_endpoint",
                columnNames = "endpoint"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Push-service endpoint — an unguessable per-browser URL, unique service-wide. */
    @Column(nullable = false, length = 2048)
    private String endpoint;

    /** Base64url P-256 client public key (the p256dh value of the browser subscription). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String p256dh;

    /** Base64url 16-byte auth secret. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String auth;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Last time the push service accepted a delivery — liveness signal for ops. */
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
}