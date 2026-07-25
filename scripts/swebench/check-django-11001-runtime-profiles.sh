#!/usr/bin/env bash
# Fail closed unless all Django 11001 role runtimes use the current verified contract.
set -euo pipefail

BASE_URL="${RD_BOT_BASE_URL:-http://127.0.0.1:18080}"
BASE_URL="${BASE_URL%/}"
PROJECT_ID="${RD_PROJECT_ID:-7485660426315894784}"
CURL_BIN="${CURL_BIN:-curl}"
RESPONSE_FILE="$(mktemp)"
trap 'rm -f "$RESPONSE_FILE"' EXIT

usage() {
  cat <<'EOF'
Usage: check-django-11001-runtime-profiles.sh

Checks that every django__django-11001 role has a verified CLAUDE_CODE runtime
using the rd-bot-runtime-contract=v2 image contract. It makes only a GET
request and never builds images or starts an agent.

Optional environment variables:
  RD_BOT_BASE_URL    Backend URL (default: http://127.0.0.1:18080)
  RD_PROJECT_ID      Project ID (default: 7485660426315894784)
  CURL_BIN           curl executable for testing (default: curl)
EOF
}

case "${1:-}" in
  "") ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

command -v "$CURL_BIN" >/dev/null 2>&1 \
  || { echo "curl executable not found: $CURL_BIN" >&2; exit 127; }

"$CURL_BIN" --fail --silent --show-error \
  "$BASE_URL/admin/projects/$PROJECT_ID/runtime-profiles" \
  > "$RESPONSE_FILE"

python3 - "$RESPONSE_FILE" <<'PY'
import json
import sys
from pathlib import Path

MARKER = "rd-bot-runtime-contract=v2"
EXPECTED = {
    "REQUIREMENT_REVIEWER": "Dockerfile.django-python",
    "SOLUTION_ARCHITECT": "Dockerfile.django-python",
    "CODING_AGENT": "Dockerfile.django-python",
    "QA_AGENT": "Dockerfile.django-python-qa",
}

try:
    profiles = json.loads(Path(sys.argv[1]).read_text())
except (OSError, json.JSONDecodeError) as error:
    print(f"ERROR invalid runtime-profile response: {error}", file=sys.stderr)
    raise SystemExit(1)

if not isinstance(profiles, list):
    print("ERROR runtime-profile response must be a JSON list", file=sys.stderr)
    raise SystemExit(1)

by_role = {}
for profile in profiles:
    if isinstance(profile, dict) and isinstance(profile.get("role"), str):
        by_role[profile["role"]] = profile

errors = []
unexpected_roles = sorted(set(by_role) - set(EXPECTED))
if unexpected_roles:
    errors.append("unexpected runtime roles: " + ", ".join(unexpected_roles))

for role, expected_dockerfile in EXPECTED.items():
    profile = by_role.get(role)
    if profile is None:
        errors.append(f"{role}: missing profile")
        continue
    if profile.get("agentType") != "CLAUDE_CODE":
        errors.append(f"{role}: agentType is not CLAUDE_CODE")
    if profile.get("dockerfileName") != expected_dockerfile:
        errors.append(f"{role}: expected {expected_dockerfile}")
    if profile.get("validationStatus") != "VERIFIED":
        errors.append(f"{role}: validationStatus is not VERIFIED")
    summary = profile.get("validationSummary")
    if not isinstance(summary, str) or MARKER not in summary:
        errors.append(f"{role}: missing {MARKER}")
    image = profile.get("image")
    if not isinstance(image, str) or not image.strip():
        errors.append(f"{role}: verified image is missing")

if errors:
    for error in errors:
        print(f"ERROR {error}", file=sys.stderr)
    raise SystemExit(1)

for role in EXPECTED:
    profile = by_role[role]
    print(f"OK {role}: {profile['dockerfileName']} -> {profile['image']}")
PY
