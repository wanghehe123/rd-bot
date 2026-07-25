#!/usr/bin/env bash
# E2 强制中断恢复实验：单次实验执行器（参数化，可重复 20 次）
#
# 用法：
#   ./scripts/benchmarks/interrupt_recovery.sh --task-id 748... --scenario kill-backend --case-id case-01
#   scenario ∈ kill-backend | kill-container | validating | pr-creating
#
# 判定三项（自动计算并落盘）：
#   resumeSuccess  重启后任务到达终态 COMPLETED
#   zeroRerun      中断前 SUCCEEDED 的 stage 未产生新 attempt
#   recoverySeconds 后端重启完成 → 任务重新进入执行态的秒数
#
# 产物：$INTERRUPT_DIR/<date>/<case-id>.json
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
ENV_FILE="scripts/benchmarks/bench.env"
[ -f "$ENV_FILE" ] || ENV_FILE="scripts/benchmarks/bench.env.example"
# shellcheck disable=SC1090
source "$ENV_FILE"

TASK_ID="" SCENARIO="" CASE_ID=""
while [ $# -gt 0 ]; do
  case "$1" in
    --task-id) TASK_ID="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --case-id) CASE_ID="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$TASK_ID" ] && [ -n "$SCENARIO" ] && [ -n "$CASE_ID" ] || {
  echo "usage: --task-id <id> --scenario <kill-backend|kill-container|validating|pr-creating> --case-id <id>" >&2
  exit 2
}

OUT_DIR="$INTERRUPT_DIR/$(date +%Y%m%d)"
mkdir -p "$OUT_DIR"
PRE_SNAPSHOT="$OUT_DIR/${CASE_ID}-pre.json"
POST_SNAPSHOT="$OUT_DIR/${CASE_ID}-post.json"

snapshot() {
  curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks/$TASK_ID/execution-overview" > "$1"
  curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks/$TASK_ID" | jq '{status, taskId}' >> "$1.status"
}

wait_for_stage() {
  # validating / pr-creating 场景：等任务进入目标状态再打断
  local target="$1"
  echo "waiting for task to reach $target ..."
  while true; do
    local status
    status="$(curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks/$TASK_ID" | jq -r '.status')"
    [ "$status" = "$target" ] && return 0
    case "$status" in COMPLETED|FAILED|STOPPED) echo "task already terminal: $status" >&2; return 1 ;; esac
    sleep 5
  done
}

# 1. 中断前快照
case "$SCENARIO" in
  validating) wait_for_stage "VALIDATING" ;;
  pr-creating) wait_for_stage "PR_CREATING" ;;
  *) ;;
esac
snapshot "$PRE_SNAPSHOT"
INTERRUPTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 2. 施加中断
case "$SCENARIO" in
  kill-backend|validating|pr-creating)
    echo "kill -9 backend ($RD_BOT_PROCESS_PATTERN) ..."
    pkill -9 -f "$RD_BOT_PROCESS_PATTERN" || true
    ;;
  kill-container)
    CONTAINER="$(docker ps --format '{{.ID}} {{.Names}}' | grep -i "$TASK_ID" | awk '{print $1}' | head -1)"
    [ -n "${CONTAINER:-}" ] || { echo "no running container matched task $TASK_ID" >&2; exit 3; }
    docker kill "$CONTAINER"
    ;;
esac

# 3. 恢复：kill-backend 类场景重启后端；kill-container 场景后端自身负责重派
if [ "$SCENARIO" != "kill-container" ]; then
  echo "restarting backend ..."
  nohup bash -c "$RD_BOT_RESTART_CMD" > "$OUT_DIR/${CASE_ID}-backend.log" 2>&1 &
  until curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks?page=1&pageSize=1" > /dev/null 2>&1; do sleep 3; done
fi
RESTARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 4. 观测恢复：先等重新进入执行态（记恢复时延），再等终态
RESUMED_AT=""
DEADLINE=$(( $(date +%s) + RECOVERY_WAIT_SECONDS + POLL_TIMEOUT_MINUTES * 60 ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  STATUS="$(curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks/$TASK_ID" | jq -r '.status')"
  if [ -z "$RESUMED_AT" ]; then
    case "$STATUS" in EXECUTING|VALIDATING|PR_CREATING|RECOVERING) RESUMED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" ;; esac
  fi
  case "$STATUS" in COMPLETED|FAILED|STOPPED) break ;; esac
  sleep "$POLL_INTERVAL_SECONDS"
done
FINAL_STATUS="$(curl -sf "$RD_BOT_BASE_URL/admin/rd-tasks/$TASK_ID" | jq -r '.status')"
snapshot "$POST_SNAPSHOT"

# 5. 判定并落盘（zeroRerun：对比前后快照中 SUCCEEDED stage 的 attemptNo）
python3 - "$PRE_SNAPSHOT" "$POST_SNAPSHOT" <<'EOF' > "$OUT_DIR/${CASE_ID}-zero-rerun.json"
import json, sys
def attempts(path):
    data = json.load(open(path))
    stages = data.get("stageRuns") or data.get("stages") or []
    return {(s.get("role"), s.get("stageRunId")): s for s in stages}
pre, post = attempts(sys.argv[1]), attempts(sys.argv[2])
pre_succeeded = {k for k, s in pre.items() if s.get("status") == "SUCCEEDED"}
reran = []
for key in pre_succeeded:
    role = key[0]
    pre_max = max((s.get("attemptNo", 1) for k, s in pre.items() if k[0] == role), default=0)
    post_max = max((s.get("attemptNo", 1) for k, s in post.items() if k[0] == role), default=0)
    if post_max > pre_max:
        reran.append(role)
print(json.dumps({"zeroRerun": not reran, "reranRoles": sorted(set(reran))}))
EOF

ZERO_RERUN="$(jq -r '.zeroRerun' "$OUT_DIR/${CASE_ID}-zero-rerun.json")"
RECOVERY_SECONDS="null"
if [ -n "$RESUMED_AT" ]; then
  RECOVERY_SECONDS=$(( $(date -jf "%Y-%m-%dT%H:%M:%SZ" "$RESUMED_AT" +%s) - $(date -jf "%Y-%m-%dT%H:%M:%SZ" "$RESTARTED_AT" +%s) ))
fi

jq -n --arg caseId "$CASE_ID" --arg taskId "$TASK_ID" --arg scenario "$SCENARIO" \
      --arg interruptedAt "$INTERRUPTED_AT" --arg restartedAt "$RESTARTED_AT" \
      --arg resumedAt "${RESUMED_AT:-}" --arg finalStatus "$FINAL_STATUS" \
      --argjson zeroRerun "$ZERO_RERUN" --argjson recoverySeconds "$RECOVERY_SECONDS" \
      '{caseId:$caseId, taskId:$taskId, scenario:$scenario,
        interruptedAt:$interruptedAt, restartedAt:$restartedAt, resumedAt:$resumedAt,
        finalStatus:$finalStatus,
        resumeSuccess:($finalStatus=="COMPLETED"),
        zeroRerun:$zeroRerun, recoverySeconds:$recoverySeconds}' \
  > "$OUT_DIR/${CASE_ID}.json"

echo "case $CASE_ID: final=$FINAL_STATUS zeroRerun=$ZERO_RERUN recovery=${RECOVERY_SECONDS}s -> $OUT_DIR/${CASE_ID}.json"
