#!/usr/bin/env bash

set -eu

: "${BACKUP_REMOTE:?BACKUP_REMOTE is required — set it to an rclone destination like s3:my-bucket/dems}"
: "${BACKUP_VOLUME:=data_entry_dems-backups}"
: "${REMOTE_RETENTION_DAYS:=30}"
: "${RCLONE_FLAGS:=}"

log() { printf '[mirror %s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

if ! command -v docker >/dev/null 2>&1; then
    log "docker not installed" >&2; exit 1
fi
if ! command -v rclone >/dev/null 2>&1; then
    log "rclone not installed (apt install rclone)" >&2; exit 1
fi
if ! rclone lsd "$BACKUP_REMOTE" >/dev/null 2>&1; then
    log "cannot reach rclone remote $BACKUP_REMOTE — check `rclone config`" >&2
    exit 1
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

log "extracting $BACKUP_VOLUME → $TMPDIR"
docker run --rm -v "$BACKUP_VOLUME":/src:ro -v "$TMPDIR":/dst alpine \
    sh -c 'cp -a /src/. /dst/'

log "pushing to $BACKUP_REMOTE"
rclone sync "$TMPDIR" "$BACKUP_REMOTE" \
    --update \
    --checksum \
    --stats=0 \
    $RCLONE_FLAGS

log "pruning remote objects older than ${REMOTE_RETENTION_DAYS}d"
rclone delete --min-age "${REMOTE_RETENTION_DAYS}d" "$BACKUP_REMOTE" $RCLONE_FLAGS

log "done"
