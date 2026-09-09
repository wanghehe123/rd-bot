#!/usr/bin/env bash
# Behavior tests for scripts/rd-bot.sh using a stubbed `docker` CLI.
# Verifies command contract, build order, data-safety rules and env handling
# without touching a real daemon (openspec T07).
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TEST_DIR/../../.." && pwd)"
LAUNCHER="$ROOT/scripts/rd-bot.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
STUB_DIR="$TMP/bin"
STUB_LOG="$TMP/docker-stub.log"
mkdir -p "$STUB_DIR"
touch "$STUB_LOG"
export PATH="$STUB_DIR:$PATH"

pass() { printf 'ok  - %s\n' "$1"; }
fail_test() { printf 'FAIL- %s\n' "$1" >&2; exit 1; }

cat > "$STUB_DIR/docker" <<'STUB'
#!/usr/bin/env bash
LOG="${DOCKER_STUB_LOG:-/dev/null}"
echo "docker $*" >> "$LOG"
case " $* " in
  *" compose version "*) echo "Docker Compose version v2.99.0-test"; exit 0 ;;
  *" info "*) exit 0 ;;
  *" image inspect "*) exit "${DOCKER_STUB_INSPECT:-1}" ;;
  *" build pi-agent-qa"*|*" build pi-agent "*) exit "${DOCKER_STUB_BUILD:-0}" ;;
  *" build "*) exit "${DOCKER_STUB_BUILD:-0}" ;;
  *" compose up "*) exit 0 ;;
  *" down -v"*) exit 0 ;;
  *" compose down "*) exit 0 ;;
  *" compose ps "*) echo "stub-service running"; exit 0 ;;
  *" compose logs "*) exit 0 ;;
  *" compose restart "*) exit 0 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$STUB_DIR/docker"

run_launcher() {
  mkdir -p "$TMP/deploy/docker"
  (cd "$TMP" && DOCKER_STUB_LOG="$STUB_LOG" RD_BOT_ROOT="$TMP" bash "$LAUNCHER" "$@")
}

# --- up: generates env, builds in order Pi -> QA -> app, prints admin URL ----
OUT="$(run_launcher up)"
grep -qE "compose .* build pi-agent$" "$STUB_LOG" || fail_test "up must build the Pi image"
ORDER="$(awk '/compose .* build/{print $NF}' "$STUB_LOG" | tr '\n' ' ')"
echo "$ORDER" | grep -q "^pi-agent pi-agent-qa\|pi-agent pi-agent-qa" || fail_test "build order must be Pi -> QA (got: $ORDER)"
grep -qE "^docker build " "$STUB_LOG" || fail_test "up must build the application image"
grep -qE "docker compose .* up -d --wait" "$STUB_LOG" || fail_test "up must wait for stack health"
grep -q "admin UI: http://127.0.0.1:" <<<"$OUT" || fail_test "up must print the loopback admin URL"
[ -f "$TMP/deploy/docker/runtime.env" ] || fail_test "up must generate deploy/docker/runtime.env"
grep -qx 'GITHUB_PAT=' "$TMP/deploy/docker/runtime.env" || fail_test "generated runtime.env must expose an empty GITHUB_PAT slot"
PERMS="$(stat -f '%Lp' "$TMP/deploy/docker/runtime.env" 2>/dev/null || stat -c '%a' "$TMP/deploy/docker/runtime.env")"
[ "$PERMS" = "600" ] || fail_test "runtime.env must be 0600 (got $PERMS)"
pass "up generates 0600 env, builds Pi -> QA -> app, waits health, prints loopback URL"

ENV_BEFORE="$(cat "$TMP/deploy/docker/runtime.env")"
run_launcher up >/dev/null
ENV_AFTER="$(cat "$TMP/deploy/docker/runtime.env")"
[ "$ENV_BEFORE" = "$ENV_AFTER" ] || fail_test "repeated up must not reset secrets"
pass "repeated up keeps the generated env unchanged"

# --- down: no -v ever --------------------------------------------------------
: > "$STUB_LOG"
run_launcher down >/dev/null
grep -qE "compose .* down -v" "$STUB_LOG" && fail_test "down must not delete volumes (-v)"
grep -qE "^docker compose .* down$" "$STUB_LOG" || fail_test "down must run compose down"
pass "down never deletes volumes"

# --- purge: requires --yes ---------------------------------------------------
: > "$STUB_LOG"
if run_launcher purge >/dev/null 2>&1; then
  fail_test "purge without --yes must fail"
fi
[ -s "$STUB_LOG" ] && grep -q "down" "$STUB_LOG" && fail_test "purge without --yes must not touch the stack"
pass "purge without --yes is rejected before any destructive action"

# A tampered env must not expand purge beyond this checkout's .rd-bot-data.
cp "$TMP/deploy/docker/runtime.env" "$TMP/deploy/docker/runtime.env.safe"
OUTSIDE_WORKSPACE="$TMP-outside-workspace"
mkdir -p "$OUTSIDE_WORKSPACE"
touch "$OUTSIDE_WORKSPACE/keep.txt"
printf '\nRD_BOT_WORKSPACE_ROOT=%s\n' "$OUTSIDE_WORKSPACE" >> "$TMP/deploy/docker/runtime.env"
: > "$STUB_LOG"
if run_launcher purge --yes >/dev/null 2>&1; then
  fail_test "purge must reject a workspace outside .rd-bot-data"
fi
[ -f "$OUTSIDE_WORKSPACE/keep.txt" ] || fail_test "rejected purge must preserve the external workspace"
[ ! -s "$STUB_LOG" ] || fail_test "rejected purge must fail before touching the Compose project"
mv "$TMP/deploy/docker/runtime.env.safe" "$TMP/deploy/docker/runtime.env"
pass "purge rejects an external workspace before any destructive action"

mkdir -p "$TMP/.rd-bot-data/workspaces" && touch "$TMP/.rd-bot-data/workspaces/keep.txt"
run_launcher purge --yes >/dev/null
grep -qE "compose .* down -v" "$STUB_LOG" || fail_test "purge --yes must delete this project's volumes"
[ ! -e "$TMP/.rd-bot-data" ] || fail_test "purge --yes must remove .rd-bot-data"
pass "purge --yes deletes project volumes and .rd-bot-data only"

# --- build failure surfaces non-zero with diagnostics ------------------------
: > "$STUB_LOG"
if DOCKER_STUB_BUILD=1 run_launcher up >/dev/null 2>&1; then
  fail_test "build failure must exit non-zero"
fi
pass "image build failure exits non-zero"

# --- launcher never mutates global git config --------------------------------
if grep -q "git config --global" "$LAUNCHER"; then
  fail_test "launcher must not contain git config --global"
fi
pass "launcher performs no global git configuration"

echo "launcher tests: all passed"
