#!/usr/bin/env bash
# Read-only environment doctor. Thin delegate so both entry points work;
# the implementation lives in scripts/rd-bot.sh (single source of truth).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$ROOT/scripts/rd-bot.sh" doctor "$@"
