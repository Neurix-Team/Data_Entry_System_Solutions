#!/usr/bin/env bash

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

if [[ ! -f "$ENV_FILE" ]]; then
  echo "error: $ENV_FILE not found. Run scripts/setup-env.sh first." >&2
  exit 1
fi
set -a; . "$ENV_FILE"; set +a

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

if ! docker ps --format '{{.Names}}' | grep -qx dems-postgres; then
  echo "==> dems-postgres container is not running; starting it..."
  (cd "$REPO_ROOT" && docker compose up -d postgres)
fi

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

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$WORK_DIR/dataentry-${TIMESTAMP}.dump"
echo
echo "==> Dumping source to $DUMP_FILE ..."
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

echo
echo "==> Restoring into dems-postgres ..."
export MSYS_NO_PATHCONV=1
docker exec -i -e PGPASSWORD="$SRC_PW" dems-postgres \
  pg_restore --clean --if-exists --no-owner --no-privileges \
             -U "$SRC_USER" -d "$SRC_DB" \
  < "$DUMP_FILE"
echo "    Restore finished."

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
