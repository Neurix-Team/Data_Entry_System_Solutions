#!/bin/sh
set -eu

: "${BACKUP_DB_HOST:=postgres}"
: "${BACKUP_DB_PORT:=5432}"
: "${BACKUP_DB_NAME:=dataentry}"
: "${BACKUP_DB_USER:=daleel}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_INTERVAL_SECONDS:=86400}"
: "${BACKUP_RETENTION_DAYS:=14}"

if [ -z "${BACKUP_DB_PASSWORD:-}" ]; then
    echo "[backup] BACKUP_DB_PASSWORD is empty — refusing to start." >&2
    exit 1
fi
export PGPASSWORD="$BACKUP_DB_PASSWORD"

mkdir -p "$BACKUP_DIR"

log() { printf '[backup %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

take_one() {
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    tmp="$BACKUP_DIR/.inflight-$stamp.dump"
    final="$BACKUP_DIR/${BACKUP_DB_NAME}-${stamp}.dump"

    log "starting pg_dump → ${final##*/}"
    if pg_dump \
            --host="$BACKUP_DB_HOST" \
            --port="$BACKUP_DB_PORT" \
            --username="$BACKUP_DB_USER" \
            --dbname="$BACKUP_DB_NAME" \
            --format=custom \
            --no-owner \
            --no-privileges \
            --file="$tmp"; then
        mv "$tmp" "$final"
        size=$(du -h "$final" | awk '{print $1}')
        log "wrote ${final##*/} ($size)"
    else
        rc=$?
        log "pg_dump failed with exit $rc — leaving previous backups untouched"
        rm -f "$tmp"
        return "$rc"
    fi
}

prune_old() {
    deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${BACKUP_DB_NAME}-*.dump" \
        -mtime "+${BACKUP_RETENTION_DAYS}" -print -delete 2>/dev/null | wc -l | tr -d ' ')
    find "$BACKUP_DIR" -maxdepth 1 -type f -name '.inflight-*' -mmin +60 -delete 2>/dev/null || true
    if [ "$deleted" -gt 0 ]; then
        log "pruned $deleted backup(s) older than ${BACKUP_RETENTION_DAYS} day(s)"
    fi
}

log "sidecar up. interval=${BACKUP_INTERVAL_SECONDS}s retention=${BACKUP_RETENTION_DAYS}d target=${BACKUP_DIR}"

while :; do
    if ! pg_isready --host="$BACKUP_DB_HOST" --port="$BACKUP_DB_PORT" --username="$BACKUP_DB_USER" --dbname="$BACKUP_DB_NAME" >/dev/null 2>&1; then
        log "waiting for postgres at ${BACKUP_DB_HOST}:${BACKUP_DB_PORT} …"
        while ! pg_isready --host="$BACKUP_DB_HOST" --port="$BACKUP_DB_PORT" --username="$BACKUP_DB_USER" --dbname="$BACKUP_DB_NAME" >/dev/null 2>&1; do
            sleep 5
        done
        log "postgres is ready"
    fi

    take_one || true
    prune_old || true

    log "sleeping ${BACKUP_INTERVAL_SECONDS}s until next run"
    sleep "$BACKUP_INTERVAL_SECONDS"
done
