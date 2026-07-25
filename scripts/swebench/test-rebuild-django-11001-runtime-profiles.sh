#!/usr/bin/env bash
# Shell contract test for the operator-only Django runtime-profile uploader.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/swebench/rebuild-django-11001-runtime-profiles.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

export CURL_LOG="$TMP_DIR/curl.log"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf "%s\\n" "--- request ---" >> "$CURL_LOG"' \
  'printf "%s\\n" "$@" >> "$CURL_LOG"' \
  'printf "{\\"status\\":\\"accepted\\"}\\n"' \
  > "$TMP_DIR/curl"
chmod +x "$TMP_DIR/curl"

expect_failure_without_token() {
  if env -u RD_RUNTIME_PROFILE_UPLOAD_TOKEN PATH="$TMP_DIR:$PATH" bash "$SCRIPT" \
    >"$TMP_DIR/no-token.out" 2>"$TMP_DIR/no-token.err"; then
    echo "expected uploader to reject a missing upload token" >&2
    exit 1
  fi
  rg -q 'RD_RUNTIME_PROFILE_UPLOAD_TOKEN is required' "$TMP_DIR/no-token.err"
}

expect_dry_run() {
  : > "$CURL_LOG"
  PATH="$TMP_DIR:$PATH" \
    RD_RUNTIME_PROFILE_UPLOAD_TOKEN='test-token' \
    RD_BOT_BASE_URL='http://127.0.0.1:18080' \
    RD_PROJECT_ID='test-project' \
    bash "$SCRIPT" --dry-run > "$TMP_DIR/dry-run.out"

  [[ ! -s "$CURL_LOG" ]] || { echo "dry-run must not call curl" >&2; exit 1; }
  rg -q 'REQUIREMENT_REVIEWER.*Dockerfile.django-python' "$TMP_DIR/dry-run.out"
  rg -q 'SOLUTION_ARCHITECT.*Dockerfile.django-python' "$TMP_DIR/dry-run.out"
  rg -q 'CODING_AGENT.*Dockerfile.django-python' "$TMP_DIR/dry-run.out"
  rg -q 'QA_AGENT.*Dockerfile.django-python-qa' "$TMP_DIR/dry-run.out"
}

expect_upload_requests() {
  : > "$CURL_LOG"
  PATH="$TMP_DIR:$PATH" \
    RD_RUNTIME_PROFILE_UPLOAD_TOKEN='test-token' \
    RD_BOT_BASE_URL='http://127.0.0.1:18080/' \
    RD_PROJECT_ID='test-project' \
    bash "$SCRIPT" > "$TMP_DIR/upload.out"

  [[ "$(rg -c '^--- request ---$' "$CURL_LOG")" == 4 ]] \
    || { echo "expected four runtime-profile uploads" >&2; exit 1; }
  [[ "$(rg -c '^X-RD-Runtime-Profile-Token: test-token$' "$CURL_LOG")" == 4 ]] \
    || { echo "expected upload token on every request" >&2; exit 1; }
  [[ "$(rg -c '^agentType=CLAUDE_CODE$' "$CURL_LOG")" == 4 ]] \
    || { echo "expected Claude Code agent type on every request" >&2; exit 1; }
  rg -q '/admin/projects/test-project/runtime-profiles/REQUIREMENT_REVIEWER$' "$CURL_LOG"
  rg -q '/admin/projects/test-project/runtime-profiles/SOLUTION_ARCHITECT$' "$CURL_LOG"
  rg -q '/admin/projects/test-project/runtime-profiles/CODING_AGENT$' "$CURL_LOG"
  rg -q '/admin/projects/test-project/runtime-profiles/QA_AGENT$' "$CURL_LOG"
  rg -q 'Dockerfile.django-python-qa' "$CURL_LOG"
}

expect_failure_without_token
expect_dry_run
expect_upload_requests

echo "Runtime-profile rebuild script contract passed."
