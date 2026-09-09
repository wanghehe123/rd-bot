#!/usr/bin/env bash
# Behavior test: an acceptance run must never overwrite the operator's normal
# deploy/docker/runtime.env, even when the smoke fails after setup.
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_DIR/../../.." && pwd)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/scripts/docker" "$TMP/deploy/docker" "$TMP/bin"
cp "$SOURCE_ROOT/scripts/docker/acceptance.sh" "$TMP/scripts/docker/acceptance.sh"
printf 'operator-secret-must-survive\n' > "$TMP/deploy/docker/runtime.env"
cp "$TMP/deploy/docker/runtime.env" "$TMP/deploy/docker/runtime.env.before"

cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "${DOCKER_STUB_LOG:?}"
case " $* " in
  *" image inspect "*) exit 0 ;;
  *" compose "*" up -d --wait "*) exit 42 ;;
  *" compose "*" down -v "*) exit 0 ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$TMP/bin/docker"

STUB_LOG="$TMP/docker.log"
set +e
PATH="$TMP/bin:$PATH" DOCKER_STUB_LOG="$STUB_LOG" \
  ACCEPTANCE_TAG=isolation-test ACCEPTANCE_PROJECT=isolation-test \
  bash "$TMP/scripts/docker/acceptance.sh" >/dev/null 2>&1
RC=$?
set -e

[ "$RC" -ne 0 ] || { echo "FAIL- smoke fixture must stop at the stubbed startup failure" >&2; exit 1; }
cmp -s "$TMP/deploy/docker/runtime.env.before" "$TMP/deploy/docker/runtime.env" || {
  echo "FAIL- acceptance smoke overwrote deploy/docker/runtime.env" >&2
  exit 1
}

ENV_ARG="$(sed -n 's/^docker compose --env-file \([^ ]*\) .*/\1/p' "$STUB_LOG" | head -1)"
case "$ENV_ARG" in
  "$TMP/.rd-bot-data/acceptance/"*/runtime.env) ;;
  *) echo "FAIL- acceptance smoke did not use a run-local env (got: $ENV_ARG)" >&2; exit 1 ;;
esac

echo "ok  - acceptance smoke preserves deploy runtime.env and uses a run-local env"
