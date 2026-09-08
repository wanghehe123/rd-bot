#!/usr/bin/env bash
# UNSUPPORTED historical example — bare-metal VM startup script from the maintainer's
# private server. This is NOT the supported deployment path; the supported path is the
# Docker Compose stack started via `./scripts/rd-bot.sh up` (see README.md).
# This example is kept only to document the bare-metal JVM startup pattern
# (resource limits, git timeout, nohup restart). It contains no host-specific values.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

EPISODE_BUDGET_ENV="$ROOT/deploy/cloud-server/episode-budget.sh"
if [[ -f "$EPISODE_BUDGET_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$EPISODE_BUDGET_ENV"
fi
# Back-compat: server may still have episode-budget.env (gitignored *.env).
if [[ -f "$ROOT/deploy/cloud-server/episode-budget.env" ]]; then
  # shellcheck disable=SC1090
  source "$ROOT/deploy/cloud-server/episode-budget.env"
fi

if [[ -f "$ROOT/.env.local" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/.env.local"
fi

export GH_TOKEN="${GH_TOKEN:-${GITHUB_PAT:-}}"
# Spring binds rd.github.code-platform.pat-token from GITHUB_PAT / GH_TOKEN; keep both set.
export GITHUB_PAT="${GITHUB_PAT:-${GH_TOKEN:-}}"
# Fail closed: the runtime mutation token must be supplied by the operator. There is no
# public default (see AgentRuntimeMutationAccessPolicy).
if [[ -z "${RD_AGENT_RUNTIME_MUTATION_TOKEN:-}" ]]; then
  echo "ERROR: RD_AGENT_RUNTIME_MUTATION_TOKEN is not set — runtime mutation would be denied." >&2
  exit 1
fi
export RD_AGENT_RUNTIME_MUTATION_TOKEN
export RD_EXECUTOR_PI_CPU_LIMIT="${RD_EXECUTOR_PI_CPU_LIMIT:-1.5}"
export RD_EXECUTOR_PI_MEMORY_LIMIT="${RD_EXECUTOR_PI_MEMORY_LIMIT:-1500m}"
export SERVER_PORT="${SERVER_PORT:-8080}"
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

# NOTE: this script deliberately does NOT touch the developer's global git config
# (no url.*.insteadOf rewrites, no tokenized pushInsteadOf, no global http.* changes).
# Transient-TLS mitigations for GitHub pushes are applied per-invocation by the
# backend itself (see ProcessGitRepairWorkspaceRepository, RULE.md section on
# GitHub TLS retry), never by rewriting the host's global git configuration.

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
