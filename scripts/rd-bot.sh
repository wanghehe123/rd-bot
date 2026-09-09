#!/usr/bin/env bash
# RD-Bot single-machine Docker operations (supported entry point).
#
#   ./scripts/rd-bot.sh doctor          # read-only environment check
#   ./scripts/rd-bot.sh up              # generate env on first run, build + start
#   ./scripts/rd-bot.sh status          # services, health, admin address
#   ./scripts/rd-bot.sh logs [service]  # follow logs
#   ./scripts/rd-bot.sh restart         # restart, keeps all data
#   ./scripts/rd-bot.sh down            # stop, keeps volumes and workspaces
#   ./scripts/rd-bot.sh purge --yes     # delete THIS project's volumes + .rd-bot-data
#
# Data safety: `down` never deletes volumes; destructive cleanup requires the
# explicit `purge --yes` form and only touches this Compose project's resources.
set -euo pipefail

# Repo root: resolved from this script's location; RD_BOT_ROOT overrides (tests,
# relocated checkouts). Compose files always live under <root>/deploy + <root>.
ROOT="${RD_BOT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ENV_FILE="$ROOT/deploy/docker/runtime.env"
COMPOSE_FILE="$ROOT/docker-compose.yml"
WORKSPACE_ROOT_DEFAULT="$ROOT/.rd-bot-data/workspaces"

log()  { printf '[rd-bot] %s\n' "$*"; }
fail() { printf '[rd-bot] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  grep '^#   \./scripts' "$0" | sed 's/^# *//'
  exit "${1:-0}"
}

command_exists() { command -v "$1" >/dev/null 2>&1; }

random_hex() {
  openssl rand -hex 24 2>/dev/null || head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

detect_docker_gid() {
  case "$(uname -s)" in
    # Docker Desktop (macOS/Windows): the socket inside containers is root:root 660.
    Darwin|MINGW*|MSYS*|CYGWIN*) echo "0" ;;
    *)
      if [ -S /var/run/docker.sock ]; then
        stat -c '%g' /var/run/docker.sock 2>/dev/null || echo "999"
      else
        echo "999"
      fi
      ;;
  esac
}

generate_env() {
  cat > "$ENV_FILE" <<EOF
COMPOSE_PROJECT_NAME=rd-bot
RD_BOT_IMAGE_TAG=oss-test
RD_BOT_APP_IMAGE=rd-bot/app
RD_BOT_PORT=18080
DOCKER_GID=$(detect_docker_gid)
RD_BOT_WORKSPACE_ROOT=$WORKSPACE_ROOT_DEFAULT
RD_BOT_EGRESS_NETWORK=rd-bot-egress
POSTGRES_USERNAME=postgres
POSTGRES_PASSWORD=$(random_hex)
RUSTFS_ACCESS_KEY_ID=rdbotminio
RUSTFS_SECRET_ACCESS_KEY=$(random_hex)
RUSTFS_BUCKET=biz
RD_AGENT_RUNTIME_MUTATION_TOKEN=$(random_hex)
GITHUB_PAT=
RD_EXECUTOR_PI_CPU_LIMIT=4
RD_EXECUTOR_PI_MEMORY_LIMIT=8g
EOF
  chmod 600 "$ENV_FILE"
  log "generated $ENV_FILE (0600) with random secrets"
}

load_env() {
  local create_workspace="${1:-yes}"
  [ -f "$ENV_FILE" ] || generate_env
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
  export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-rd-bot}"
  export RD_BOT_WORKSPACE_ROOT="${RD_BOT_WORKSPACE_ROOT:-$WORKSPACE_ROOT_DEFAULT}"
  [ "$create_workspace" = "no" ] || mkdir -p "$RD_BOT_WORKSPACE_ROOT"
  export RD_BOT_WORKSPACE_ROOT
}

validate_workspace_root_for_purge() {
  local data_root="$ROOT/.rd-bot-data"
  case "$RD_BOT_WORKSPACE_ROOT" in
    "$data_root"|"$data_root"/*) ;;
    *) fail "refusing purge: RD_BOT_WORKSPACE_ROOT must stay under $data_root (got $RD_BOT_WORKSPACE_ROOT)" ;;
  esac
  case "$RD_BOT_WORKSPACE_ROOT" in
    *"/../"*|*"/.."|*"/./"*|*"/.")
      fail "refusing purge: RD_BOT_WORKSPACE_ROOT must not contain '.' or '..' path segments"
      ;;
  esac
  if [ -d "$data_root" ] && [ -e "$RD_BOT_WORKSPACE_ROOT" ]; then
    local data_root_real workspace_root_real
    data_root_real="$(cd "$data_root" && pwd -P)"
    workspace_root_real="$(cd "$RD_BOT_WORKSPACE_ROOT" && pwd -P)"
    case "$workspace_root_real" in
      "$data_root_real"|"$data_root_real"/*) ;;
      *) fail "refusing purge: resolved workspace path escapes $data_root_real" ;;
    esac
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

preflight() {
  command_exists docker || fail "docker CLI not found. Install Docker Engine/Desktop with Compose v2: https://docs.docker.com/get-docker/"
  docker compose version >/dev/null 2>&1 || fail "docker compose v2 not available. Update Docker Desktop or install the compose plugin."
  docker info >/dev/null 2>&1 || fail "Docker daemon is not running. Start Docker Desktop / the docker service and retry."
}

cmd_doctor() {
  local problems=0
  say_env() { printf '[doctor] %-28s %s\n' "$1" "$2"; }
  say_env "repo root" "$ROOT"
  if command_exists docker; then
    say_env "docker cli" "$(docker --version 2>/dev/null | head -1)"
  else
    say_env "docker cli" "MISSING"; problems=$((problems+1))
  fi
  if docker compose version >/dev/null 2>&1; then
    say_env "compose" "$(docker compose version --short 2>/dev/null)"
  else
    say_env "compose" "MISSING (need v2)"; problems=$((problems+1))
  fi
  if docker info >/dev/null 2>&1; then
    say_env "daemon" "running ($(docker info --format '{{.ServerVersion}} {{.Architecture}}' 2>/dev/null))"
  else
    say_env "daemon" "NOT RUNNING"; problems=$((problems+1))
  fi
  if [ -S /var/run/docker.sock ]; then
    say_env "docker socket" "present (gid $(detect_docker_gid))"
    if [ -r /var/run/docker.sock ] && [ -w /var/run/docker.sock ]; then
      say_env "socket access" "read/write ok"
    else
      say_env "socket access" "no rw access — add your user to the socket group (Linux: 'newgrp docker' after usermod -aG docker), do NOT chmod 666"
      problems=$((problems+1))
    fi
  else
    say_env "docker socket" "not at /var/run/docker.sock (Docker Desktop exposes it via the VM)"
  fi
  local port="${RD_BOT_PORT:-18080}"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    say_env "port $port" "IN USE — stop the occupier or set RD_BOT_PORT"; problems=$((problems+1))
  else
    say_env "port $port" "free"
  fi
  if [ -d "$ROOT/.rd-bot-data" ] || mkdir -p "$ROOT/.rd-bot-data" 2>/dev/null; then
    if [ -w "$ROOT/.rd-bot-data" ]; then say_env "workspace dir" "writable"
    else say_env "workspace dir" "NOT writable"; problems=$((problems+1)); fi
  fi
  local avail_kb cpu_count mem_gb
  avail_kb=$(df -k "$ROOT" | awk 'NR==2 {print $4}')
  cpu_count=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo "unknown")
  mem_gb=$( (sysctl -n hw.memsize 2>/dev/null || echo 0) | awk '{printf "%d", $1/1024/1024/1024}' )
  say_env "disk available" "$((avail_kb / 1024 / 1024)) GiB on $ROOT"
  say_env "cpus" "$cpu_count"
  say_env "host memory" "${mem_gb} GiB"
  say_env "measured limits" "image build+stack need roughly 20 GiB disk; RD_EXECUTOR_PI_CPU_LIMIT must be <= host cpus"
  if [ "$problems" -gt 0 ]; then
    fail "doctor found $problems problem(s); fix them and re-run"
  fi
  log "doctor: all checks passed"
}

build_images() {
  log "building Pi agent image (rd-bot/pi-agent:${RD_BOT_IMAGE_TAG:-oss-test})..."
  compose --profile build build pi-agent || fail "Pi image build failed; run './scripts/rd-bot.sh doctor' and check the log above"
  log "building QA agent image (rd-bot/pi-agent-qa:${RD_BOT_IMAGE_TAG:-oss-test}) from the Pi base..."
  compose --profile build build pi-agent-qa || fail "QA image build failed; if the Playwright CDN was flaky retry, or seed BROWSER_CACHE_IMAGE from a previous QA tag"
  if docker image inspect "${RD_BOT_APP_IMAGE:-rd-bot/app}:${RD_BOT_IMAGE_TAG:-oss-test}" >/dev/null 2>&1; then
    log "application image ${RD_BOT_APP_IMAGE:-rd-bot/app}:${RD_BOT_IMAGE_TAG:-oss-test} already present (pass RD_BOT_IMAGE_TAG to force a fresh build)"
  else
    log "building application image (frontend + backend + runtime)..."
    docker build --build-arg VCS_REF="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)" \
      -t "${RD_BOT_APP_IMAGE:-rd-bot/app}:${RD_BOT_IMAGE_TAG:-oss-test}" "$ROOT" \
      || fail "application image build failed; check the log above"
  fi
}

cmd_up() {
  preflight
  load_env
  build_images
  log "starting stack (project: ${COMPOSE_PROJECT_NAME})..."
  compose up -d --wait || fail "stack did not become healthy; run './scripts/rd-bot.sh logs' for details"
  local missing=0
  [ -n "${RD_AGENT_RUNTIME_MUTATION_TOKEN:-}" ] || { log "NOTE: RD_AGENT_RUNTIME_MUTATION_TOKEN empty — runtime mutations stay denied"; missing=1; }
  [ -n "${GITHUB_PAT:-}" ] || { log "NOTE: GITHUB_PAT empty — private-repository clone/push and PR publication are unavailable; set it in $ENV_FILE, then restart"; missing=1; }
  log "NOTE: configure model-provider API keys in the admin UI before running agents"
  [ "$missing" -eq 1 ] && log "complete the credential steps above before running a delivery that needs them"
  log "admin UI: http://127.0.0.1:${RD_BOT_PORT:-18080}/admin"
}

cmd_status() {
  load_env
  compose ps
  log "admin UI: http://127.0.0.1:${RD_BOT_PORT:-18080}/admin"
}

cmd_logs() {
  load_env
  if [ "${1:-}" != "" ]; then
    compose logs -f "$1"
  else
    compose logs -f --tail 100
  fi
}

cmd_restart() {
  load_env
  compose restart
  compose up -d --wait
  log "restart complete; data preserved"
}

cmd_down() {
  load_env
  compose down
  log "stack stopped; volumes and workspaces are preserved (purge deletes them)"
}

cmd_purge() {
  [ "${1:-}" = "--yes" ] || usage 1
  load_env no
  validate_workspace_root_for_purge
  log "purging project '${COMPOSE_PROJECT_NAME}': its volumes AND $ROOT/.rd-bot-data will be deleted"
  compose down -v --remove-orphans
  rm -rf "$ROOT/.rd-bot-data"
  log "purge complete"
}

case "${1:-}" in
  doctor)  shift; cmd_doctor "$@" ;;
  up)      shift; cmd_up "$@" ;;
  status)  shift; cmd_status "$@" ;;
  logs)    shift; cmd_logs "$@" ;;
  restart) shift; cmd_restart "$@" ;;
  down)    shift; cmd_down "$@" ;;
  purge)   shift; cmd_purge "$@" ;;
  help|-h|--help) usage 0 ;;
  *) log "unknown command: ${1:-}"; usage 1 ;;
esac
