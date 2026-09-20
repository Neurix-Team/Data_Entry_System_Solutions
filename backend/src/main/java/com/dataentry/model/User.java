package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;

@Entity
@Table(name = "users")
@Filter(name = "teamFilter", condition = "team_id = :teamId")
@EntityListeners(TenantEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements TeamOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(length = 150)
    private String displayName;

    @Column(name = "display_name_en", length = 150)
    private String displayNameEn;

    @Column(name = "display_name_ar", length = 150)
    private String displayNameAr;

    @Column(length = 200)
    private String email;

    @Column(length = 40)
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "avatar_updated_at")
    private Instant avatarUpdatedAt;

    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0L;

            /**
     * TOTP MFA (RFC 6238). The shared secret lives AES-GCM encrypted at rest and never leaves the
     * backend. While enrollment is in progress the secret is held in {@code mfaSecretEncrypted}
     * with {@code mfaEnabled == false}, so a botched enrollment can never lock the account.
     */
    @Column(name = "mfa_enabled", nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret_enc")
    private String mfaSecretEncrypted;

    /**
     * Last accepted TOTP timestep — prevents code replay within the validity window.
     * Null until the first code is ever accepted.
     */
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    @Column(name = "mfa_enabled_at")
    private Instant mfaEnabledAt;

    /** Failed MFA attempts within the current lockout cycle. */
    @Column(name = "mfa_failed_attempts", nullable = false)
    @Builder.Default
    private int mfaFailedAttempts = 0;

    /** Account is MFA-locked until this instant (null = not locked). */
    @Column(name = "mfa_locked_until")
    private Instant mfaLockedUntil;

    /** True when the account is currently MFA-locked and the second factor must not be challenged. */
    public boolean isMfaLocked() {
        return mfaLockedUntil != null && mfaLockedUntil.isAfter(Instant.now());
    }

    public boolean isAdminLike() {
        return role == Role.ADMIN || role == Role.SUPER_ADMIN;
    }
}
