#!/usr/bin/env bash
# Idempotent schema migration runner for the RD-Bot Docker stack.
# Runs inside the postgres image (psql available). Applies bootstrap/src/main/resources/sql/postgres
# pN_*.sql files in numeric order, recording (name, sha256) in rd_schema_migrations.
# - already applied with the SAME checksum  -> skipped
# - already applied with a DIFFERENT checksum -> hard failure (manual resolution required)
# - each migration file AND its ledger insert commit in the SAME transaction.
set -euo pipefail

MIGRATIONS_DIR="${MIGRATIONS_DIR:-/migrations}"

psql_exec() {
  psql -v ON_ERROR_STOP=1 -q "$@"
}

psql_exec -c "CREATE TABLE IF NOT EXISTS rd_schema_migrations (
  name text PRIMARY KEY,
  sha256 text NOT NULL,
  applied_at timestamptz NOT NULL DEFAULT now()
)"

shopt -s nullglob
files=("$MIGRATIONS_DIR"/p*.sql)
if [ ${#files[@]} -eq 0 ]; then
  echo "no migration files found in $MIGRATIONS_DIR" >&2
  exit 1
fi

# Numeric-aware order: p2_ < p8_ < p8_zz_ < p9_ < p13_ ...
mapfile -t sorted < <(printf '%s\n' "${files[@]}" | sort -t/ -k1 -V 2>/dev/null || printf '%s\n' "${files[@]}" | sort -V)
applied=0
skipped=0
for file in "${sorted[@]}"; do
  name="$(basename "$file")"
  checksum="$(sha256sum "$file" | cut -d' ' -f1)"
  recorded="$(psql_exec -tA -c "SELECT sha256 FROM rd_schema_migrations WHERE name = '$name'")"
  if [ -n "$recorded" ]; then
    if [ "$recorded" = "$checksum" ]; then
      skipped=$((skipped + 1))
      continue
    fi
    echo "MIGRATION DRIFT: $name was applied with checksum $recorded but now hashes to $checksum." >&2
    echo "Manual resolution required: inspect rd_schema_migrations and the database schema." >&2
    exit 1
  fi
  {
    echo "BEGIN;"
    cat "$file"
    echo "INSERT INTO rd_schema_migrations (name, sha256) VALUES ('$name', '$checksum');"
    echo "COMMIT;"
  } | psql_exec -v ON_ERROR_STOP=1 --single-transaction
  applied=$((applied + 1))
  echo "applied: $name"
done
echo "migrations complete: applied=$applied skipped=$skipped"
