#!/usr/bin/env bash
# E1 配对基准测试：单题执行器（可重复、参数化）
#
# 用法：
#   ./scripts/benchmarks/run_paired_instance.sh --instance django__django-11019 --arm claude-code
#   ./scripts/benchmarks/run_paired_instance.sh --instance django__django-11019 --arm rd-bot
#   ./scripts/benchmarks/run_paired_instance.sh --instance django__django-11019 --arm both
#
# 产物（每次执行落盘，可追溯）：
#   $METRICS_DIR/e1/<instance>/<arm>/meta.json        执行元数据（时间戳/参数/退出码）
#   $METRICS_DIR/e1/<instance>/claude-code/claude-events.jsonl
#   $METRICS_DIR/e1/<instance>/rd-bot/task.json       终态任务视图
#
# 依赖：jq、python3、claude CLI（claude-code 臂）、RD-Bot 后端运行中（rd-bot 臂）。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
ENV_FILE="scripts/benchmarks/bench.env"
[ -f "$ENV_FILE" ] || ENV_FILE="scripts/benchmarks/bench.env.example"
# shellcheck disable=SC1090
source "$ENV_FILE"

INSTANCE=""
ARM="both"
while [ $# -gt 0 ]; do
  case "$1" in
    --instance) INSTANCE="$2"; shift 2 ;;
    --arm) ARM="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$INSTANCE" ] || { echo "--instance is required" >&2; exit 2; }

TASK_JSON="$(jq -c --arg id "$INSTANCE" '.tasks[] | select(.instance_id == $id)' "$RUN_PLAN")"
[ -n "$TASK_JSON" ] || { echo "instance $INSTANCE not found in $RUN_PLAN" >&2; exit 2; }

run_claude_code() {
  local workspace prompt out started finished exit_code
  workspace="$(jq -r '.agent_workspaces.claude_code' <<<"$TASK_JSON")"
  prompt="$(jq -r '.prompt_paths.claude_code.path' <<<"$TASK_JSON")"
  out="$METRICS_DIR/e1/$INSTANCE/claude-code"
  mkdir -p "$out"

  # 红线 #3：提示词 checksum 必须与 run-plan.json 固化值一致
  local expected actual
  expected="$(jq -r '.prompt_paths.claude_code.sha256' <<<"$TASK_JSON")"
  actual="$(shasum -a 256 "$prompt" | awk '{print $1}')"
  [ "$expected" = "$actual" ] || { echo "prompt checksum mismatch: $prompt" >&2; exit 3; }

  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  set +e
  ( cd "$workspace" && claude -p --bare --dangerously-skip-permissions --no-session-persistence \
      --output-format stream-json --verbose \
      --max-budget-usd "$MAX_BUDGET_USD" --max-turns "$MAX_TURNS" \
    ) < "$prompt" > "$out/claude-events.jsonl" 2> "$out/claude-stderr.log"
  exit_code=$?
  set -e
  finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  jq -n --arg instance "$INSTANCE" --arg arm claude-code \
        --arg started "$started" --arg finished "$finished" \
        --arg model "$CLAUDE_MODEL_LABEL" --arg budget "$MAX_BUDGET_USD" \
        --arg turns "$MAX_TURNS" --arg prompt_sha "$actual" --argjson exit_code "$exit_code" \
        '{instance:$instance, arm:$arm, startedAt:$started, finishedAt:$finished,
          model:$model, maxBudgetUsd:$budget, maxTurns:$turns,
          promptSha256:$prompt_sha, exitCode:$exit_code}' > "$out/meta.json"
  echo "[claude-code] $INSTANCE done, exit=$exit_code -> $out"
}

run_rd_bot() {
  local out
  out="$METRICS_DIR/e1/$INSTANCE/rd-bot"
  mkdir -p "$out"
  RD_BOT_BASE_URL="$RD_BOT_BASE_URL" \
  POLL_INTERVAL_SECONDS="$POLL_INTERVAL_SECONDS" \
  POLL_TIMEOUT_MINUTES="$POLL_TIMEOUT_MINUTES" \
  python3 scripts/benchmarks/submit_and_wait_rd_task.py \
    --run-plan "$RUN_PLAN" --instance "$INSTANCE" --out-dir "$out"
  echo "[rd-bot] $INSTANCE done -> $out"
}

case "$ARM" in
  claude-code) run_claude_code ;;
  rd-bot) run_rd_bot ;;
  both) run_claude_code; run_rd_bot ;;
  *) echo "unknown arm: $ARM (claude-code|rd-bot|both)" >&2; exit 2 ;;
esac
