#!/usr/bin/env python3
"""E1 配对测试 RD-Bot 臂：按 run-plan.json 创建需求任务、提交并轮询到终态。

用法（通常由 run_paired_instance.sh 调用）::

    RD_BOT_BASE_URL=http://localhost:18080 python3 scripts/benchmarks/submit_and_wait_rd_task.py \
        --run-plan qa-runs/swebench-lite-10/run-plan.json \
        --instance django__django-11019 --out-dir qa-runs/benchmarks-metrics/e1/django__django-11019/rd-bot

产物：
    <out-dir>/meta.json   taskId、开始/结束时间戳、终态、是否被门禁拦截
    <out-dir>/task.json   终态任务视图（GET /admin/rd-tasks/{taskId}）

退出码：0 终态 COMPLETED；1 其他终态（FAILED/STOPPED/超时）；2 参数错误；3 提示词校验失败。
"""

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

TERMINAL_STATUSES = {"COMPLETED", "FAILED", "STOPPED", "CANCELLED"}
# 门禁态：不算终态，但要单独记录（统计门禁拦截率用）
GATE_STATUSES = {"WAITING_APPROVAL"}


def api(base_url: str, path: str, payload=None):
    url = base_url.rstrip("/") + path
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"},
        method="POST" if payload is not None else "GET",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode())


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-plan", required=True)
    parser.add_argument("--instance", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    base_url = os.environ.get("RD_BOT_BASE_URL", "http://localhost:18080")
    poll_interval = int(os.environ.get("POLL_INTERVAL_SECONDS", "30"))
    timeout_minutes = int(os.environ.get("POLL_TIMEOUT_MINUTES", "90"))

    plan = json.loads(Path(args.run_plan).read_text())
    task_plan = next((t for t in plan["tasks"] if t["instance_id"] == args.instance), None)
    if task_plan is None:
        print(f"instance {args.instance} not found in {args.run_plan}", file=sys.stderr)
        return 2

    prompt_path = Path(task_plan["prompt_paths"]["rd_bot"]["path"])
    prompt_text = prompt_path.read_text()
    actual_sha = hashlib.sha256(prompt_text.encode()).hexdigest()
    expected_sha = task_plan["prompt_paths"]["rd_bot"]["sha256"]
    if actual_sha != expected_sha:
        print(f"prompt checksum mismatch: {prompt_path}", file=sys.stderr)
        return 3

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    started_at = now_iso()
    created = api(base_url, "/admin/rd-tasks/requirements", {
        "projectId": task_plan["project"]["project_id"],
        "title": f"SWE-bench {args.instance}",
        "priority": "P1",
        "expectedResult": "按需求材料产出可应用补丁并通过回归测试",
        "acceptanceCriteria": ["补丁可 git apply", "相关回归测试通过", "QA 产出结构化验收证据"],
        "materials": [{
            "sourceType": "MANUAL_TEXT",
            "title": f"swebench-{args.instance}-prompt",
            "content": prompt_text,
        }],
        "autoExecute": True,
    })
    task_id = created["taskId"]
    print(f"taskId={task_id} status={created.get('status')}")

    deadline = time.time() + timeout_minutes * 60
    gate_hit = created.get("status") in GATE_STATUSES
    final_view = created
    outcome = "TIMEOUT"
    while time.time() < deadline:
        final_view = api(base_url, f"/admin/rd-tasks/{task_id}")
        status = final_view.get("status")
        if status in GATE_STATUSES:
            gate_hit = True  # 记录但不自动放行：门禁拦截本身是被统计对象
        if status in TERMINAL_STATUSES:
            outcome = status
            break
        time.sleep(poll_interval)
    finished_at = now_iso()

    (out_dir / "task.json").write_text(json.dumps(final_view, ensure_ascii=False, indent=2))
    (out_dir / "meta.json").write_text(json.dumps({
        "instance": args.instance,
        "arm": "rd-bot",
        "taskId": task_id,
        "startedAt": started_at,
        "finishedAt": finished_at,
        "finalStatus": outcome,
        "gateHit": gate_hit,
        "promptSha256": actual_sha,
        "pollIntervalSeconds": poll_interval,
        "timeoutMinutes": timeout_minutes,
    }, ensure_ascii=False, indent=2))
    print(f"final={outcome} gateHit={gate_hit}")
    return 0 if outcome == "COMPLETED" else 1


if __name__ == "__main__":
    sys.exit(main())
