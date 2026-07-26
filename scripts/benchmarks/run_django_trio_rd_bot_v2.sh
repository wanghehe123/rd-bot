#!/usr/bin/env bash
# django 三题 rd-bot 臂串行编排 v2：每题独占后端，任务终态后才切下一题。
# 顺序 10924 -> 10914 -> 11001（当前后端已是 10924 allowlist，省一次重启）
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
LOG=logs/trio-rd-bot-v2.log

ensure_backend(){
  local NUM="$1"
  # 已有正确 allowlist 的后端则复用
  local pid cur
  pid=$(lsof -nP -iTCP:18080 -sTCP:LISTEN -t 2>/dev/null || true)
  if [ -n "$pid" ]; then
    cur=$(ps eww "$pid" | tr ' ' '\n' | grep '^RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY=' | cut -d= -f2)
    if [ "$cur" = "wanghehe123/swebench-lite-django-$NUM" ]; then echo "backend reuse for $NUM" >> "$LOG"; return 0; fi
    kill "$pid" 2>/dev/null; sleep 8
  fi
  nohup "./scripts/swebench/start-rd-bot-private-django-$NUM.sh" > "logs/rd-bot-django-$NUM.log" 2>&1 &
  for i in $(seq 1 36); do sleep 5; curl -sf -o /dev/null http://localhost:18080/admin/rd-tasks/7486814737628532736 && return 0; done
  echo "backend failed to start for $NUM" >> "$LOG"; return 1
}

run_one(){
  local inst="$1" NUM="${inst##*-}"
  local out="qa-runs/benchmarks-metrics/e1/$inst/rd-bot"
  mkdir -p "$out"
  echo "=== [$inst] start $(date -u +%H:%M:%S) ===" >> "$LOG"
  ensure_backend "$NUM" || return 1
  local task_id
  task_id=$(INST="$inst" python3 - <<'PY'
import json,os,urllib.request
inst=os.environ["INST"]; base="http://localhost:18080"
plan=json.load(open("qa-runs/swebench-lite-10/run-plan.json"))
t=next(x for x in plan["tasks"] if x["instance_id"]==inst)
pp=open(t["prompt_paths"]["rd_bot"]["path"]).read()
crit=json.load(open(f"qa-runs/swebench-lite-10/acceptance/{inst}.json"))
payload={"projectId":t["project"]["project_id"],"title":f"SWE-bench {inst}","priority":"P1",
 "expectedResult":"按需求材料产出可应用补丁并通过回归测试","acceptanceCriteria":crit,
 "materials":[{"sourceType":"MANUAL_TEXT","title":f"swebench-{inst}-prompt","content":pp}],"autoExecute":True}
req=urllib.request.Request(base+"/admin/rd-tasks/requirements", data=json.dumps(payload).encode(),
 headers={"Content-Type":"application/json"}, method="POST")
print(json.load(urllib.request.urlopen(req,timeout=60))["taskId"])
PY
)
  echo "[$inst] taskId=$task_id" >> "$LOG"
  local final="TIMEOUT" st=""
  for i in $(seq 1 240); do
    st=$(curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" | jq -r '.status' 2>/dev/null)
    case "$st" in COMPLETED|FAILED|FAILED_NEEDS_HUMAN|STOPPED|CANCELLED|REJECTED) final="$st"; break;; esac
    sleep 60
  done
  sleep 30
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id" > "$out/task.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/execution-overview" > "$out/overview.json"
  curl -sf "http://localhost:18080/admin/rd-tasks/$task_id/timeline" > "$out/timeline.json"
  python3 - <<PY
import json
from datetime import datetime,timezone
tl=json.load(open("$out/timeline.json"))
iso=lambda ms: datetime.fromtimestamp(ms/1000,timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump({"instance":"$inst","arm":"rd-bot","taskId":"$task_id",
 "startedAt":iso(tl[0]["enteredAtEpochMillis"]),"finishedAt":iso(tl[-1]["enteredAtEpochMillis"]),
 "finalStatus":"$final","gateHit":False},open("$out/meta.json","w"),ensure_ascii=False,indent=2)
PY
  echo "=== [$inst] done $(date -u +%H:%M:%S) final=$final ===" >> "$LOG"
}

for inst in django__django-10924 django__django-10914 django__django-11001; do
  run_one "$inst"
done
echo "TRIO_V2_DONE" >> "$LOG"
