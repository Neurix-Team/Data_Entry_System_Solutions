-- V8: MFA follow-ups.
-- mfa_enabled_at records when the second factor went live (surfaced to the operator console
-- and handy in the audit trail). mfa_failed_attempts / mfa_locked_until implement a durable
-- per-account attempt budget so a process restart can't clear a lockout. mfa_recovery_codes
-- stores single-use recovery codes as SHA-256 hashes — the plaintext is shown to the user
-- exactly once, at generation time, and is never recoverable from the database.
ALTER TABLE users ADD COLUMN mfa_enabled_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN mfa_failed_attempts SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN mfa_locked_until TIMESTAMPTZ;

CREATE TABLE mfa_recovery_codes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    used_at    TIMESTAMPTZ,
    CONSTRAINT uq_mfa_recovery_code_hash UNIQUE (code_hash)
);

CREATE INDEX idx_mfa_recovery_user ON mfa_recovery_codes (user_id);
