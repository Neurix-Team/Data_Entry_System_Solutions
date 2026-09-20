-- V7: TOTP multi-factor authentication for user accounts.
-- The shared secret is stored encrypted at rest (AES-GCM, key derived from JWT_SECRET)
-- and never leaves the backend. mfa_secret_enc doubles as the in-progress enrollment
-- secret while mfa_enabled is still false, so a failed/botched enrollment can never
-- lock an account out of its own second factor. mfa_last_step blocks TOTP replay by
-- remembering the highest timestep already accepted.
ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN mfa_secret_enc TEXT;
ALTER TABLE users ADD COLUMN mfa_last_step BIGINT;
