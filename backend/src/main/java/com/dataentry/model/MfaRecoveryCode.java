package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A one-time MFA recovery code.
 *
 * <p>Only a hash is stored, so a database read does not yield usable codes. Consumption is a
 * single-use operation: {@code usedAt} is stamped the first time the code matches.</p>
 */
@Entity
@Table(name = "mfa_recovery_codes", indexes = {
        @Index(name = "idx_mfa_recovery_user", columnList = "user_id"),
        @Index(name = "idx_mfa_recovery_hash", columnList = "code_hash", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256 of the normalized code — the plaintext exists only in the API response. */
    @Column(name = "code_hash", nullable = false, length = 64, unique = true)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isUsed() {
        return usedAt != null;
    }
}
