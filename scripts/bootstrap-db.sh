#!/usr/bin/env bash
# Apply Postgres SQL migrations under bootstrap/src/main/resources/sql/postgres/
# in numeric pN_ order (p10 after p9), skipping non-.sql files.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SQL_DIR="${ROOT_DIR}/bootstrap/src/main/resources/sql/postgres"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"

POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-rdbot}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
POSTGRES_USERNAME="${POSTGRES_USERNAME:-${POSTGRES_USER}}"

die() {
  echo "error: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# Resolve connection: POSTGRES_URL (jdbc:postgresql://… or postgresql://…) overrides host/db pieces.
resolve_psql_args() {
  local url="${POSTGRES_URL:-}"
  if [[ -z "${url}" ]]; then
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    PSQL_ARGS=(-h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_USERNAME}" -d "${POSTGRES_DB}")
    return
  fi

  # Strip JDBC prefix if present (matches application.yaml POSTGRES_URL style).
  url="${url#jdbc:}"

  if [[ "${url}" =~ ^(postgres(ql)?://)([^:/@]+):([^@]+)@([^:/]+)(:([0-9]+))?/([^?]+) ]]; then
    POSTGRES_USER="${BASH_REMATCH[3]}"
    POSTGRES_PASSWORD="${BASH_REMATCH[4]}"
    POSTGRES_HOST="${BASH_REMATCH[5]}"
    POSTGRES_PORT="${BASH_REMATCH[7]:-5432}"
    POSTGRES_DB="${BASH_REMATCH[8]}"
    POSTGRES_USERNAME="${POSTGRES_USER}"
  elif [[ "${url}" =~ ^(postgres(ql)?://)([^:/]+)(:([0-9]+))?/([^?]+) ]]; then
    POSTGRES_HOST="${BASH_REMATCH[3]}"
    POSTGRES_PORT="${BASH_REMATCH[5]:-5432}"
    POSTGRES_DB="${BASH_REMATCH[6]}"
  else
    die "unsupported POSTGRES_URL (expected postgresql://[user:pass@]host[:port]/db): ${POSTGRES_URL}"
  fi

  export PGPASSWORD="${POSTGRES_PASSWORD}"
  PSQL_ARGS=(-h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_USERNAME}" -d "${POSTGRES_DB}")
}

wait_for_postgres() {
  local attempts=60
  local i
  echo "Waiting for Postgres at ${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}..."

  if [[ -f "${COMPOSE_FILE}" ]] && command -v docker >/dev/null 2>&1; then
    for ((i = 1; i <= attempts; i++)); do
      if docker compose -f "${COMPOSE_FILE}" exec -T postgres \
        pg_isready -U postgres -d rdbot >/dev/null 2>&1; then
        echo "Postgres is ready (docker compose)."
        return 0
      fi
      sleep 1
    done
  fi

  for ((i = 1; i <= attempts; i++)); do
    if pg_isready -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_USERNAME}" -d "${POSTGRES_DB}" >/dev/null 2>&1; then
      echo "Postgres is ready (pg_isready)."
      return 0
    fi
    if psql "${PSQL_ARGS[@]}" -v ON_ERROR_STOP=1 -c "SELECT 1" >/dev/null 2>&1; then
      echo "Postgres is ready (psql)."
      return 0
    fi
    sleep 1
  done

  die "Postgres did not become ready within ${attempts}s"
}

list_sql_files() {
  # Numeric order by leading pN_, then by filename (so both p8_* apply; p10 after p9).
  # Avoid lexicographic sort of the whole name (p10 before p2).
  local -a files=()
  local f
  shopt -s nullglob
  for f in "${SQL_DIR}"/*.sql; do
    files+=("$(basename "${f}")")
  done
  shopt -u nullglob

  if ((${#files[@]} == 0)); then
    die "no .sql files found in ${SQL_DIR}"
  fi

  printf '%s\n' "${files[@]}" | awk '
    {
      name = $0
      if (match(name, /^p([0-9]+)_/)) {
        n = substr(name, RSTART + 1, RLENGTH - 2) + 0
      } else {
        n = 999999
      }
      printf "%09d\t%s\n", n, name
    }
  ' | sort -t $'\t' -k1,1n -k2,2 | cut -f2-
}

apply_sql() {
  local file="$1"
  local path="${SQL_DIR}/${file}"
  echo "Applying ${file}..."
  psql "${PSQL_ARGS[@]}" -v ON_ERROR_STOP=1 -f "${path}"
}

main() {
  require_cmd psql
  [[ -d "${SQL_DIR}" ]] || die "SQL directory not found: ${SQL_DIR}"

  resolve_psql_args
  wait_for_postgres

  local file
  local count=0
  while IFS= read -r file; do
    [[ -n "${file}" ]] || continue
    apply_sql "${file}"
    count=$((count + 1))
  done < <(list_sql_files)

  echo "Applied ${count} migration(s) to ${POSTGRES_DB} at ${POSTGRES_HOST}:${POSTGRES_PORT}."
}

main "$@"
