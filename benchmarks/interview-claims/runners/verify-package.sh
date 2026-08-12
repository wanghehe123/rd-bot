#!/usr/bin/env bash
# Verify interview-claims package layout and that cases.jsonl faultIds exist under faults/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
CASES="$ROOT/datasets/v0/cases.jsonl"
METRICS="$ROOT/metrics.json"
MATRIX="docs/superpowers/qa/fault-injection-matrix.md"
RESUME="$REPO_ROOT/resume_optimized.md"

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
require "$RESUME"

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

# Resume claims must describe the implemented Host gate, not promise an
# unconditional provider fallback that has no matching raw run or safety proof.
python3 - "$RESUME" <<'PY'
import re
import sys
from pathlib import Path

resume = Path(sys.argv[1])
text = resume.read_text(encoding="utf-8")
unsupported = [
    re.compile(r"支持[^\n。；;]*自动(?:地)?(?:降级|切换|回退)", re.IGNORECASE),
    re.compile(r"\b(?:automatic|unconditional|always|silent)\s+(?:provider\s+)?fallback\b", re.IGNORECASE),
    re.compile(r"\bprovider\s+(?:automatically|unconditionally|always|silently)\s+(?:falls?\s+back|switch(?:es|ing)?)\b", re.IGNORECASE),
]
matches = [pattern.pattern for pattern in unsupported if pattern.search(text)]
if matches:
    raise SystemExit(
        "unsupported automatic Provider fallback wording in resume_optimized.md; "
        "describe Host-gated switching and human handling for high-risk/uncertain cases"
    )
PY

# Every non-layout run must be replayable from raw JSON and have a matching report.
python3 - "$ROOT" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
raw_root = root / "raw"
report_root = root / "reports"
runs = []
verified_runs = []
for metadata in sorted(raw_root.glob("*/metadata.json")):
    data = json.loads(metadata.read_text(encoding="utf-8"))
    run_id = data.get("runId")
    if data.get("status") == "layout-verified" and data.get("sampleN", 0) == 0:
        continue
    if not run_id or data.get("sampleCount", 0) <= 0:
        raise SystemExit(f"invalid non-empty metadata: {metadata}")
    run_dir = metadata.parent
    for required in ("single-pass.jsonl", "iterative.jsonl", "metrics.json", "commands.txt"):
        if not (run_dir / required).is_file():
            raise SystemExit(f"missing {required} for run {run_id}")
    if not (report_root / f"{run_id}.html").is_file():
        raise SystemExit(f"missing report for run {run_id}")
    if data.get("status") not in ("verified", "pending-reproduction", "layout-verified"):
        raise SystemExit(f"unknown run status for {run_id}: {data.get('status')}")
    dataset_path = pathlib.Path(data.get("datasetPath", ""))
    if not dataset_path.is_absolute():
        dataset_path = root.parents[1] / dataset_path
    if dataset_path.is_file() and data.get("datasetSha256"):
        digest = hashlib.sha256(dataset_path.read_bytes()).hexdigest()
        if digest != data["datasetSha256"]:
            raise SystemExit(f"dataset hash mismatch for {run_id}")
    metrics = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
    for mode in ("singlePass", "iterative"):
        if mode not in metrics:
            raise SystemExit(f"missing {mode} aggregate for {run_id}")
        if metrics[mode].get("status") == "verified" and metrics[mode].get("scoredCount", 0) <= 0:
            raise SystemExit(f"verified aggregate has no scored samples for {run_id}/{mode}")
    if data.get("status") == "verified":
        if any(metrics[mode].get("status") != "verified" for mode in ("singlePass", "iterative")):
            raise SystemExit(f"metadata says verified but an aggregate is pending for {run_id}")
        verified_runs.append(run_id)
    runs.append(run_id)
if not runs:
    raise SystemExit("no non-empty raw run found; execute run-evaluation.py")
if not verified_runs:
    raise SystemExit("no verified raw run found; plan completion requires a scored replay")
print("verified raw runs:", ", ".join(runs))
PY

echo "verify-package: OK ($ROOT)"
