#!/usr/bin/env bash
# Start RD-Bot on 106.55.13.166 with Pi-friendly resource limits and provider env.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

if [[ -f "$ROOT/.env.opencode.local" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.opencode.local"
fi

# CPA via SSH tunnel (run deploy/cloud-server/start-cpa-tunnel.sh first).
# Set CPA_API_KEY in .env.opencode.local or export before start.

export GH_TOKEN="${GH_TOKEN:-${GITHUB_PAT:-}}"
# Spring binds rd.github.code-platform.pat-token from GITHUB_PAT / GH_TOKEN; keep both set.
export GITHUB_PAT="${GITHUB_PAT:-${GH_TOKEN:-}}"
export RD_AGENT_RUNTIME_MUTATION_TOKEN="${RD_AGENT_RUNTIME_MUTATION_TOKEN:-local-agent-runtime}"
export RD_EXECUTOR_PI_CPU_LIMIT="${RD_EXECUTOR_PI_CPU_LIMIT:-1.5}"
export RD_EXECUTOR_PI_MEMORY_LIMIT="${RD_EXECUTOR_PI_MEMORY_LIMIT:-1500m}"
export SERVER_PORT="${SERVER_PORT:-8080}"
# Recover a leftover tokenized insteadOf before wiping url.* rewrites.
if [[ -z "${GH_TOKEN}" ]]; then
  recovered="$(git config --global --get-regexp '^url\.' 2>/dev/null \
    | sed -n 's#^url\.https://x-access-token:\([^@]*\)@github.com/.*#\1#p' \
    | head -1 || true)"
  if [[ -n "${recovered}" ]]; then
    export GH_TOKEN="${recovered}"
    export GITHUB_PAT="${recovered}"
  fi
  unset recovered
fi
if [[ -z "${GH_TOKEN}" ]]; then
  echo "WARNING: GH_TOKEN/GITHUB_PAT empty — GitHub clone/PR will fail PAT_LOCAL_SMOKE" >&2
fi
# Host git must not prompt on a TTY-less JVM child (hangs until rd.executor.docker.git.timeout-seconds).
export RD_EXECUTOR_DOCKER_GIT_TIMEOUT_SECONDS="${RD_EXECUTOR_DOCKER_GIT_TIMEOUT_SECONDS:-600}"

# Bridge npm provision only. Isolated Pi agents stay npm_config_offline=true.
if [[ -z "${npm_config_registry:-}" && -f "${HOME}/.npmrc" ]]; then
  npm_config_registry="$(sed -n 's/^[[:space:]]*registry=//p' "${HOME}/.npmrc" | head -1 | tr -d '\r')"
  export npm_config_registry
fi

# Fetch the live waimai repo from the local bare mirror (github.com:443 is unreliable
# on this VM). Push MUST still go to GitHub — never insteadOf both fetch and push to
# the mirror (that yields BRANCH_CONFIRMED with a missing GitHub head).
WAIMAI_HTTPS="https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git"
WAIMAI_MIRROR="${HOME}/mirror/waimai.git"
while IFS= read -r key; do
  git config --global --unset-all "$key" 2>/dev/null || true
done < <(git config --global --name-only --get-regexp '^url\.' 2>/dev/null || true)
if [[ -d "${WAIMAI_MIRROR}" ]]; then
  git config --global "url.file://${WAIMAI_MIRROR}.insteadOf" "${WAIMAI_HTTPS}"
fi
# Ubuntu git (GnuTLS) + HTTP/2 to github.com:443 yields recv error -110 on push.
git config --global http.version HTTP/1.1
git config --global http.postBuffer 524288000
if [[ -n "${GH_TOKEN:-}" ]]; then
  git config --global url."https://x-access-token:${GH_TOKEN}@github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git".pushInsteadOf "${WAIMAI_HTTPS}"
fi

# Spring Boot loads ./application-local.yaml from the JVM cwd (not src/main/resources
# after the jar is built). Keep src copy for local IDE runs; runtime overlay goes here.
cp "$ROOT/deploy/cloud-server/application-local.server.yaml" \
  "$ROOT/application-local.yaml"
cp "$ROOT/deploy/cloud-server/application-local.server.yaml" \
  "$ROOT/bootstrap/src/main/resources/application-local.yaml"

# Kill only the JVM that is actually running the jar. `pkill -f bootstrap-0.1.0-SNAPSHOT.jar`
# matches the SSH remote command itself when this script is invoked over ssh.
JAR_NAME="bootstrap-0.1.0-SNAPSHOT.jar"
for cmdline in /proc/[0-9]*/cmdline; do
  pid="${cmdline%/cmdline}"
  pid="${pid##*/}"
  args="$(tr '\0' ' ' < "$cmdline" 2>/dev/null || true)"
  case "$args" in
    java\ *"${JAR_NAME}"*) kill "$pid" 2>/dev/null || true ;;
  esac
done
sleep 2
nohup env \
  CPA_API_KEY="${CPA_API_KEY:-}" \
  OPENCODE_API_KEY="${OPENCODE_API_KEY:-}" \
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}" \
  GH_TOKEN="${GH_TOKEN:-}" \
  GITHUB_PAT="${GITHUB_PAT:-}" \
  RD_AGENT_RUNTIME_MUTATION_TOKEN="$RD_AGENT_RUNTIME_MUTATION_TOKEN" \
  RD_EXECUTOR_PI_CPU_LIMIT="$RD_EXECUTOR_PI_CPU_LIMIT" \
  RD_EXECUTOR_PI_MEMORY_LIMIT="$RD_EXECUTOR_PI_MEMORY_LIMIT" \
  SERVER_PORT="$SERVER_PORT" \
  GIT_TERMINAL_PROMPT=0 \
  npm_config_registry="${npm_config_registry:-}" \
  RD_EXECUTOR_DOCKER_GIT_TIMEOUT_SECONDS="${RD_EXECUTOR_DOCKER_GIT_TIMEOUT_SECONDS:-600}" \
  java -jar "$ROOT/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar" \
  --spring.profiles.active=local \
  >> /tmp/rd-bot-backend.log 2>&1 &

echo "backend starting on :$SERVER_PORT (log: /tmp/rd-bot-backend.log)"
