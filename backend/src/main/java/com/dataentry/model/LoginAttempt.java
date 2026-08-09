package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per rate-limited login attempt. Older rows are pruned lazily inside
 * {@code DatabaseLoginRateLimiter}, so the table stays bounded even under sustained abuse.
 */
@Entity
@Table(name = "login_attempts", indexes = {
        @Index(name = "idx_login_attempts_key_time", columnList = "attemptKey,attemptedAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IP + ":" + lowercased username — matches AuthController#clientKey. */
    @Column(name = "attempt_key", nullable = false, length = 200)
    private String attemptKey;

    @Column(name = "attempted_at", nullable = false)
    @Builder.Default
    private Instant attemptedAt = Instant.now();
}
