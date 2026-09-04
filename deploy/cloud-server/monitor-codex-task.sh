#!/usr/bin/env bash
set -euo pipefail
TASK_ID="${1:?task id required}"
PROJECT_ID="${2:?project id required}"
ARTIFACT_DIR="${3:-/tmp/codex-memory-run}"
BASE="${RD_BOT_BASE_URL:-http://127.0.0.1:8080}"
LOG="$ARTIFACT_DIR/monitor.log"
mkdir -p "$ARTIFACT_DIR"
terminal=(COMPLETED FAILED FAILED_NEEDS_HUMAN CANCELLED DELETED MERGED)
start=$(date +%s)
while true; do
  json=$(curl -sf "$BASE/admin/rd-tasks/$TASK_ID")
  status=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['status'])" "$json")
  err=$(python3 -c "import json,sys; t=json.loads(sys.argv[1]); print((t.get('errorMessage') or '')[:160])" "$json")
  line="$(date -Iseconds) status=$status err=$err"
  echo "$line" | tee -a "$LOG"
  for t in "${terminal[@]}"; do
    if [[ "$status" == "$t" ]]; then
      echo "$json" > "$ARTIFACT_DIR/final-task.json"
      python3 ~/RD-Bot/deploy/cloud-server/run-codex-memory-live-task.py export-only "$PROJECT_ID" "$TASK_ID" 2>/dev/null || \
        docker exec rd-bot-postgres psql -U postgres -d rdbot -t -A -c \
          "SELECT row_to_json(t) FROM rd_project_memories t WHERE project_id=$PROJECT_ID ORDER BY id" \
          > "$ARTIFACT_DIR/memories-$TASK_ID.jsonl" || true
      echo DONE "$status" after "$(( $(date +%s) - start ))s"
      exit 0
    fi
  done
  if (( $(date +%s) - start > 7200 )); then
    echo TIMEOUT after 7200s | tee -a "$LOG"
    exit 1
  fi
  sleep 60
done
