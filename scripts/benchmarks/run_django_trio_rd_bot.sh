#!/usr/bin/env bash
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
for inst in django__django-11001 django__django-10914 django__django-10924; do
  NUM="${inst##*-}"
  echo "=== [rd-bot] $inst start $(date -u +%H:%M:%S) ==="
  # kill existing backend
  PID=$(lsof -nP -iTCP:18080 -sTCP:LISTEN -t 2>/dev/null); [ -n "$PID" ] && kill $PID 2>/dev/null
  sleep 6
  # start backend for this instance
  nohup "./scripts/swebench/start-rd-bot-private-django-$NUM.sh" > "logs/rd-bot-django-$NUM.log" 2>&1 &
  for i in $(seq 1 30); do sleep 5; curl -sf http://localhost:18080/admin/rd-tasks/7486814737628532736 >/dev/null 2>&1 && break; done
  echo "backend up for $inst"
  # run rd-bot arm
  ./scripts/benchmarks/run_paired_instance.sh --instance "$inst" --arm rd-bot
  echo "=== [rd-bot] $inst done  $(date -u +%H:%M:%S) ==="
done
echo "TRIO_RD_BOT_DONE"
