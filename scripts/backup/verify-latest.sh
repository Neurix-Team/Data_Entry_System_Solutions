#!/usr/bin/env bash
#
# Weekly backup verification: restore the newest dump into a scratch postgres, count
# rows in the core tables, compare to live, exit non-zero if the diff is too large.
#
# The pg_dump sidecar runs daily; without this check, silent corruption (bad disk, bad
# tar, wrong password) could sit undetected for weeks until an incident asks for a
# restore. This script closes that gap by pretending the incident happened.
#
# Environment:
#   DB_NAME           live database name       (default: dataentry)
#   DB_USERNAME       live/backup role         (default: daleel)
#   DB_PASSWORD       required                 (no default — read from .env or set in cron env)
#   COMPOSE_PROJECT   docker compose project prefix for volume names
#                                              (default: data_entry, matches this repo dir)
#   MAX_DIFF_PERCENT  tolerated row-count diff (default: 5)
#
# Sample host cron (Sunday 05:00 UTC — well after the sidecar's daily dump lands):
#
#   0 5 * * 0  DB_PASSWORD="$(grep '^DB_PASSWORD=' /opt/dems/.env | cut -d= -f2-)" \
#              /opt/dems/scripts/backup/verify-latest.sh >> /var/log/dems-backup-verify.log 2>&1
#
# Alert delivery: pipe the exit code into whatever your ops uses (systemd OnFailure,
# healthchecks.io ping, a Prometheus pushgateway job).

set -eu

: "${DB_NAME:=dataentry}"
: "${DB_USERNAME:=daleel}"
: "${DB_PASSWORD:?DB_PASSWORD is required — pass it via cron env or export before running}"
: "${COMPOSE_PROJECT:=data_entry}"
: "${MAX_DIFF_PERCENT:=5}"

BACKUP_VOLUME="${COMPOSE_PROJECT}_dems-backups"
SCRATCH_NAME="dems-verify-$(date -u +%s)"

log() { printf '[verify %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

cleanup() {
    docker rm -f "$SCRATCH_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 1. Pick the newest dump.
LATEST="$(docker run --rm -v "${BACKUP_VOLUME}:/backups:ro" alpine \
    sh -c 'ls -1 /backups/'"${DB_NAME}"'-*.dump 2>/dev/null | sort | tail -1' || true)"
if [ -z "${LATEST}" ]; then
    log "no backups found in volume ${BACKUP_VOLUME} — verification cannot run"
    exit 2
fi
log "verifying: $(basename "$LATEST")"

# 2. Boot a throwaway postgres attached to the compose network (so we can reach the live
# DB from the same container to compute reference counts).
docker run -d --name "$SCRATCH_NAME" \
    --network "${COMPOSE_PROJECT}_default" \
    -v "${BACKUP_VOLUME}:/dumps:ro" \
    -e POSTGRES_DB="$DB_NAME" \
    -e POSTGRES_USER="$DB_USERNAME" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    postgres:18-alpine >/dev/null

# Wait for readiness (up to 60 s).
for _ in $(seq 1 60); do
    if docker exec "$SCRATCH_NAME" pg_isready -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if ! docker exec "$SCRATCH_NAME" pg_isready -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
    log "scratch postgres never became ready — abandoning verification"
    exit 3
fi

# 3. Restore the newest dump.
if ! docker exec -e PGPASSWORD="$DB_PASSWORD" "$SCRATCH_NAME" \
        pg_restore -U "$DB_USERNAME" -d "$DB_NAME" --no-owner --no-privileges "$LATEST" >/tmp/restore-out 2>&1; then
    log "pg_restore FAILED — dump appears corrupted or incompatible"
    cat /tmp/restore-out
    exit 4
fi
log "restore succeeded"

# 4. Compare row counts across core tables.
CORE_TABLES="users teams tickets ticket_documents dataset_records departments subcategories"
FAIL=0
for tbl in $CORE_TABLES; do
    live=$(docker exec -e PGPASSWORD="$DB_PASSWORD" dems-postgres \
        psql -U "$DB_USERNAME" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "-1")
    restored=$(docker exec -e PGPASSWORD="$DB_PASSWORD" "$SCRATCH_NAME" \
        psql -U "$DB_USERNAME" -d "$DB_NAME" -tAc "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "-1")

    if [ "$live" = "-1" ] || [ "$restored" = "-1" ]; then
        log "table $tbl: could not read counts (live=$live, restored=$restored)"
        FAIL=$((FAIL + 1))
        continue
    fi

    if [ "$live" -eq 0 ] && [ "$restored" -eq 0 ]; then
        log "table $tbl: 0 rows both sides — OK"
        continue
    fi

    # Percent diff = |live - restored| / max(live, 1) * 100
    diff=$(( live > restored ? live - restored : restored - live ))
    denom=$(( live > 0 ? live : 1 ))
    pct=$(( diff * 100 / denom ))

    if [ "$pct" -gt "$MAX_DIFF_PERCENT" ]; then
        log "table $tbl: MISMATCH — live=$live restored=$restored (diff ${pct}% > ${MAX_DIFF_PERCENT}%)"
        FAIL=$((FAIL + 1))
    else
        log "table $tbl: live=$live restored=$restored (diff ${pct}%) — OK"
    fi
done

if [ "$FAIL" -gt 0 ]; then
    log "VERIFICATION FAILED — ${FAIL} table(s) diverged. Do not trust this dump for restore."
    exit 5
fi
log "verification passed — dump is a faithful copy of live"
