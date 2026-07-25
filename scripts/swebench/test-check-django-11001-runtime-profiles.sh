#!/usr/bin/env bash
# Shell contract test for the strict runtime-profile verification gate.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/swebench/check-django-11001-runtime-profiles.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/profiles.json" <<'JSON'
[
  {"role":"REQUIREMENT_REVIEWER","agentType":"CLAUDE_CODE","image":"rd-bot/project-runtime-project-requirement_reviewer-new","dockerfileName":"Dockerfile.django-python","validationStatus":"VERIFIED","validationSummary":"rd-bot-runtime-contract=v2; Claude Code runtime contract verified"},
  {"role":"SOLUTION_ARCHITECT","agentType":"CLAUDE_CODE","image":"rd-bot/project-runtime-project-solution_architect-new","dockerfileName":"Dockerfile.django-python","validationStatus":"VERIFIED","validationSummary":"rd-bot-runtime-contract=v2; Claude Code runtime contract verified"},
  {"role":"CODING_AGENT","agentType":"CLAUDE_CODE","image":"rd-bot/project-runtime-project-coding_agent-new","dockerfileName":"Dockerfile.django-python","validationStatus":"VERIFIED","validationSummary":"rd-bot-runtime-contract=v2; Claude Code runtime contract verified"},
  {"role":"QA_AGENT","agentType":"CLAUDE_CODE","image":"rd-bot/project-runtime-project-qa_agent-new","dockerfileName":"Dockerfile.django-python-qa","validationStatus":"VERIFIED","validationSummary":"rd-bot-runtime-contract=v2; Claude Code runtime contract verified"}
]
JSON

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'cat "$FAKE_RUNTIME_PROFILES_JSON"' \
  > "$TMP_DIR/curl"
chmod +x "$TMP_DIR/curl"

expect_current_profiles_to_pass() {
  PATH="$TMP_DIR:$PATH" \
    FAKE_RUNTIME_PROFILES_JSON="$TMP_DIR/profiles.json" \
    CURL_BIN=curl \
    RD_BOT_BASE_URL='http://127.0.0.1:18080/' \
    RD_PROJECT_ID='project' \
    bash "$SCRIPT" > "$TMP_DIR/current.out"

  [[ "$(rg -c '^OK ' "$TMP_DIR/current.out")" == 4 ]] \
    || { echo "expected one success row for each role" >&2; exit 1; }
  rg -q 'OK CODING_AGENT.*Dockerfile.django-python' "$TMP_DIR/current.out"
  rg -q 'OK QA_AGENT.*Dockerfile.django-python-qa' "$TMP_DIR/current.out"
}

expect_legacy_profiles_to_fail() {
  python3 - "$TMP_DIR/profiles.json" "$TMP_DIR/legacy.json" <<'PY'
import json
import sys

profiles = json.load(open(sys.argv[1]))
for profile in profiles:
    profile["validationSummary"] = "Claude Code runtime contract verified"
json.dump(profiles, open(sys.argv[2], "w"))
PY

  if PATH="$TMP_DIR:$PATH" \
    FAKE_RUNTIME_PROFILES_JSON="$TMP_DIR/legacy.json" \
    CURL_BIN=curl \
    RD_BOT_BASE_URL='http://127.0.0.1:18080' \
    RD_PROJECT_ID='project' \
    bash "$SCRIPT" > "$TMP_DIR/legacy.out" 2> "$TMP_DIR/legacy.err"; then
    echo "expected legacy profiles to fail the v2 verification gate" >&2
    exit 1
  fi
  rg -q 'missing rd-bot-runtime-contract=v2' "$TMP_DIR/legacy.err"
}

expect_current_profiles_to_pass
expect_legacy_profiles_to_fail

echo "Runtime-profile verification gate contract passed."
