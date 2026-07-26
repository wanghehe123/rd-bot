#!/usr/bin/env bash
# 断点重试 10924/10914 的 QA 阶段（前三角色产物复用，零重跑）
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
LOG=logs/retry-qa-pair.log

switch_backend(){
  local NUM="$1" pid cur
  pid=$(lsof -nP -iTCP:18080 -sTCP:LISTEN -t 2>/dev/null || true)
  if [ -n "$pid" ]; then
    cur=$(ps eww "$pid" | tr ' ' '\n' | grep '^RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY=' | cut -d= -f2)
    [ "$cur" = "wanghehe123/swebench-lite-django-$NUM" ] && { echo "reuse backend $NUM" >> "$LOG"; return 0; }
    kill "$pid" 2>/dev/null; sleep 8
  fi
  nohup "./scripts/swebench/start-rd-bot-private-django-$NUM.sh" > "logs/rd-bot-django-$NUM.log" 2>&1 &
  for i in $(seq 1 36); do sleep 5; curl -sf -o /dev/null http://localhost:18080/admin/rd-tasks/7486814737628532736 && return 0; done
  return 1
}

retry_one(){
  local inst="$1" task_id="$2"
  local NUM="${inst##*-}"
  local out="qa-runs/benchmarks-metrics/e1/$inst/rd-bot"
  echo "=== retry [$inst] task=$task_id $(date -u +%H:%M:%S) ===" >> "$LOG"
  switch_backend "$NUM" || { echo "backend fail $NUM" >> "$LOG"; return 1; }
  local V
  V=$(curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/retry-preview" | jq -r .sourceTaskVersion)
  curl -s -X POST "http://localhost:18080/admin/rd-tasks/$task_id/retry" -H 'Content-Type: application/json' \
    -d "{\"expectedSourceTaskVersion\":$V}" >> "$LOG" 2>&1
  echo "" >> "$LOG"
  local final="TIMEOUT" st=""
  for i in $(seq 1 120); do
    st=$(curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" | jq -r '.status' 2>/dev/null)
    case "$st" in COMPLETED|FAILED|FAILED_NEEDS_HUMAN|STOPPED|CANCELLED|REJECTED) final="$st"; break;; esac
    sleep 60
  done
  sleep 20
  mkdir -p "$out"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" > "$out/task.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/execution-overview" > "$out/overview.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/timeline" > "$out/timeline.json"
  echo "=== retry [$inst] done $(date -u +%H:%M:%S) final=$final ===" >> "$LOG"
}

retry_one django__django-10924 7487004839310921728
retry_one django__django-10914 7487010815002939392
echo "RETRY_PAIR_DONE" >> "$LOG"
