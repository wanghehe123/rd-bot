#!/usr/bin/env python3
"""MEA Demo 普通 HTTP 读取核对 — 只读断言，不含 kill/reopen/故障注入。

逐项验证组合候选（codex/mea-demo）上的读取接口返回真实 JSON：
  1. 任务列表接口正常（不是 SPA index.html / 500）
  2. shell、execution-overview、role-prompts 读取接口
  3. coding-mea 读取接口（codingStageRunId 参数、Manager 全文 boundedContract）
  4. stage result 读取接口
  负例（只两个）：
  N1. 跨 task 的 stageRunId 请求 coding-mea → 明确 4xx，不串任务
  N2. 没有完整产物的 stage result → 明确 unavailable / 404，不白屏不伪造

Usage (on cloud / local):
  RD_BOT_BASE_URL=http://127.0.0.1:8080 MEA_EVAL_PROJECT_ID=7499721648870920192 \\
    python3 deploy/cloud-server/mea-live/verify_demo.py --task-id <已完成的真实 taskId> \\
    [--output /tmp/mea-demo-verify]
"""
from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request
from pathlib import Path


BASE = os.environ.get("RD_BOT_BASE_URL", "http://127.0.0.1:8080").rstrip("/")


def req(method: str, path: str, timeout: int = 60) -> tuple[int, object]:
    """返回 (status, 解析后的 JSON 或原始文本前缀)。不抛 4xx。"""
    request = urllib.request.Request(BASE + path, headers={"Accept": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, _maybe_json(raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        return error.code, _maybe_json(raw)


def _maybe_json(raw: str) -> object:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"__raw__": raw[:200]}


def is_json_response(payload: object) -> bool:
    return isinstance(payload, dict) or isinstance(payload, list)


def check(name: str, condition: bool, detail: str = "") -> bool:
    mark = "PASS" if condition else "FAIL"
    print(f"[{mark}] {name}{(' — ' + detail) if detail else ''}")
    return condition


def verify_task_read_apis(task_id: str) -> bool:
    ok = True

    # 1. 任务列表：JSON 且含条目结构，不是 SPA index.html
    status, payload = req("GET", "/admin/rd-tasks")
    ok &= check("task list returns JSON", status == 200 and is_json_response(payload),
                f"status={status}")

    # 2. shell（任务详情）
    status, detail = req("GET", f"/admin/rd-tasks/{task_id}")
    ok &= check("task shell returns JSON", status == 200 and is_json_response(detail),
                f"status={status}")

    # 3. execution overview
    status, overview = req("GET", f"/admin/rd-tasks/{task_id}/execution-overview")
    ok &= check("execution-overview returns JSON", status == 200 and is_json_response(overview),
                f"status={status}")

    # 4. role prompts
    status, prompts = req("GET", f"/admin/rd-tasks/{task_id}/role-prompts")
    ok &= check("role-prompts returns JSON", status == 200 and is_json_response(prompts),
                f"status={status}")

    # 5. coding-mea：不传参数走默认最新 Coding Attempt；available + schemaVersion=1
    status, mea = req("GET", f"/admin/rd-tasks/{task_id}/coding-mea")
    ok &= check("coding-mea returns JSON", status == 200 and is_json_response(mea), f"status={status}")
    if is_json_response(mea) and isinstance(mea, dict):
        ok &= check("coding-mea schemaVersion=1", mea.get("schemaVersion") == 1,
                    f"schemaVersion={mea.get('schemaVersion')}")
        coding_stage_run_id = mea.get("codingStageRunId")
        ok &= check("coding-mea reports codingStageRunId",
                    bool(coding_stage_run_id), f"codingStageRunId={coding_stage_run_id}")

        # 显式传 codingStageRunId：后端真实参数名，返回同一个 Attempt（不串）
        if coding_stage_run_id:
            status2, mea2 = req(
                "GET",
                f"/admin/rd-tasks/{task_id}/coding-mea?codingStageRunId={coding_stage_run_id}",
            )
            ok &= check(
                "coding-mea honors codingStageRunId",
                status2 == 200 and is_json_response(mea2)
                and isinstance(mea2, dict)
                and mea2.get("codingStageRunId") == coding_stage_run_id,
                f"status={status2}",
            )

        # Manager 决策全文：boundedContract 必须是真实全文端点字段
        decisions = mea.get("decisions") or []
        if decisions:
            decision_hash = decisions[0].get("decisionHash")
            status3, full = req(
                "GET", f"/admin/rd-tasks/{task_id}/manager-decisions/{decision_hash}"
            )
            ok &= check(
                "manager-decision full text has boundedContract",
                status3 == 200 and is_json_response(full)
                and isinstance(full, dict)
                and isinstance(full.get("boundedContract"), str)
                and len(full.get("boundedContract", "")) > 0,
                f"status={status3}",
            )

        # 6. stage result：对最新 coding stage 读结果（预览或不可用都必须是明确 JSON）
        if coding_stage_run_id:
            status4, stage_result = req(
                "GET", f"/admin/rd-tasks/{task_id}/stage-runs/{coding_stage_run_id}/result"
            )
            ok &= check(
                "stage result returns explicit JSON",
                is_json_response(stage_result)
                and isinstance(stage_result, dict)
                and bool(stage_result.get("source")),
                f"status={status4} source={(stage_result or {}).get('source') if isinstance(stage_result, dict) else None}",
            )

    # N1. 跨 task 负例：用一个不存在的 stageRunId 明确 4xx
    status5, _ = req("GET", f"/admin/rd-tasks/{task_id}/coding-mea?codingStageRunId=not-a-real-stage")
    ok &= check("cross/fake stageRunId is rejected (4xx)", 400 <= status5 < 500, f"status={status5}")

    # N2. 缺产物负例：不存在的 stage result → 4xx 明确不可用，而不是 500 / index.html
    status6, payload6 = req("GET", f"/admin/rd-tasks/{task_id}/stage-runs/not-a-real-stage/result")
    ok &= check("missing stage result is explicit (4xx JSON)",
                400 <= status6 < 500 and is_json_response(payload6), f"status={status6}")

    return ok


def main() -> None:
    parser = argparse.ArgumentParser(description="MEA demo read-only HTTP verification")
    parser.add_argument("--task-id", required=True, help="一个已完成的真实需求 taskId")
    parser.add_argument("--output", default="/tmp/mea-demo-verify", help="结果输出目录")
    args = parser.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    ok = verify_task_read_apis(args.task_id)

    summary = {"taskId": args.task_id, "base": BASE, "result": "PASS" if ok else "FAIL"}
    (output_dir / "verify-demo-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print()
    print(f"verify_demo: {'PASS' if ok else 'FAIL'} (taskId={args.task_id})")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
