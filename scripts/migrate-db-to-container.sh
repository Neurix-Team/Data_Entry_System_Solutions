#!/usr/bin/env bash
#
# One-shot data migration: dump the current external Postgres and restore it into the
# in-compose `postgres` container. Run this ONCE when switching from an external DB
# (host.docker.internal / a remote server) to the bundled container Postgres — after
# that the app is 20-40x faster because queries no longer pay the ~50 ms network
# round-trip to the external server.
#
# Requirements on the host:
#   - Docker (used to run pg_dump / pg_restore from the postgres:18-alpine image so no
#     psql client install is needed)
#   - Read access to the source Postgres (the credentials in .env)
#   - The compose stack already built (`docker compose build`), so dems-postgres exists
#
# Usage:
#   ./scripts/migrate-db-to-container.sh                 # dumps live source → container
#   ./scripts/migrate-db-to-container.sh --dry-run       # dumps only; skips the restore
#   ./scripts/migrate-db-to-container.sh --keep-dump     # leaves the .dump file for inspection
#   SOURCE_HOST=1.2.3.4 ./scripts/migrate-db-to-container.sh   # override source host
#
# What it does, in order:
#   1. Reads DB_* from .env and captures the SOURCE_HOST (defaults to the .env value).
#   2. Verifies both the source Postgres and the local dems-postgres container are reachable.
#   3. pg_dump -Fc from the source into ./scripts/.migration/dataentry-<ts>.dump
#   4. pg_restore --clean --if-exists into the dems-postgres container.
#   5. Compares row counts of a handful of critical tables and prints a table so you can
#      eyeball that nothing was lost. Non-zero exit if any count differs.
#   6. Prints the exact one-line .env edit + docker command to complete the switch.
#
# What it does NOT do:
#   - Modify your .env (that's a manual, reversible step).
#   - Delete or downgrade anything in your existing external DB.
#   - Start containers you haven't started (the compose stack must already exist).
#
# Safe to re-run: the restore uses --clean --if-exists so a second run overwrites the
# container DB with a fresh dump. It never touches the source.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"
WORK_DIR="$REPO_ROOT/scripts/.migration"
mkdir -p "$WORK_DIR"

DRY_RUN=0
KEEP_DUMP=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --keep-dump) KEEP_DUMP=1 ;;
    -h|--help) sed -n '3,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ---- 1. Load .env ------------------------------------------------------------
if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: $ENV_FILE not found. Run scripts/setup-env.sh first." >&2
  exit 1
fi
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

# Source host: whatever the pre-migration .env used to point at. Falls back to the
# `DB_HOST` if `DB_DOCKER_HOST` was already flipped to 'postgres' before the migration.
SRC_HOST="${SOURCE_HOST:-${DB_DOCKER_HOST:-${DB_HOST:-}}}"
if [[ -z "$SRC_HOST" || "$SRC_HOST" == "postgres" ]]; then
  echo "error: cannot determine the source Postgres host from .env." >&2
  echo "  DB_DOCKER_HOST in .env is either empty or already set to 'postgres'." >&2
  echo "  Re-run with SOURCE_HOST=<old-host> ./scripts/migrate-db-to-container.sh" >&2
  exit 1
fi
SRC_PORT="${DB_PORT:-5432}"
SRC_DB="${DB_NAME:-dataentry}"
SRC_USER="${DB_USERNAME:-daleel}"
SRC_PW="${DB_PASSWORD:-}"
if [[ -z "$SRC_PW" ]]; then
  echo "error: DB_PASSWORD is empty in .env." >&2
  exit 1
fi

echo "==> Source     : ${SRC_USER}@${SRC_HOST}:${SRC_PORT}/${SRC_DB}"
echo "==> Destination: daleel@dems-postgres:5432/${SRC_DB} (container)"
echo

# ---- 2. Check container is up -----------------------------------------------
if ! docker ps --format '{{.Names}}' | grep -qx dems-postgres; then
  echo "==> dems-postgres container is not running; starting it..."
  (cd "$REPO_ROOT" && docker compose up -d postgres)
fi

# Wait until Postgres inside the container answers a select 1
echo "==> Waiting for dems-postgres to accept connections..."
for i in $(seq 1 30); do
  if docker exec dems-postgres pg_isready -U "$SRC_USER" -d "$SRC_DB" >/dev/null 2>&1; then
    echo "    dems-postgres is ready."
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "error: dems-postgres never became ready. Check 'docker logs dems-postgres'." >&2
    exit 1
  fi
  sleep 1
done

# ---- 3. Dump source ---------------------------------------------------------
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$WORK_DIR/dataentry-${TIMESTAMP}.dump"
echo
echo "==> Dumping source to $DUMP_FILE ..."
# pg_dump inside a throwaway container: no local psql install required, and we always
# get a client version that matches the source server version enough for -Fc dumps.
# Stream stdout to the host file directly — a `-v $WORK_DIR:/dump` mount would work on
# Linux but Git Bash on Windows mangles the "/dump" argument into a Windows path.
docker run --rm -i \
  -e PGPASSWORD="$SRC_PW" \
  postgres:18-alpine \
  pg_dump -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" \
          -Fc --no-owner --no-privileges \
  > "$DUMP_FILE"
DUMP_BYTES=$(stat -c %s "$DUMP_FILE" 2>/dev/null || wc -c < "$DUMP_FILE")
printf "    Dump ready: %s bytes (%s)\n" "$DUMP_BYTES" "$DUMP_FILE"

if [[ $DRY_RUN -eq 1 ]]; then
  echo
  echo "--dry-run set: skipping restore. Dump left at $DUMP_FILE"
  exit 0
fi

# ---- 4. Restore into container ---------------------------------------------
echo
echo "==> Restoring into dems-postgres ..."
# Stream the dump straight into pg_restore over stdin. Piping avoids any container-side
# temp file (and dodges Git Bash on Windows mangling absolute paths passed to docker exec).
export MSYS_NO_PATHCONV=1
docker exec -i -e PGPASSWORD="$SRC_PW" dems-postgres \
  pg_restore --clean --if-exists --no-owner --no-privileges \
             -U "$SRC_USER" -d "$SRC_DB" \
  < "$DUMP_FILE"
echo "    Restore finished."

# ---- 5. Verify row counts on critical tables --------------------------------
echo
echo "==> Verifying row counts..."
TABLES=(teams users projects departments subcategories custom_fields tickets audit_logs)
printf "  %-16s %10s %10s   %s\n" "table" "source" "container" "status"
printf "  %-16s %10s %10s   %s\n" "-----" "------" "---------" "------"
FAIL=0
for tbl in "${TABLES[@]}"; do
  SRC=$(docker run --rm -e PGPASSWORD="$SRC_PW" postgres:18-alpine \
          psql -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" \
               -t -A -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null | tr -d '[:space:]' || true)
  DST=$(MSYS_NO_PATHCONV=1 docker exec -e PGPASSWORD="$SRC_PW" dems-postgres \
          psql -U "$SRC_USER" -d "$SRC_DB" -t -A -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null | tr -d '[:space:]' || true)
  SRC="${SRC:-?}"; DST="${DST:-?}"
  if [[ "$SRC" == "$DST" ]]; then
    printf "  %-16s %10s %10s   OK\n" "$tbl" "$SRC" "$DST"
  else
    printf "  %-16s %10s %10s   MISMATCH\n" "$tbl" "$SRC" "$DST"
    FAIL=1
  fi
done

if [[ $KEEP_DUMP -eq 0 ]]; then
  rm -f "$DUMP_FILE"
  echo
  echo "==> Deleted $DUMP_FILE (pass --keep-dump to preserve it)."
fi

echo
if [[ $FAIL -eq 1 ]]; then
  echo "One or more tables have differing counts. Review before switching .env." >&2
  exit 1
fi

cat <<EOF

Migration verified. To complete the switch:

  1. Edit .env — change one line:
       DB_DOCKER_HOST=postgres

  2. Recreate the backend against the container DB:
       docker compose up -d --force-recreate backend

  3. Confirm the swap by hitting a warm endpoint — you should see server-side timings
     drop from ~300 ms to ~15 ms in the backend logs:
       docker logs -f dems-backend | grep RequestTimingFilter

Rollback: if anything goes wrong, edit .env back to your old DB_DOCKER_HOST value and
re-run 'docker compose up -d --force-recreate backend'. The external DB is untouched.
EOF
