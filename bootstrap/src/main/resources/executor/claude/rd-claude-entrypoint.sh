#!/usr/bin/env bash
set -euo pipefail

PROMPT_FILE="/work/input/prompt.md"
SCHEMA_FILE="/work/input/result.schema.json"
OUTPUT_DIR="/work/output"
RESULT_FILE="${OUTPUT_DIR}/result.json"
PATCH_FILE="${OUTPUT_DIR}/patch.diff"
TEST_LOG_FILE="${OUTPUT_DIR}/test.log"
CLAUDE_EVENTS_FILE="${OUTPUT_DIR}/claude-events.jsonl"
DOCKER_META_FILE="${OUTPUT_DIR}/docker-meta.json"

if [[ ! -f "$PROMPT_FILE" ]]; then
  echo "missing required prompt file: $PROMPT_FILE" >&2
  exit 64
fi

if [[ ! -f "$SCHEMA_FILE" ]]; then
  echo "missing required schema file: $SCHEMA_FILE" >&2
  exit 64
fi

mkdir -p "$OUTPUT_DIR"

local_fallback_should_run() {
  local code="$1"
  local events_file="$2"
  if [[ "$code" -eq 0 ]]; then
    return 1
  fi
  if [[ ! -s "$events_file" ]]; then
    return 1
  fi
  grep -Eiq 'API Error:|Insufficient Balance|rate limit|overloaded|temporarily unavailable|provider.*unavailable|authentication|permission' "$events_file"
}

if [[ -n "${RD_CLAUDE_AUTH_TOKEN_ENV:-}" ]]; then
  auth_token_value="${!RD_CLAUDE_AUTH_TOKEN_ENV:-}"
  if [[ -n "$auth_token_value" ]]; then
    export ANTHROPIC_AUTH_TOKEN="$auth_token_value"
  fi
fi

if [[ -n "${RD_CLAUDE_API_KEY_ENV:-}" ]]; then
  api_key_value="${!RD_CLAUDE_API_KEY_ENV:-}"
  if [[ -n "$api_key_value" ]]; then
    export ANTHROPIC_API_KEY="$api_key_value"
  fi
fi

if [[ "$#" -eq 0 ]]; then
  set -- claude -p --dangerously-skip-permissions --output-format stream-json --verbose
fi

started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
prompt="$(
  cat "$PROMPT_FILE"
  printf '\n\nYou must write the final structured result JSON to %s and it must satisfy %s.\n' "$RESULT_FILE" "$SCHEMA_FILE"
  printf 'Write a unified git diff to %s and a concise test log to %s.\n' "$PATCH_FILE" "$TEST_LOG_FILE"
)"

set +e
(
  cd /work/repo
  "$@" "$prompt"
) 2>&1 | tee "$CLAUDE_EVENTS_FILE"
exit_code="${PIPESTATUS[0]}"
set -e

if [[ "${RD_CLAUDE_LOCAL_FALLBACK_ENABLED:-false}" == "true" ]] \
    && local_fallback_should_run "$exit_code" "$CLAUDE_EVENTS_FILE"; then
  set +e
  (
    cd /work/repo
    RD_LOCAL_REPAIR_REPO="/work/repo" \
      PROMPT_FILE="$PROMPT_FILE" \
      OUTPUT_DIR="$OUTPUT_DIR" \
      RESULT_FILE="$RESULT_FILE" \
      PATCH_FILE="$PATCH_FILE" \
      TEST_LOG_FILE="$TEST_LOG_FILE" \
      node /usr/local/bin/rd-local-repair-worker.mjs
  ) 2>&1 | tee -a "$CLAUDE_EVENTS_FILE"
  fallback_exit_code="${PIPESTATUS[0]}"
  set -e
  if [[ "$fallback_exit_code" -eq 0 ]]; then
    exit_code=0
  fi
fi

(
  cd /work/repo
  generated_patch="$(mktemp)"
  git diff --binary > "$generated_patch"
  if [[ -s "$generated_patch" ]]; then
    mv "$generated_patch" "$PATCH_FILE"
  else
    rm -f "$generated_patch"
    if [[ ! -s "$PATCH_FILE" ]] && git rev-parse --verify HEAD~1 >/dev/null 2>&1; then
      git diff --binary HEAD~1 HEAD > "$PATCH_FILE"
    fi
  fi
) || true

if [[ ! -f "$TEST_LOG_FILE" ]]; then
  printf 'Claude Code execution finished with exit code %s. See %s for event stream.\n' "$exit_code" "$CLAUDE_EVENTS_FILE" > "$TEST_LOG_FILE"
fi

if [[ ! -s "$RESULT_FILE" ]]; then
  jq -n \
    --arg summary "Claude Code did not write result.json. See claude-events.jsonl and test.log for details." \
    '{
      status: "FAILED",
      summary: $summary,
      changedFiles: [],
      testCommands: [],
      testStatus: "SKIPPED",
      riskLevel: "HIGH",
      prBody: "",
      needHumanAction: true
    }' > "$RESULT_FILE"
fi

jq -e . "$RESULT_FILE" > /dev/null

ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
jq -n \
  --arg startedAt "$started_at" \
  --arg endedAt "$ended_at" \
  --arg user "$(id -un)" \
  --arg workdir "/work/repo" \
  --arg outputDir "$OUTPUT_DIR" \
  --arg exitCode "$exit_code" \
  '{
    startedAt: $startedAt,
    endedAt: $endedAt,
    user: $user,
    workingDirectory: $workdir,
    outputDirectory: $outputDir,
    exitCode: ($exitCode | tonumber)
  }' > "$DOCKER_META_FILE"

exit "$exit_code"
