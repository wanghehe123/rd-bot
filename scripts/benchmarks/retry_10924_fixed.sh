#!/usr/bin/env bash
# 等 10914 retry 终态后，用修正后的验收标准断点重试 10924 QA
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
LOG=logs/retry-10924-fixed.log
TASK=7487004839310921728
INST=django__django-10924

echo "=== waiting for retry_qa_pair to finish $(date -u +%H:%M:%S) ===" >> "$LOG"
for i in $(seq 1 90); do
  grep -q "RETRY_PAIR_DONE" logs/retry-qa-pair.log 2>/dev/null && break
  sleep 60
done
grep -q "RETRY_PAIR_DONE" logs/retry-qa-pair.log || { echo "pair orchestrator still running, abort" >> "$LOG"; exit 1; }

# 切后端到 10924
pid=$(lsof -nP -iTCP:18080 -sTCP:LISTEN -t 2>/dev/null || true)
if [ -n "$pid" ]; then
  cur=$(ps eww "$pid" | tr ' ' '\n' | grep '^RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY=' | cut -d= -f2)
  if [ "$cur" != "wanghehe123/swebench-lite-django-10924" ]; then
    kill "$pid" 2>/dev/null; sleep 8
    nohup ./scripts/swebench/start-rd-bot-private-django-10924.sh > logs/rd-bot-django-10924.log 2>&1 &
  fi
else
  nohup ./scripts/swebench/start-rd-bot-private-django-10924.sh > logs/rd-bot-django-10924.log 2>&1 &
fi
ok=""
for i in $(seq 1 36); do sleep 5; curl -sf -o /dev/null "http://localhost:18080/admin/rd-tasks/$TASK" && { ok=1; break; }; done
[ -n "$ok" ] || { echo "backend not ready" >> "$LOG"; exit 1; }

echo "=== retry [$INST] task=$TASK $(date -u +%H:%M:%S) with fixed acceptance ===" >> "$LOG"
V=$(curl -sf "http://localhost:18080/admin/rd-tasks/$TASK/retry-preview" | jq -r .sourceTaskVersion)
curl -s -X POST "http://localhost:18080/admin/rd-tasks/$TASK/retry" -H 'Content-Type: application/json' \
  -d "{\"expectedSourceTaskVersion\":$V}" >> "$LOG" 2>&1
echo "" >> "$LOG"

final="TIMEOUT" st=""
for i in $(seq 1 120); do
  st=$(curl -sf "http://localhost:18080/admin/rd-tasks/$TASK" | jq -r '.status' 2>/dev/null)
  case "$st" in COMPLETED|FAILED|FAILED_NEEDS_HUMAN|STOPPED|CANCELLED|REJECTED) final="$st"; break;; esac
  sleep 60
done
sleep 20
out="qa-runs/benchmarks-metrics/e1/$INST/rd-bot"
mkdir -p "$out"
curl -sf "http://localhost:18080/admin/rd-tasks/$TASK" > "$out/task.json"
curl -sf "http://localhost:18080/admin/rd-tasks/$TASK/execution-overview" > "$out/overview.json"
curl -sf "http://localhost:18080/admin/rd-tasks/$TASK/timeline" > "$out/timeline.json"
echo "=== retry [$INST] done $(date -u +%H:%M:%S) final=$final ===" >> "$LOG"
echo "RETRY_10924_FIXED_DONE" >> "$LOG"
