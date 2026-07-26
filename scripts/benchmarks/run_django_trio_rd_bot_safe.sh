#!/usr/bin/env bash
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

run_one(){
  local inst="$1" NUM="${inst##*-}"
  local out="qa-runs/benchmarks-metrics/e1/$inst/rd-bot"
  mkdir -p "$out"
  echo "=== [rd-bot] $inst start $(date -u +%H:%M:%S) ===" | tee -a logs/trio-rd-bot.log
  # kill existing backend
  local pid=$(lsof -nP -iTCP:18080 -sTCP:LISTEN -t 2>/dev/null)
  [ -n "$pid" ] && { kill $pid 2>/dev/null; sleep 6; }
  # start backend for this instance
  nohup "./scripts/swebench/start-rd-bot-private-django-$NUM.sh" > "logs/rd-bot-django-$NUM.log" 2>&1 &
  for i in $(seq 1 30); do sleep 5; curl -sf http://localhost:18080/admin/rd-tasks/7486814737628532736 >/dev/null 2>&1 && break; done
  echo "backend up for $inst" | tee -a logs/trio-rd-bot.log
  # create task and capture taskId immediately (avoid submit script buffering hang)
  local task_id
  task_id=$(python3 - <<PY
import json,urllib.request
base="http://localhost:18080"
plan=json.load(open("qa-runs/swebench-lite-10/run-plan.json"))
t=next(x for x in plan["tasks"] if x["instance_id"]=="$inst")
pp=open(t["prompt_paths"]["rd_bot"]["path"]).read()
crit=json.load(open("qa-runs/swebench-lite-10/acceptance/$inst.json"))
payload={"projectId":t["project"]["project_id"],"title":f"SWE-bench $inst","priority":"P1","expectedResult":"按需求材料产出可应用补丁并通过回归测试","acceptanceCriteria":crit,"materials":[{"sourceType":"MANUAL_TEXT","title":"swebench-$inst-prompt","content":pp}],"autoExecute":True}
req=urllib.request.Request(base+"/admin/rd-tasks/requirements", data=json.dumps(payload).encode(), headers={"Content-Type":"application/json"}, method="POST")
print(json.load(urllib.request.urlopen(req,timeout=60))["taskId"])
PY
)
  echo "taskId=$task_id" | tee -a logs/trio-rd-bot.log
  # poll to terminal
  local final="TIMEOUT"
  for i in $(seq 1 240); do
    local st
    st=$(curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" | jq -r '.status' 2>/dev/null)
    case "$st" in COMPLETED|FAILED|FAILED_NEEDS_HUMAN|STOPPED|CANCELLED|REJECTED) final="$st"; break;; esac
    sleep 60
  done
  sleep 30
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" > "$out/task.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/execution-overview" > "$out/overview.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/timeline" > "$out/timeline.json"
  echo "=== [rd-bot] $inst done $(date -u +%H:%M:%S) final=$final ===" | tee -a logs/trio-rd-bot.log
}

for inst in django__django-10914 django__django-10924; do
  run_one "$inst"
done
echo "TRIO_RD_BOT_SAFE_DONE" | tee -a logs/trio-rd-bot.log
