#!/usr/bin/env python3
"""Slim B11 / P2 live probes — not 12×3.

Probes:
  P2-BUDGET: frozen maxAgentTurns/maxTotalTokens on any dispatched role snapshot
             (REQUIREMENT_REVIEWER is enough for slim; coding preferred when available)
  P2-ISO:    QA workspace under provider-attempts/ when QA runs
  P2-GAP:    if HOST_VERIFY_FIX / recovery prompt visible, no stderr-secret wall

Usage (on cloud):
  RD_BOT_BASE_URL=http://127.0.0.1:8080 MEA_EVAL_PROJECT_ID=7499721648870920192 \\
    python3 deploy/cloud-server/mea-live/verify_p2_slim.py --output /tmp/mea-p2-slim
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


PROJECT_ID = os.environ.get("MEA_EVAL_PROJECT_ID", "7499721648870920192")
BASE = os.environ.get("RD_BOT_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
MARKER = "stderr-secret-fixture-unique"
WORKSPACE_ROOT = Path(os.environ.get("RD_BOT_WORKSPACE_ROOT", "/tmp/rd-bot/repair-workspaces"))


def req(method: str, path: str, body: dict | None = None, timeout: int = 120) -> dict:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw.decode()) if raw else {}


def create_and_submit() -> str:
    created = req(
        "POST",
        "/admin/rd-tasks/requirements",
        {
            "projectId": PROJECT_ID,
            "title": "MEA-P2-SLIM 首页标记",
            "priority": "P1",
            "baseBranch": "main",
            "autoExecute": False,
            "repositoryUrl": "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git",
            "repoOwner": "wanghehe123",
            "repoName": "rd-bot-waimai-acceptance-20260624-141045",
            "expectedResult": "顾客首页可见 MEA-P2-SLIM-HOME，client 生产构建通过。",
            "acceptanceCriteria": [
                "顾客首页渲染包含 MEA-P2-SLIM-HOME",
                "client 生产构建通过",
            ],
            "materials": [
                {
                    "materialType": "REQUIREMENT",
                    "sourceType": "MANUAL_TEXT",
                    "title": "单文件标记",
                    "mimeType": "text/plain",
                    "content": (
                        "在顾客端首页增加可见静态标记 MEA-P2-SLIM-HOME。"
                        "只改必要页面文件；不要改商家后台或支付配置。"
                    ),
                }
            ],
        },
    )
    task_id = str(created.get("taskId") or created.get("id") or "")
    if not task_id:
        raise RuntimeError(f"create missing taskId: {created}")
    req("POST", f"/admin/rd-tasks/{task_id}/submit", {})
    return task_id


def get_task(task_id: str) -> dict:
    return req("GET", f"/admin/rd-tasks/{task_id}")


def stage_runs_from_task(task: dict) -> list[dict]:
    for key in ("stageRuns", "stages", "agentStages"):
        value = task.get(key)
        if isinstance(value, list) and value:
            return value
    result = task.get("executionResultJson") or task.get("resultJson") or "{}"
    if isinstance(result, str):
        try:
            parsed = json.loads(result)
        except json.JSONDecodeError:
            parsed = {}
    else:
        parsed = result if isinstance(result, dict) else {}
    stages = parsed.get("stages") or parsed.get("multiAgentStages") or []
    return stages if isinstance(stages, list) else []


def find_coding_stage(task_id: str, task: dict) -> dict | None:
    for stage in stage_runs_from_task(task):
        role = str(stage.get("role") or stage.get("agentRole") or "").upper()
        sid = str(stage.get("stageRunId") or stage.get("id") or "")
        status = str(stage.get("status") or "").upper()
        if "CODING" in role and sid:
            return {"stageRunId": sid, "status": status, "role": role}
    try:
        mea = req("GET", f"/admin/rd-tasks/{task_id}/coding-mea?limit=50")
        for item in mea.get("codingStages") or []:
            sid = str(item.get("stageRunId") or "")
            status = str(item.get("status") or "").upper()
            if sid:
                return {"stageRunId": sid, "status": status, "role": "CODING_AGENT", "mea": mea}
        sid = str(mea.get("codingStageRunId") or "")
        if sid:
            return {"stageRunId": sid, "status": "UNKNOWN", "role": "CODING_AGENT", "mea": mea}
    except Exception:
        return None
    return None


def find_budget_stage(task_id: str, task: dict) -> dict | None:
    """Prefer coding; otherwise any non-PENDING stage with a stageRunId (slim)."""
    coding = find_coding_stage(task_id, task)
    if coding and str(coding.get("status") or "").upper() not in {"", "PENDING", "UNKNOWN"}:
        return coding
    preferred = ("CODING", "REQUIREMENT_REVIEWER", "QA", "ACCEPTANCE")
    candidates: list[dict] = []
    for stage in stage_runs_from_task(task):
        role = str(stage.get("role") or stage.get("agentRole") or "").upper()
        sid = str(stage.get("stageRunId") or stage.get("id") or "")
        status = str(stage.get("status") or "").upper()
        if not sid or status in {"", "PENDING"}:
            continue
        candidates.append({"stageRunId": sid, "status": status, "role": role})
    for needle in preferred:
        for item in candidates:
            if needle in item["role"]:
                return item
    if candidates:
        return candidates[0]
    return coding


def get_profile_snapshot(task_id: str, stage_run_id: str) -> dict:
    return req("GET", f"/admin/rd-tasks/{task_id}/stage-runs/{stage_run_id}/execution-profile")


def parse_snapshot_json(snapshot: dict) -> dict:
    raw = snapshot.get("snapshotJson") or "{}"
    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def qa_provider_attempt_dirs(task_id: str) -> list[str]:
    found: list[str] = []
    if not WORKSPACE_ROOT.is_dir():
        return found
    for path in WORKSPACE_ROOT.glob(f"*{task_id}*"):
        for attempt in path.glob("**/provider-attempts/*"):
            if attempt.is_dir():
                found.append(str(attempt))
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="/tmp/mea-p2-slim")
    parser.add_argument("--poll-seconds", type=int, default=2400)
    parser.add_argument("--task-id", default="")
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)

    task_id = args.task_id.strip() or create_and_submit()
    (out / "meta.json").write_text(
        json.dumps(
            {
                "taskId": task_id,
                "projectId": PROJECT_ID,
                "base": BASE,
                "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    terminal = {
        "COMPLETED",
        "FAILED",
        "FAILED_NEEDS_HUMAN",
        "FAILED_RETRYABLE",
        "CANCELLED",
        "DEAD_LETTERED",
        "REJECTED",
        "MERGED",
        "WAITING_USER_INPUT",
    }
    deadline = time.time() + max(60, args.poll_seconds)
    budget_probe: dict = {"status": "PENDING"}
    iso_probe: dict = {"status": "PENDING"}
    gap_probe: dict = {"status": "PENDING"}
    last: dict = {}

    while time.time() < deadline:
        last = get_task(task_id)
        (out / "latest.json").write_text(json.dumps(last, ensure_ascii=False, indent=2), encoding="utf-8")
        status = str(last.get("status") or "")
        coding = find_coding_stage(task_id, last)
        budget_stage = find_budget_stage(task_id, last)
        stage_id = (budget_stage or {}).get("stageRunId") or ""
        stage_status = (budget_stage or {}).get("status") or ""
        stage_role = (budget_stage or {}).get("role") or ""
        coding_id = (coding or {}).get("stageRunId") or ""
        if coding and coding.get("mea"):
            (out / "coding-mea.json").write_text(
                json.dumps(coding["mea"], ensure_ascii=False, indent=2), encoding="utf-8"
            )

        # Snapshot exists only after stage is dispatched/RUNNING (not PENDING).
        if (
            stage_id
            and stage_status not in {"", "PENDING", "UNKNOWN"}
            and budget_probe.get("status") in {"PENDING", "ERROR"}
        ):
            try:
                snap = get_profile_snapshot(task_id, stage_id)
                payload = parse_snapshot_json(snap)
                profile_name = (
                    "coding-execution-profile.json"
                    if "CODING" in stage_role
                    else f"{stage_role.lower() or 'role'}-execution-profile.json"
                )
                (out / profile_name).write_text(
                    json.dumps(snap, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                turns = int(payload.get("maxAgentTurns") or 0)
                tokens = int(payload.get("maxTotalTokens") or 0)
                ep_status = str(payload.get("episodeBudgetStatus") or "")
                ok = turns == 40 and tokens == 2_000_000 and ep_status == "FROZEN_FROM_ENV"
                budget_probe = {
                    "status": "PASS" if ok else "FAIL",
                    "stageRunId": stage_id,
                    "role": stage_role or payload.get("role"),
                    "stageStatus": stage_status,
                    "maxAgentTurns": turns,
                    "maxTotalTokens": tokens,
                    "episodeBudgetStatus": ep_status,
                }
            except urllib.error.HTTPError as exc:
                # Keep polling on 404 until snapshot is frozen at dispatch.
                budget_probe = {
                    "status": "PENDING",
                    "waitingForSnapshot": True,
                    "http": exc.code,
                    "stageRunId": stage_id,
                    "role": stage_role,
                    "stageStatus": stage_status,
                }
            except Exception as exc:  # noqa: BLE001
                budget_probe = {
                    "status": "PENDING",
                    "error": str(exc),
                    "stageRunId": stage_id,
                    "role": stage_role,
                    "stageStatus": stage_status,
                }

        qa_dirs = qa_provider_attempt_dirs(task_id)
        if qa_dirs and iso_probe.get("status") == "PENDING":
            iso_probe = {"status": "PASS", "dirs": qa_dirs}
            (out / "qa-provider-attempts.json").write_text(
                json.dumps(iso_probe, ensure_ascii=False, indent=2), encoding="utf-8"
            )

        # Gap / no-stderr: scan any known prompt-like fields and remediation packages
        blob = json.dumps(last, ensure_ascii=False)
        if "HOST_VERIFY_FIX" in blob or "已审计缺口" in blob:
            has_gap = "已审计缺口（Host）" in blob or "GATE-BUILD" in blob
            has_marker = MARKER in blob
            # Prefer stage result content when available
            if coding_id:
                try:
                    content = req(
                        "GET",
                        f"/admin/rd-tasks/{task_id}/stage-runs/{coding_id}/result/content",
                    )
                    (out / "coding-result-content.json").write_text(
                        json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8"
                    )
                    text = json.dumps(content, ensure_ascii=False)
                    has_marker = has_marker or (MARKER in text)
                except Exception:
                    pass
            gap_probe = {
                "status": "PASS" if (has_gap and not has_marker) or (not has_marker) else "FAIL",
                "sawHostVerifyFixOrGap": True,
                "hasAuditedGapOrGate": has_gap,
                "hasSecretMarker": has_marker,
            }

        (out / "probes.json").write_text(
            json.dumps(
                {"budget": budget_probe, "isolation": iso_probe, "gap": gap_probe, "taskStatus": status},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(json.dumps({"taskId": task_id, "status": status, "budget": budget_probe.get("status"), "iso": iso_probe.get("status")}, ensure_ascii=False))
        if status in terminal and budget_probe.get("status") != "PENDING":
            break
        # If budget already captured, can stop early for slim (don't wait full delivery)
        if budget_probe.get("status") in {"PASS", "FAIL", "ERROR"} and status not in {"", "PENDING", "QUEUED"}:
            # still wait a bit for QA isolation if coding succeeded quickly
            if iso_probe.get("status") == "PASS" or status in terminal:
                break
            if time.time() > deadline - 300 and budget_probe.get("status") == "PASS":
                # slim exit: budget is the primary new B11 live assert
                break
        time.sleep(20)

    if iso_probe.get("status") == "PENDING":
        iso_probe = {
            "status": "SKIPPED",
            "reason": "QA provider-attempt dir not observed in slim window",
            "dirs": qa_provider_attempt_dirs(task_id),
        }
    if gap_probe.get("status") == "PENDING":
        gap_probe = {
            "status": "SKIPPED",
            "reason": "No HOST_VERIFY_FIX/audited-gap surface in slim window",
        }

    summary = {
        "taskId": task_id,
        "finalStatus": last.get("status"),
        "probes": {"budget": budget_probe, "isolation": iso_probe, "gap": gap_probe},
        "notR0": True,
        "notFullP2Matrix": True,
    }
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    # Exit 0 if budget PASS; isolation/gap may be SKIPPED in slim
    return 0 if budget_probe.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
