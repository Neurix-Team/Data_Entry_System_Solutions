# On-call runbook

First response guide. Every entry is a concrete command, not prose. If you find
yourself editing this file after an incident, that means it did its job.

Related docs:

- [`SECURITY.md`](./SECURITY.md) — credential rotation, leak response
- [`README.md#deploying-to-a-server`](./README.md) — first deploy setup
- Compose services: `postgres` `backend` `frontend` `libretranslate` `backup`
- Monitoring (once wired): Prometheus at `dems-prometheus:9090`, Grafana at `dems-grafana:3000`

---

## Backend refuses to boot

1. Get the last 200 lines of logs:
   ```bash
   sudo docker compose logs --tail=200 backend
   ```
2. Match the failure signature:

   | Signature | Cause | Fix |
   |---|---|---|
   | `REFUSING TO BOOT — production security check failed` | An `.env` value is still a placeholder or default | `./scripts/setup-env.sh --force` then `docker compose up -d` (see SECURITY.md) |
   | `JWT_SECRET is a known placeholder` | Same as above but caught earlier by JwtService | Same fix |
   | `Schema-validation: missing table [X]` or `missing column [Y]` | Entity added without a Flyway migration | Add a `V<next>__…sql` migration under `backend/src/main/resources/db/migration/` (see README "Database migrations"). Never edit V1. |
   | `password authentication failed for user "daleel"` | `.env` `DB_PASSWORD` no longer matches the Postgres role | `sudo docker compose exec postgres psql -U postgres -c "ALTER ROLE daleel WITH PASSWORD '<pw>';"` then update `.env` and restart |
   | `Connection refused` on port 5432 | Postgres container is down or unhealthy | `docker compose ps` → if `postgres` is unhealthy see next section |
   | `APPLICATION FAILED TO START` with a Flyway checksum mismatch | Someone edited an already-applied migration | Do NOT `flyway repair` blindly. First `git blame` the migration, understand what changed, and either revert the edit or ship a new V<next> that undoes it. |

3. If the fix works, confirm health:
   ```bash
   sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/health | jq .
   # -> {"status":"UP", ...}
   ```

## Postgres unhealthy

```bash
sudo docker compose ps postgres
sudo docker compose logs --tail=100 postgres
sudo docker compose exec postgres pg_isready -U daleel -d dataentry
sudo docker compose exec postgres df -h /var/lib/postgresql
```

- `no space left on device` → jump to "Disk full" below.
- Corrupted WAL → restore from the most recent backup (see "Restore from backup").
- `too many clients already` → someone is holding a psql session hostage:
  ```bash
  sudo docker compose exec postgres psql -U daleel -d dataentry -c \
    "SELECT pid, usename, application_name, state, query_start FROM pg_stat_activity WHERE state = 'idle in transaction';"
  # Kill an offender:
  sudo docker compose exec postgres psql -U daleel -d dataentry -c \
    "SELECT pg_terminate_backend(<pid>);"
  ```

## Restore from backup

`pg_dump` snapshots live in the `dems-backups` docker volume, one file per 24 h,
retention `BACKUP_RETENTION_DAYS` (default 14 days).

```bash
# 1. List available dumps, newest last.
sudo docker run --rm -v data_entry_dems-backups:/backups alpine ls -la /backups

# 2. Stop the backend so it can't write during the restore.
sudo docker compose stop backend

# 3. Drop + recreate the DB. THIS DELETES CURRENT DATA — be sure you want this.
sudo docker compose exec postgres psql -U postgres -c "DROP DATABASE dataentry;"
sudo docker compose exec postgres psql -U postgres -c "CREATE DATABASE dataentry OWNER daleel;"

# 4. Restore the chosen dump.
DUMP=dataentry-YYYYMMDDTHHMMSSZ.dump  # replace with the file from step 1
sudo docker run --rm --network data_entry_default \
  -v data_entry_dems-backups:/backups:ro \
  -e PGPASSWORD="$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)" \
  postgres:18-alpine \
  pg_restore -h postgres -U daleel -d dataentry --no-owner --no-privileges "/backups/$DUMP"

# 5. Bring the backend back up.
sudo docker compose start backend
sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/health | jq .status
```

## Wiring alert delivery (Slack / email / webhook)

Alertmanager ships silent — alerts fire and appear in the UI but nothing is sent until
you edit `monitoring/alertmanager/alertmanager.yml` and add a delivery block. Then
`docker compose up -d alertmanager` (or `curl -X POST http://<host>:9093/-/reload`).

**Slack incoming webhook:**
```yaml
receivers:
  - name: default
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/T00/B00/xxxx'
        channel: '#dems-alerts'
        send_resolved: true
```

**Generic webhook (Discord, Telegram bot, custom shim):**
```yaml
receivers:
  - name: default
    webhook_configs:
      - url: 'https://hooks.example.com/dems'
        send_resolved: true
```

**Email (SMTP):** set `global.smtp_*` at the top of the file, then use `email_configs`
in the receiver.

Keep the `default` and `pages` receiver names — the `severity=page` route in the same
file points at `pages`, so make its delivery louder (a paging tool, not just Slack).

## Verify backups are actually restorable (weekly)

`scripts/backup/verify-latest.sh` restores the newest dump into a scratch postgres, then
compares row counts across the core tables. Wire to weekly cron:

```cron
0 5 * * 0  DB_PASSWORD="$(grep '^DB_PASSWORD=' /opt/dems/.env | cut -d= -f2-)" \
           /opt/dems/scripts/backup/verify-latest.sh >> /var/log/dems-backup-verify.log 2>&1
```

Non-zero exit means the dump is bad or drifted from live by more than 5% of rows on any
core table. Investigate the last few sidecar log lines (`docker compose logs backup`) and
try a restore drill manually before the next scheduled backup overwrites the working one.

## High CPU or memory

```bash
# 1. Which container?
sudo docker stats --no-stream

# 2. If it's `dems-backend`, grab a thread dump via actuator.
sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/threaddump | jq . > /tmp/threaddump.json

# 3. Grab a heap breakdown (much cheaper than a full heap dump).
sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/metrics/jvm.memory.used
sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/metrics/hikaricp.connections.active
```

If a live heap dump is needed:

```bash
sudo docker compose exec backend jcmd 1 GC.heap_dump /app/data/heap.hprof
sudo docker cp dems-backend:/app/data/heap.hprof ./heap.hprof
# Analyse offline with Eclipse MAT / jhat — do NOT leave heap dumps on the container.
```

## Disk full

```bash
sudo docker system df                                   # top-level offender
sudo du -sh /var/lib/docker/volumes/*                   # biggest volume
sudo docker compose exec postgres du -sh /var/lib/postgresql   # DB size
sudo docker exec dems-backend du -sh /app/data          # attachments size
```

Common cleanups (least destructive first):

```bash
sudo docker image prune -f                              # dangling images
sudo docker builder prune -f                            # build cache
# Trim backups more aggressively than the default 14 days:
BACKUP_RETENTION_DAYS=7 sudo docker compose up -d backup

# NEVER: docker volume prune — it deletes dems-postgres-data + dems-data and takes prod with it.
```

## Rolled a bad deploy

```bash
# 1. What was the previous good SHA?
git log --oneline -20

# 2. Revert (creates a new commit — does not rewrite history).
git revert <bad-sha>

# 3. Redeploy.
sudo docker compose up -d --build
sudo docker compose logs --tail=100 -f backend
```

If the bad deploy shipped a Flyway migration that mutated data, `git revert` on the
code alone does NOT undo the SQL. Write a compensating `V<next>__revert_X.sql` and
apply it as part of the redeploy.

## User locked out (login rate limit tripped)

Rate limit lives in the `login_attempts` table. Purge the offender's key:

```bash
# username OR ip:username — the limiter stores both.
sudo docker compose exec postgres psql -U daleel -d dataentry -c \
  "DELETE FROM login_attempts WHERE attempt_key LIKE '%<username>%';"
```

Or wait 5 minutes — the window rolls off automatically.

## Rotate the admin password of a live user

See [`SECURITY.md#rotating-an-existing-admins-password`](./SECURITY.md).

## Backend up but 5xx on every request

```bash
# 1. Confirm actuator health per subsystem.
sudo docker compose exec backend curl -fsS http://localhost:9090/actuator/health | jq .
# db, diskSpace, ping, livenessState, readinessState should all be UP.

# 2. If db is DOWN, back to "Postgres unhealthy" above.

# 3. If everything is UP but requests still 5xx, tail request logs.
sudo docker compose logs --tail=200 backend | grep -E ' 5[0-9]{2} '
```

## Frontend blank / 502

```bash
sudo docker compose ps frontend
sudo docker compose logs --tail=50 frontend
# nginx serves a static bundle — check it exists inside the container:
sudo docker compose exec frontend ls -la /usr/share/nginx/html
# Empty means the build produced no artifacts. Rebuild:
sudo docker compose build --no-cache frontend && sudo docker compose up -d frontend
```

## Emergency stop

```bash
# Take the whole stack down but keep data.
sudo docker compose down

# Data survives — dems-postgres-data, dems-data, dems-backups are named volumes.
# Bring back up with `docker compose up -d`.
```

DO NOT run `docker compose down -v` in production — the `-v` deletes named volumes
including the DB.
