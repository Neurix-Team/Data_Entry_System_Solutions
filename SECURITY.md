# Security & credential rotation

This project is deployed with `SPRING_PROFILES_ACTIVE=docker` and boots behind a Neurix
auto-SSL reverse proxy. Under that profile the backend runs a
`ProductionSecurityCheck` at startup: if any of the built-in default credentials or
placeholder secrets is still present, boot fails with a clear error listing every
offender. Local `mvn spring-boot:run` (no `docker` profile) keeps the check off so
development is not blocked; the seeder still emits a `⚠ SECURITY` warning as a nudge.

## Which secrets live where

| Secret | Where it is read from | What it protects | Rotation impact |
|---|---|---|---|
| `JWT_SECRET` | `.env` → env var → `app.jwt.secret` | Every authenticated request. Anyone with this can forge a token as any user. | Rotating logs every active session out (all tokens become unverifiable). Restart the backend right after changing. |
| `DB_PASSWORD` | `.env` → env var → `spring.datasource.password` | Read/write access to the whole DB. | Must match the Postgres role's password on the host. See below for the two-step rotation. |
| `APP_SEED_ADMIN_PASSWORD` / `APP_SEED_SUPERADMIN_PASSWORD` | `.env` → env var → `DataSeeder` | Used **only** when the users table is empty on first boot. If the admin exists already, its current password is untouched by rotating the env var. | Zero on an existing install. To change a live admin's password, sign in and use the change-password flow, or run the SQL below. |
| API tokens (`api_tokens`) | Database table, one row per token | The `/api/v1/export/*` endpoints via `ApiTokenAuthFilter`. | Rotate through the super-admin token UI; revoke immediately by deleting the row. Only the token hash is stored — the plaintext is shown once at issue. |
| PostgreSQL WAL / attachments | On-disk volumes (`dems-postgres-data`, `dems-data`) | Business data + uploaded files. | Not a credential per se; see backups below. |
| Backup dumps | Named volume `dems-backups`, one pg_dump per 24 h, `BACKUP_RETENTION_DAYS` old files pruned | Point-in-recent-past restore. | Same host as the DB — protects against `DROP TABLE`, corruption, application bugs. Off-host DR is wired via `scripts/backup/mirror-to-remote.sh` (see "Off-host backup" below). |

## Rotating `JWT_SECRET`

Anyone who reads `JWT_SECRET` can mint tokens as any user, so treat it like a root key.
The check refuses any secret shorter than 32 characters and rejects two known placeholder
values that appeared in the historical committed `.env` (commit `b5a95f2`, later removed
in `c03c6f7`) and in `application.yml`.

```bash
# On the server, next to docker-compose.yml:
./scripts/setup-env.sh --force        # regenerates JWT_SECRET, admin, superadmin
sudo docker compose up -d --build     # restarts the backend with the new secret
```

Every active session is logged out because their tokens no longer verify. The frontend
detects the 401 and sends users to the login page.

If a previous deployment ever ran with one of the historical placeholder values, the
tokens issued during that window remain forgeable by anyone with the git history. The
mitigation is the same: rotate now, and treat any activity before rotation as
attacker-influenced.

## Rotating `DB_PASSWORD`

The role's password lives on the host Postgres, not in the app. Two steps:

```bash
# 1. Change the role password on the host.
sudo -u postgres psql -c "ALTER ROLE daleel WITH PASSWORD '<new-password>';"

# 2. Update .env to match and restart. --force re-runs setup-env.sh's prompt.
DB_PASSWORD='<new-password>' ./scripts/setup-env.sh --force
sudo docker compose up -d
```

If step 1 fails or the two values do not match, the backend logs `password
authentication failed for user "daleel"` at boot. Fix the mismatch and restart — no data
is lost.

## Rotating an existing admin's password

The seed variables only take effect on an empty users table. For a live admin/super-admin
who wants a new password:

- **Preferred** — sign in and use the "change password" action in the top-right menu.
- **Emergency** — run one SQL update with a pre-hashed BCrypt password. Generate the hash
  locally so plaintext never travels through the terminal history:

```bash
# Generate a BCrypt hash of the new password (Node one-liner using bcryptjs):
node -e "console.log(require('bcryptjs').hashSync(process.argv[1], 12))" 'the-new-password'

# Apply it (replace $HASH with the output above):
sudo docker compose exec postgres \
  psql -U daleel -d dataentry -c \
  "UPDATE users SET password_hash = '\$HASH' WHERE username = 'admin';"
```

## Rotating an API token

Tokens are shown in plaintext only at creation. To rotate:

1. Sign in as super-admin and issue a new token in the token UI.
2. Update the consuming service with the new token.
3. Delete (or "revoke") the old token from the same UI — this sets `revoked_at`, and
   `ApiTokenAuthFilter` treats any revoked token as invalid on the next request.

## What is _not_ a credential

- `APP_SEED_ADMIN_USERNAME` / `APP_SEED_SUPERADMIN_USERNAME` — display value only, not
  secret. Change freely.
- `APP_CORS_ALLOWED_ORIGINS` — public info by design (it's the site's own URL). Getting
  it wrong hurts availability, not confidentiality.
- The BCrypt hashes in the database — these are one-way and cannot be reversed to the
  original password in practical time.

## Off-host backup

Local pg_dumps in `dems-backups` don't survive host loss. `scripts/backup/mirror-to-remote.sh`
pushes them to any rclone destination (S3, GCS, Backblaze, another SSH host, …).

One-time setup on the host:

```bash
sudo apt install rclone
rclone config                  # walk through creating a remote (name it, e.g., `s3`)
rclone lsd s3:                 # sanity-check
```

Wire to cron so it runs shortly after the backup sidecar's daily tick:

```cron
# Sidecar dumps ~03:30 UTC (24h from first compose up). Mirror at 04:30 UTC.
30 4 * * *  BACKUP_REMOTE=s3:acme-backups/dems /opt/dems/scripts/backup/mirror-to-remote.sh >> /var/log/dems-backup-mirror.log 2>&1
```

Set `REMOTE_RETENTION_DAYS` at least as high as the local `BACKUP_RETENTION_DAYS`
(default 14) — the mirror can't restore what was already pruned locally. 30 days is
the script default.

## Reporting a suspected leak

If you believe a secret has leaked (a laptop was lost, an env dump was posted, git
history was pushed by mistake):

1. Rotate the affected secret immediately using the steps above.
2. If it was `JWT_SECRET`, force every session out (rotation already does this).
3. Audit `audit_logs` for the window between suspected leak and rotation. Every
   privileged action writes a row.
4. If DB access was possible, snapshot the current DB before any cleanup so the incident
   can be reconstructed later.
