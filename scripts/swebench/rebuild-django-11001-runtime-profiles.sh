#!/usr/bin/env bash
# Rebuild the four operator-approved Docker runtime profiles for django__django-11001.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BASE_URL="${RD_BOT_BASE_URL:-http://127.0.0.1:18080}"
BASE_URL="${BASE_URL%/}"
PROJECT_ID="${RD_PROJECT_ID:-7485660426315894784}"
UPLOAD_TOKEN="${RD_RUNTIME_PROFILE_UPLOAD_TOKEN:-}"
CURL_BIN="${CURL_BIN:-curl}"
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage: rebuild-django-11001-runtime-profiles.sh [--dry-run]

Uploads the four restricted Django 11001 runtime Dockerfiles to the local
RD-Bot project. The backend must already be running with the same non-empty
RD_RUNTIME_PROFILE_UPLOAD_TOKEN value.

Optional environment variables:
  RD_BOT_BASE_URL    Backend URL (default: http://127.0.0.1:18080)
  RD_PROJECT_ID      Project ID (default: 7485660426315894784)
  CURL_BIN           curl executable for testing (default: curl)
EOF
}

case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=true ;;
  -h|--help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

if [[ -z "$UPLOAD_TOKEN" ]]; then
  echo "RD_RUNTIME_PROFILE_UPLOAD_TOKEN is required; restart RD-Bot with the same value before uploading." >&2
  exit 2
fi

command -v "$CURL_BIN" >/dev/null 2>&1 \
  || { echo "curl executable not found: $CURL_BIN" >&2; exit 127; }

upload_profile() {
  local role="$1"
  local filename="$2"
  local dockerfile="$ROOT/scripts/swebench/$filename"
  local endpoint="$BASE_URL/admin/projects/$PROJECT_ID/runtime-profiles/$role"

  [[ -f "$dockerfile" ]] || { echo "missing runtime Dockerfile: $dockerfile" >&2; exit 1; }

  if [[ "$DRY_RUN" == true ]]; then
    printf 'Would upload %-22s %s\n' "$role" "$dockerfile"
    return
  fi

  printf 'Uploading %-22s %s\n' "$role" "$dockerfile"
  "$CURL_BIN" --fail --silent --show-error \
    --request PUT \
    --header "X-RD-Runtime-Profile-Token: $UPLOAD_TOKEN" \
    --form 'agentType=CLAUDE_CODE' \
    --form "dockerfile=@$dockerfile;type=text/plain" \
    "$endpoint"
  printf '\n'
}

upload_profile REQUIREMENT_REVIEWER Dockerfile.django-python
upload_profile SOLUTION_ARCHITECT Dockerfile.django-python
upload_profile CODING_AGENT Dockerfile.django-python
upload_profile QA_AGENT Dockerfile.django-python-qa

if [[ "$DRY_RUN" == true ]]; then
  echo "Dry run complete; no Docker image was built."
else
  echo "Runtime-profile rebuild requests completed. Confirm each profile reports rd-bot-runtime-contract=v2 before retrying the task."
fi
