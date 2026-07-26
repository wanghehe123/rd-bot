#!/usr/bin/env bash
# 串行跑 django 三题的 claude-code 臂（rd-bot 臂私有仓库另行搭建）
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
for inst in django__django-11001 django__django-10914 django__django-10924; do
  echo "=== [claude-code] $inst start $(date -u +%H:%M:%S) ==="
  ./scripts/benchmarks/run_paired_instance.sh --instance "$inst" --arm claude-code
  echo "=== [claude-code] $inst done  $(date -u +%H:%M:%S) ==="
done
echo "TRIO_CLAUDE_DONE"
