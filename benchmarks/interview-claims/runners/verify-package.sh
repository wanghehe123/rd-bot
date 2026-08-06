#!/usr/bin/env bash
# Verify interview-claims package layout and that cases.jsonl faultIds exist under faults/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CASES="$ROOT/datasets/v0/cases.jsonl"
METRICS="$ROOT/metrics.json"
MATRIX="docs/superpowers/qa/fault-injection-matrix.md"

fail=0

require() {
  local path="$1"
  if [[ ! -e "$path" ]]; then
    echo "MISSING: $path" >&2
    fail=1
  fi
}

require "$CASES"
require "$METRICS"
require "$ROOT/README.md"
require "$ROOT/runners"
require "$ROOT/raw"
require "$ROOT/reports"

if [[ ! -f "$CASES" ]]; then
  exit 1
fi

while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  # Extract faultIds array contents naively (JSONL without nested arrays of objects).
  ids=$(printf '%s' "$line" | sed -n 's/.*"faultIds"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p')
  if [[ -z "$ids" ]]; then
    continue
  fi
  IFS=',' read -ra parts <<< "$ids"
  for raw in "${parts[@]}"; do
    id=$(printf '%s' "$raw" | tr -d ' "')
    [[ -z "$id" ]] && continue
    if [[ ! -d "$ROOT/faults/$id" ]]; then
      echo "MISSING fault dir for $id (from cases.jsonl)" >&2
      fail=1
    fi
  done
done < "$CASES"

if [[ -f "$MATRIX" ]]; then
  for dir in "$ROOT"/faults/F-*; do
    [[ -d "$dir" ]] || continue
    fid=$(basename "$dir")
    if ! grep -q "$fid" "$MATRIX"; then
      echo "WARN: $fid not mentioned in $MATRIX" >&2
    fi
  done
else
  echo "WARN: fault matrix not found at $MATRIX (run from repo root)" >&2
fi

if [[ "$fail" -ne 0 ]]; then
  echo "verify-package: FAILED" >&2
  exit 1
fi

echo "verify-package: OK ($ROOT)"
