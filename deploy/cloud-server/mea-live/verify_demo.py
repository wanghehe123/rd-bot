#!/usr/bin/env python3
"""MEA Demo 普通 HTTP 读取核对 — 只读断言，不含 kill/reopen/故障注入。

逐项验证组合候选（codex/mea-demo）上的读取接口返回真实 JSON：
  1. 任务列表接口正常（不是 SPA index.html / 500）
  2. shell、execution-overview、role-prompts 读取接口
  3. coding-mea 读取接口（codingStageRunId 参数、Manager 全文 boundedContract）
  4. stage result 读取接口
  负例：
  N1. 跨 task 的 stageRunId 请求 coding-mea / stage result → 明确 404，不串任务。
      优先用运行时发现的「另一个真实 REQUIREMENT 任务的真实 stageRunId」（正向或反向）；
      环境只有一个任务时该项进入未验证项清单。
  N2. 没有完整产物的 stage → 明确 unavailable JSON，不白屏不伪造。
      优先在真实 stage 里找「存在但无完整 stage result」的阶段，断言 result 端点
      available=false + unavailableReason，且 /result/content 明确 404；
      找不到这样的阶段时该项进入未验证项清单。
  兜底假 id 负例（not-a-real-stage）保留，作为无真实标识可借用时的基线。
  无法执行的检查一律记入未验证项（UNVERIFIED/SKIPPED）：stdout 显式打印 + summary JSON
  的 unverified 字段，不静默跳过。

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

# 本次运行未能验证的检查项：{"item": 检查名, "reason": 为什么没验证}。
# SKIP 不判 FAIL（exit code 不受影响），但必须显式出现在 stdout 与 summary JSON 里。
UNVERIFIED: list[dict] = []


def req(method: str, path: str, timeout: int = 60) -> tuple[int, object]:
    """返回 (status, 解析后的 JSON 或原始文本前缀)。不抛 HTTP/网络读取错误。"""
    request = urllib.request.Request(BASE + path, headers={"Accept": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode()
            return response.status, _maybe_json(raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        return error.code, _maybe_json(raw)
    except urllib.error.URLError as error:
        return 0, {"__error__": str(error)}


def _maybe_json(raw: str) -> object:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"__raw__": raw[:200]}


def is_json_response(payload: object) -> bool:
    return isinstance(payload, dict) or isinstance(payload, list)


def is_task_page(payload: object) -> bool:
    """Validate the task-list DTO without assuming the selected task is on page one."""
    if not isinstance(payload, dict) or not isinstance(payload.get("records"), list):
        return False
    page = payload.get("page")
    page_size = payload.get("pageSize")
    total = payload.get("total")
    pages = payload.get("pages")
    if any(type(value) is not int for value in (page, page_size, total, pages)):
        return False
    if page < 1 or page_size < 1 or total < 0 or pages < 0 or len(payload["records"]) > page_size:
        return False
    expected_pages = (total + page_size - 1) // page_size if total else 0
    if pages != expected_pages:
        return False
    return all(
        isinstance(record, dict)
        and str(record.get("taskId") or "").strip()
        and str(record.get("taskType") or "").strip()
        for record in payload["records"]
    )


def check(name: str, condition: bool, detail: str = "") -> bool:
    mark = "PASS" if condition else "FAIL"
    print(f"[{mark}] {name}{(' — ' + detail) if detail else ''}")
    return condition


def skip(name: str, reason: str) -> None:
    """记录一个未验证项：不判 FAIL，但不允许静默跳过。"""
    print(f"[SKIP] {name} — {reason}")
    UNVERIFIED.append({"item": name, "reason": reason})


MANAGER_ROUTES = {"EXECUTE", "DONE", "BLOCKED", "ASK", "REPLAN"}


def manager_detail_matches(
    task_id: str, reference: dict, detail: object, require_bounded_contract: bool = False
) -> bool:
    """Check Manager full-detail identity and the route-specific DTO shape."""
    if not isinstance(detail, dict):
        return False
    expected_targets = reference.get("targetRecordIds")
    if not isinstance(expected_targets, list):
        return False
    if detail.get("taskId") != task_id or detail.get("decisionHash") != reference.get("decisionHash"):
        return False
    if detail.get("route") not in MANAGER_ROUTES:
        return False
    if detail.get("route") != reference.get("route"):
        return False
    if str(detail.get("executorRoute") or "") != str(reference.get("executorRoute") or ""):
        return False
    if detail.get("targetRecordIds") != expected_targets:
        return False
    if detail.get("roundNo") != reference.get("roundNo"):
        return False
    if detail.get("stateVersion") != reference.get("stateVersion"):
        return False
    if detail.get("stateHash") != reference.get("stateHash"):
        return False
    if "sourceCommandId" in reference and detail.get("sourceCommandId") != reference.get("sourceCommandId"):
        return False
    contract = detail.get("boundedContract")
    return isinstance(contract, str) and (bool(contract.strip()) if require_bounded_contract else True)


def is_bounded_coding_decision(reference: object) -> bool:
    """A Manager decision that must carry a bounded Coding repair contract."""
    if not isinstance(reference, dict):
        return False
    target_ids = reference.get("targetRecordIds")
    return (
        reference.get("route") == "EXECUTE"
        and reference.get("executorRoute") == "CODING_AGENT"
        and isinstance(target_ids, list)
        and bool(target_ids)
        and all(isinstance(record_id, str) and record_id.strip() for record_id in target_ids)
    )


def find_second_requirement_task(task_id: str) -> tuple[str, int, str]:
    """从任务列表发现另一个真实 REQUIREMENT 任务。

    返回 (otherTaskId, 列表里可见的 REQUIREMENT 任务总数, discoveryError)；找不到第二个时 otherTaskId 为空。
    任务没有静态种子（全部经 POST /admin/rd-tasks/requirements 创建），只能运行时发现。
    """
    status, payload = req("GET", "/admin/rd-tasks?pageSize=100")
    if status != 200:
        return "", 0, f"任务列表接口不可用（status={status}）"
    if not isinstance(payload, dict):
        return "", 0, "任务列表响应不是 JSON 对象"
    records = payload.get("records")
    if not isinstance(records, list):
        return "", 0, "任务列表响应缺少 records 数组"
    requirement_tasks = [
        str(record.get("taskId") or "").strip()
        for record in records
        if isinstance(record, dict)
        and str(record.get("taskId") or "").strip()
        and str(record.get("taskType") or "").strip().upper() == "REQUIREMENT"
        and str(record.get("status") or "").strip().upper() != "DELETED"
    ]
    for candidate in requirement_tasks:
        if candidate != task_id:
            return candidate, len(requirement_tasks), ""
    return "", len(requirement_tasks), ""


def collect_stage_references(task_id: str, overview: object, mea: object) -> list[dict]:
    """汇总本次已取回响应里的真实 stage 引用。

    execution-overview 的 stageRuns 覆盖全部需求交付角色
    （REQUIREMENT_REVIEWER / SOLUTION_ARCHITECT / CODING_AGENT / QA_AGENT），
    coding-mea 的 codingStages / qaStages 作补充；按 stageRunId 去重。
    """
    refs: dict[str, dict] = {}
    sources: list[object] = []
    if isinstance(overview, dict):
        sources.append(overview.get("stageRuns") or [])
    if isinstance(mea, dict):
        sources.append(mea.get("codingStages") or [])
        sources.append(mea.get("qaStages") or [])
    for stages in sources:
        for stage in stages if isinstance(stages, list) else []:
            if not isinstance(stage, dict):
                continue
            stage_task_id = str(stage.get("taskId") or "").strip()
            if stage_task_id and stage_task_id != task_id:
                continue
            stage_run_id = str(stage.get("stageRunId") or "").strip()
            if not stage_run_id or stage_run_id in refs:
                continue
            refs[stage_run_id] = {
                "stageRunId": stage_run_id,
                "status": str(stage.get("status") or "").strip().upper(),
                "resultArtifactId": str(stage.get("resultArtifactId") or "").strip(),
            }
    return list(refs.values())


def find_stage_without_full_result(
    task_id: str, stages: list[dict], exclude: set[str], max_probes: int = 8
) -> str:
    """在真实 stage 里找一个「存在但无完整 stage result」的阶段。

    判定以 stage result 端点为准：200 且 available=false 且带 unavailableReason
    （source=ARTIFACT_PREVIEW / UNAVAILABLE）。疑似缺产物的候选排前面
    （非 SUCCEEDED、或无 resultArtifactId，如失败的旧 Coding Attempt、SKIPPED 阶段）；
    探测数量有上界，保持脚本只读且轻量。找不到返回空串。
    """
    candidates = [stage for stage in stages if stage["stageRunId"] not in exclude]
    candidates.sort(
        key=lambda stage: stage["status"] == "SUCCEEDED" and bool(stage["resultArtifactId"])
    )
    for stage in candidates[:max_probes]:
        status, view = req("GET", f"/admin/rd-tasks/{task_id}/stage-runs/{stage['stageRunId']}/result")
        if (
            status == 200
            and isinstance(view, dict)
            and view.get("taskId") == task_id
            and view.get("stageRunId") == stage["stageRunId"]
            and view.get("available") is False
            and isinstance(view.get("unavailableReason"), str)
            and view.get("unavailableReason")
            and view.get("source") in {"ARTIFACT_PREVIEW", "UNAVAILABLE"}
        ):
            return stage["stageRunId"]
    return ""


def verify_task_read_apis(task_id: str) -> bool:
    ok = True
    coding_stage_run_id = ""
    overview: object = None
    mea: object = None

    # 1. 任务列表：JSON 且含条目结构，不是 SPA index.html
    status, payload = req("GET", "/admin/rd-tasks")
    ok &= check("task list returns JSON", status == 200 and is_task_page(payload),
                f"status={status}")

    # 2. shell（任务详情）
    status, detail = req("GET", f"/admin/rd-tasks/{task_id}")
    ok &= check("task shell returns JSON", status == 200 and isinstance(detail, dict)
                and detail.get("taskId") == task_id,
                f"status={status}")

    # 3. execution overview
    status, overview = req("GET", f"/admin/rd-tasks/{task_id}/execution-overview")
    ok &= check("execution-overview returns JSON", status == 200 and isinstance(overview, dict)
                and overview.get("taskId") == task_id
                and isinstance(overview.get("stageRuns"), list),
                f"status={status}")

    # 4. role prompts
    status, prompts = req("GET", f"/admin/rd-tasks/{task_id}/role-prompts")
    ok &= check("role-prompts returns JSON", status == 200 and isinstance(prompts, dict)
                and prompts.get("taskId") == task_id
                and isinstance(prompts.get("stagePrompts"), list),
                f"status={status}")

    # 5. coding-mea：不传参数走默认最新 Coding Attempt；available + schemaVersion=1
    status, mea = req("GET", f"/admin/rd-tasks/{task_id}/coding-mea")
    ok &= check("coding-mea returns JSON", status == 200 and isinstance(mea, dict)
                and mea.get("taskId") == task_id, f"status={status}")
    if isinstance(mea, dict):
        ok &= check("coding-mea schemaVersion=1", mea.get("schemaVersion") == 1,
                    f"schemaVersion={mea.get('schemaVersion')}")
        coding_stage_run_id = str(mea.get("codingStageRunId") or "")
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
                and mea2.get("taskId") == task_id
                and mea2.get("codingStageRunId") == coding_stage_run_id,
                f"status={status2}",
            )
        else:
            skip("coding-mea honors codingStageRunId",
                 "coding-mea 未返回 codingStageRunId，没有真实标识可回放该参数")

        # Manager 决策全文：先验证首条决策的完整身份/字段形状。
        # QA/DONE/ASK/BLOCKED 等决策的 boundedContract 合法地可以为空。
        decisions = mea.get("decisions") or []
        if decisions:
            first_decision = decisions[0] if isinstance(decisions[0], dict) else {}
            decision_hash = str(first_decision.get("decisionHash") or "").strip()
            first_detail: object = None
            status3 = 0
            if decision_hash:
                status3, first_detail = req(
                    "GET", f"/admin/rd-tasks/{task_id}/manager-decisions/{decision_hash}"
                )
                ok &= check(
                    "manager-decision full text has identity and valid shape",
                    status3 == 200 and manager_detail_matches(task_id, first_decision, first_detail),
                    f"status={status3}",
                )
            else:
                ok &= check("manager-decision full text has identity and valid shape", False,
                            "decisionHash 缺失，无法证明决策身份")

            bounded_decision = next(
                (candidate for candidate in decisions if is_bounded_coding_decision(candidate)), None
            )
            if bounded_decision is None:
                skip(
                    "bounded Coding EXECUTE full text has non-empty contract",
                    "当前任务没有 EXECUTE→CODING_AGENT 且带 targetRecordIds 的 Manager 决策",
                )
            elif bounded_decision is first_decision:
                ok &= check(
                    "bounded Coding EXECUTE full text has non-empty contract",
                    status3 == 200 and manager_detail_matches(
                        task_id, bounded_decision, first_detail, require_bounded_contract=True
                    ),
                    f"status={status3}",
                )
            else:
                bounded_hash = str(bounded_decision.get("decisionHash") or "").strip()
                status_bounded, bounded_detail = req(
                    "GET", f"/admin/rd-tasks/{task_id}/manager-decisions/{bounded_hash}"
                )
                ok &= check(
                    "bounded Coding EXECUTE full text has non-empty contract",
                    status_bounded == 200 and manager_detail_matches(
                        task_id, bounded_decision, bounded_detail, require_bounded_contract=True
                    ),
                    f"status={status_bounded}",
                )
        else:
            skip("manager-decision full text has identity and valid shape",
                 "coding-mea decisions 为空（该任务没有可读的 Manager 决策），全文端点无从断言")
            skip("bounded Coding EXECUTE full text has non-empty contract",
                 "coding-mea decisions 为空，没有可选择的 bounded Coding EXECUTE 决策")

        # 6. stage result：对最新 coding stage 读结果（预览或不可用都必须是明确 JSON）
        if coding_stage_run_id:
            status4, stage_result = req(
                "GET", f"/admin/rd-tasks/{task_id}/stage-runs/{coding_stage_run_id}/result"
            )
            ok &= check(
                "stage result returns explicit JSON",
                status4 == 200 and isinstance(stage_result, dict)
                and stage_result.get("taskId") == task_id
                and stage_result.get("stageRunId") == coding_stage_run_id
                and stage_result.get("source") in {
                    "FINALIZATION_RESULT", "ARTIFACT_PREVIEW", "UNAVAILABLE"
                },
                f"status={status4} source={(stage_result or {}).get('source') if isinstance(stage_result, dict) else None}",
            )
        else:
            skip("stage result returns explicit JSON",
                 "coding-mea 未返回 codingStageRunId，没有真实 Coding stage 可读结果")

    # N1-baseline. 跨 task 负例兜底：用一个不存在的 stageRunId 明确 404
    status5, _ = req("GET", f"/admin/rd-tasks/{task_id}/coding-mea?codingStageRunId=not-a-real-stage")
    ok &= check("cross/fake stageRunId is rejected (404)", status5 == 404, f"status={status5}")

    # N2-baseline. 缺产物负例兜底：不存在的 stage result → 404 明确不可用，而不是 500 / index.html
    status6, payload6 = req("GET", f"/admin/rd-tasks/{task_id}/stage-runs/not-a-real-stage/result")
    ok &= check("missing stage result is explicit (404 JSON)",
                status6 == 404 and is_json_response(payload6), f"status={status6}")

    # N1-real. 跨 task 负例（真实标识）：发现第二个真实 REQUIREMENT 任务后互打真实 stageRunId。
    # 正向：另一任务的真实 codingStageRunId 打到当前任务名下；
    # 反向：另一任务没有 Coding stage 时，把当前任务的真实 codingStageRunId 打到它名下。
    other_task_id, requirement_task_count, discovery_error = find_second_requirement_task(task_id)
    foreign_stage_run_id = ""
    if other_task_id:
        status_o, mea_o = req("GET", f"/admin/rd-tasks/{other_task_id}/coding-mea")
        if status_o == 200 and isinstance(mea_o, dict) \
                and mea_o.get("taskId") == other_task_id:
            foreign_stage_run_id = str(mea_o.get("codingStageRunId") or "")
    if other_task_id and foreign_stage_run_id:
        status7, _ = req(
            "GET", f"/admin/rd-tasks/{task_id}/coding-mea?codingStageRunId={foreign_stage_run_id}"
        )
        ok &= check(
            "cross-task coding-mea with real stageRunId rejected (404)",
            status7 == 404,
            f"direction=foreign->current foreignTaskId={other_task_id} "
            f"stageRunId={foreign_stage_run_id} status={status7}",
        )
        status8, _ = req(
            "GET", f"/admin/rd-tasks/{task_id}/stage-runs/{foreign_stage_run_id}/result"
        )
        ok &= check(
            "cross-task stage result with real stageRunId rejected (404)",
            status8 == 404,
            f"direction=foreign->current foreignTaskId={other_task_id} "
            f"stageRunId={foreign_stage_run_id} status={status8}",
        )
    elif other_task_id and coding_stage_run_id:
        status7, _ = req(
            "GET", f"/admin/rd-tasks/{other_task_id}/coding-mea?codingStageRunId={coding_stage_run_id}"
        )
        ok &= check(
            "cross-task coding-mea with real stageRunId rejected (404)",
            status7 == 404,
            f"direction=current->foreign foreignTaskId={other_task_id} "
            f"stageRunId={coding_stage_run_id} status={status7}",
        )
        status8, _ = req(
            "GET", f"/admin/rd-tasks/{other_task_id}/stage-runs/{coding_stage_run_id}/result"
        )
        ok &= check(
            "cross-task stage result with real stageRunId rejected (404)",
            status8 == 404,
            f"direction=current->foreign foreignTaskId={other_task_id} "
            f"stageRunId={coding_stage_run_id} status={status8}",
        )
    else:
        if discovery_error:
            reason = discovery_error
        elif other_task_id:
            reason = (
                f"第二个 REQUIREMENT 任务 {other_task_id} 已发现，但当前任务没有真实 "
                f"codingStageRunId 可借用（正反向都无法构造真实跨任务引用）"
            )
        else:
            reason = (
                f"/admin/rd-tasks 只能看到 {requirement_task_count} 个 REQUIREMENT 任务，"
                f"没有第二个真实任务可借用 stageRunId（真实跨任务引用未被验证）"
            )
        skip("cross-task coding-mea with real stageRunId rejected (404)", reason)
        skip("cross-task stage result with real stageRunId rejected (404)", reason)

    # N2-real. 真实阶段缺产物负例：用本脚本已取回的真实 stage 引用找一个
    # 「存在但无完整 stage result」的阶段，断言 result 端点明确 unavailable JSON、
    # /result/content 明确 404（FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND 契约）。
    stages = collect_stage_references(task_id, overview, mea)
    exclude = {coding_stage_run_id} if coding_stage_run_id else set()
    incomplete_stage_run_id = (
        find_stage_without_full_result(task_id, stages, exclude) if stages else ""
    )
    if incomplete_stage_run_id:
        status9, incomplete_view = req(
            "GET", f"/admin/rd-tasks/{task_id}/stage-runs/{incomplete_stage_run_id}/result"
        )
        ok &= check(
            "real stage without full result reports explicit unavailable JSON",
            status9 == 200 and isinstance(incomplete_view, dict)
            and incomplete_view.get("taskId") == task_id
            and incomplete_view.get("stageRunId") == incomplete_stage_run_id
            and incomplete_view.get("available") is False
            and bool(incomplete_view.get("unavailableReason"))
            and incomplete_view.get("source") in {"ARTIFACT_PREVIEW", "UNAVAILABLE"},
            f"stageRunId={incomplete_stage_run_id} "
            f"source={(incomplete_view or {}).get('source') if isinstance(incomplete_view, dict) else None} "
            f"unavailableReason={(incomplete_view or {}).get('unavailableReason') if isinstance(incomplete_view, dict) else None}",
        )
        status10, payload10 = req(
            "GET", f"/admin/rd-tasks/{task_id}/stage-runs/{incomplete_stage_run_id}/result/content"
        )
        ok &= check(
            "real stage without full result: /result/content is explicit 404",
            status10 == 404 and is_json_response(payload10),
            f"stageRunId={incomplete_stage_run_id} status={status10}",
        )
    else:
        reason = (
            f"该任务的 {len(stages)} 个真实 stage 中（最多探测 8 个）没有找到缺完整产物的阶段，"
            f"明确 unavailable 契约未被验证"
            if stages
            else "execution-overview / coding-mea 未返回任何真实 stage 引用，明确 unavailable 契约未被验证"
        )
        skip("real stage without full result reports explicit unavailable JSON", reason)
        skip("real stage without full result: /result/content is explicit 404", reason)

    return ok


def main() -> None:
    parser = argparse.ArgumentParser(description="MEA demo read-only HTTP verification")
    parser.add_argument("--task-id", required=True, help="一个已完成的真实需求 taskId")
    parser.add_argument("--output", default="/tmp/mea-demo-verify", help="结果输出目录")
    args = parser.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    ok = verify_task_read_apis(args.task_id)

    summary = {
        "taskId": args.task_id,
        "base": BASE,
        "result": "PASS" if ok else "FAIL",
        "unverified": UNVERIFIED,
        "unverifiedCount": len(UNVERIFIED),
    }
    (output_dir / "verify-demo-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print()
    if UNVERIFIED:
        print(f"unverified items ({len(UNVERIFIED)}):")
        for entry in UNVERIFIED:
            print(f"  - {entry['item']}: {entry['reason']}")
    else:
        print("unverified items: none (every check executed)")
    print(f"verify_demo: {'PASS' if ok else 'FAIL'} (taskId={args.task_id})")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
