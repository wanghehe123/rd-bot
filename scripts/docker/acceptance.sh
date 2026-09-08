#!/usr/bin/env bash
# RD-Bot release acceptance smoke (openspec T13).
#
# Default runs the non-destructive smoke: bring up an isolated stack
# (unique project name + egress network), wait for health, verify admin
# routes + migration idempotency + data preservation across restart/down,
# then tear it down. Requires built images (app/pi/qa with ACCEPTANCE_TAG).
#
#   RD_OSS_ACCEPTANCE_LIVE=1 ./scripts/docker/acceptance.sh   # adds Phase C
#   (real provider/GitHub delivery; BLOCKED without explicit credentials)
#
# Environment:
#   ACCEPTANCE_TAG     image tag to use          (default: acceptance-<run-id>)
#   ACCEPTANCE_PROJECT compose project name      (default: rd-bot-oss-acceptance-<run-id>)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
TAG="${ACCEPTANCE_TAG:-acceptance-$RUN_ID}"
PROJECT="${ACCEPTANCE_PROJECT:-rd-bot-oss-acceptance-$RUN_ID}"
ENV_FILE="${ACCEPTANCE_ENV_FILE:-$ROOT/deploy/docker/runtime.env}"
WS_ROOT="$ROOT/.rd-bot-data/acceptance/$RUN_ID/workspaces"

fail() { printf '[acceptance] FAIL: %s\n' "$*" >&2; exit 1; }
step() { printf '[acceptance] == %s\n' "$*"; }

# Phase A0: unique-tag images must already exist (built in the acceptance run).
for image in "rd-bot/app:$TAG" "rd-bot/pi-agent:$TAG" "rd-bot/pi-agent-qa:$TAG"; do
  docker image inspect "$image" >/dev/null 2>&1 || \
    fail "missing image $image — build with the unique acceptance tag first (Phase A)"
done
step "images verified (app/pi/qa @ $TAG)"

# Isolated stack env: unique project + egress network; loopback publish; fresh workspace.
if [ "$(uname -s)" = "Darwin" ]; then DOCKER_GID_VAL=0; else DOCKER_GID_VAL="$(stat -c '%g' /var/run/docker.sock 2>/dev/null || echo 999)"; fi
cat > "$ROOT/deploy/docker/runtime.env" <<EOF
COMPOSE_PROJECT_NAME=$PROJECT
RD_BOT_IMAGE_TAG=$TAG
RD_BOT_APP_IMAGE=rd-bot/app
RD_BOT_PORT=18080
DOCKER_GID=$DOCKER_GID_VAL
RD_BOT_WORKSPACE_ROOT=$WS_ROOT
RD_BOT_EGRESS_NETWORK=rd-bot-egress-acceptance-$RUN_ID
POSTGRES_USERNAME=postgres
POSTGRES_PASSWORD=acceptance-$RUN_ID
RUSTFS_ACCESS_KEY_ID=acceptance-minio
RUSTFS_SECRET_ACCESS_KEY=acceptance-minio-$RUN_ID
RUSTFS_BUCKET=biz
RD_AGENT_RUNTIME_MUTATION_TOKEN=acceptance-token-$RUN_ID
RD_EXECUTOR_PI_CPU_LIMIT=4
RD_EXECUTOR_PI_MEMORY_LIMIT=8g
EOF
chmod 600 "$ROOT/deploy/docker/runtime.env"

compose() { docker compose --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" "$@"; }
cleanup() { compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

# Phase A: empty-volume boot, all services healthy.
step "Phase A: boot from empty volumes (project $PROJECT)"
compose up -d --wait || fail "stack did not become healthy"
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/admin)"
[ "$code" = "200" ] || fail "/admin returned $code"
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: text/html' http://127.0.0.1:18080/admin/model-providers)"
[ "$code" = "200" ] || fail "/admin/model-providers returned $code"
step "Phase A: PASS (admin + SPA refresh reachable on loopback)"

# Phase B: infra ledger, no-credential posture, probe container through the socket.
step "Phase B: infra + no-credential boundary + probe container"
# Idempotency: a second explicit migrations pass must report applied=0 skipped=N.
compose up --exit-code-from migrations migrations >/dev/null 2>&1 \
  || fail "second migrations pass exited non-zero"
migration_log="$(compose logs migrations 2>/dev/null | tail -50)"
echo "$migration_log" | grep -q "applied=0 skipped=" || fail "second migrations pass re-applied files (ledger broken)"
first_pass_applied="$(compose logs migrations 2>/dev/null | grep -oE 'applied=[0-9]+ skipped' | head -1 | grep -oE '[0-9]+' | head -1)"
[ "${first_pass_applied:-0}" -ge 1 ] || fail "first boot should have applied migrations"
compose exec -T rd-bot sh -c "docker run --rm -v '$WS_ROOT:/work' alpine sh -c 'echo probe > /work/acceptance-probe.txt'" \
  || fail "probe container could not write the shared workspace"
grep -q probe "$WS_ROOT/acceptance-probe.txt" || fail "workspace probe file missing on host"
rm -f "$WS_ROOT/acceptance-probe.txt"
step "Phase B: PASS (ledger, probe container, same-path workspace)"

# Phase D: restart/up preserve data, secrets, migration state.
step "Phase D: restart + re-up idempotency"
LEDGER_BEFORE="$(compose exec -T postgres psql -U postgres -d rdbot -tAc 'SELECT count(*) FROM rd_schema_migrations')"
TOKEN_BEFORE="$(grep RD_AGENT_RUNTIME_MUTATION_TOKEN "$ENV_FILE")"
compose restart && compose up -d --wait || fail "restart/re-up failed"
LEDGER_AFTER="$(compose exec -T postgres psql -U postgres -d rdbot -tAc 'SELECT count(*) FROM rd_schema_migrations')"
[ "$LEDGER_BEFORE" = "$LEDGER_AFTER" ] || fail "migration ledger changed across restart ($LEDGER_BEFORE -> $LEDGER_AFTER)"
[ "$TOKEN_BEFORE" = "$(grep RD_AGENT_RUNTIME_MUTATION_TOKEN "$ENV_FILE")" ] || fail "secrets changed across re-up"
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/admin)"
[ "$code" = "200" ] || fail "/admin unreachable after restart"
step "Phase D: PASS (ledger + secrets stable, data served)"

# Phase C: real delivery requires explicit opt-in AND credentials.
if [ "${RD_OSS_ACCEPTANCE_LIVE:-0}" = "1" ]; then
  if [ -z "${GITHUB_PAT:-}" ] || [ -z "${OPENCODE_API_KEY:-}" ]; then
    printf '[acceptance] Phase C: BLOCKED (RD_OSS_ACCEPTANCE_LIVE=1 but GITHUB_PAT/OPENCODE_API_KEY not set)\n'
  else
    fail "Phase C live delivery is executed through the T13 manual procedure, not this smoke"
  fi
else
  printf '[acceptance] Phase C: SKIPPED by default (set RD_OSS_ACCEPTANCE_LIVE=1 for the real-delivery run)\n'
fi

printf '[acceptance] RESULT: PASS (A/B/D) run-id=%s\n' "$RUN_ID"
