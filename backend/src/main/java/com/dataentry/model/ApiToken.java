package com.dataentry.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Personal-access token issued from the super-admin surface, used by external systems
 * (typically another Neurix project doing AI/data ingestion) to pull data via
 * {@code /api/v1/export/*}. Read-only — tokens can only pull; never write.
 *
 * <p>Only the SHA-256 hash of the secret is stored — the plaintext is shown once at
 * creation time and never again. The {@code prefix} (first 8 chars of the plaintext) is
 * kept so the UI can help operators recognise which token is which without exposing the
 * full value.
 */
@Entity
@Table(name = "api_tokens", indexes = {
        @Index(name = "idx_api_tokens_hash", columnList = "token_hash", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable label so the operator can tell tokens apart on the list page. */
    @Column(nullable = false, length = 150)
    private String name;

    /** SHA-256 hash of the plaintext secret. Never stored in plaintext. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** First 8 chars of the plaintext, shown in listings so tokens are recognisable. */
    @Column(name = "prefix", nullable = false, length = 12)
    private String prefix;

    /** Super-admin who created this token. FK to users.id — nullable if creator was deleted. */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Null means never expires. Enforced at auth time in ApiTokenAuthFilter. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Set when the token is revoked. Once set the token can no longer authenticate. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Updated on every successful authentication. Best-effort — a lost update is fine. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** True if this token is still usable right now. */
    public boolean isUsable(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && !now.isBefore(expiresAt)) return false;
        return true;
    }
}
