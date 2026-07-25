#!/usr/bin/env python3
"""指标采集器：把 E1 两臂原始产物转成统一 metrics.jsonl 行（口径见 spec 文档第 3 章）。

用法::

    # RD-Bot 臂（需后端在线，读 timeline / execution-overview / qa-evidence / delivery-job）
    python3 scripts/benchmarks/collect_task_metrics.py rd-bot \
        --meta qa-runs/benchmarks-metrics/e1/<instance>/rd-bot/meta.json \
        --out qa-runs/benchmarks-metrics/e1/metrics.jsonl

    # Claude Code 臂（离线解析 events.jsonl）
    python3 scripts/benchmarks/collect_task_metrics.py claude-code \
        --meta qa-runs/benchmarks-metrics/e1/<instance>/claude-code/meta.json \
        --out qa-runs/benchmarks-metrics/e1/metrics.jsonl

输出行 schema（两臂同构，缺失项为 null）：
    instance, arm, taskId, finalStatus, wallClockMinutes, effectiveMinutes,
    gateHit, stageAttempts{role:count}, totalAttempts, costUsd, turns,
    hasPatch, hasRegressionTest, hasAcceptanceEvidence, deliveryComplete
"""

import argparse
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

TEST_FILE_PATTERN = re.compile(r"(^|/)(tests?/|test_[^/]+\.py|[^/]+_test\.(py|ts|tsx|java)$)")
TEST_COMMAND_PATTERN = re.compile(
    r"(pytest|runtests\.py|python -m (unittest|pytest)|npm (test|run test)|vitest|mvn .*test)")


def api(base_url: str, path: str):
    with urllib.request.urlopen(base_url.rstrip("/") + path, timeout=60) as response:
        return json.loads(response.read().decode())


def parse_ts(value: str):
    if not value:
        return None
    value = value.replace("Z", "+00:00")
    ts = datetime.fromisoformat(value)
    return ts if ts.tzinfo else ts.replace(tzinfo=timezone.utc)


def minutes_between(start: str, end: str):
    a, b = parse_ts(start), parse_ts(end)
    if a is None or b is None:
        return None
    return round((b - a).total_seconds() / 60, 1)


def collect_rd_bot(meta: dict) -> dict:
    base_url = os.environ.get("RD_BOT_BASE_URL", "http://localhost:18080")
    task_id = meta["taskId"]
    detail = api(base_url, f"/admin/rd-tasks/{task_id}")
    timeline = api(base_url, f"/admin/rd-tasks/{task_id}/timeline")
    overview = api(base_url, f"/admin/rd-tasks/{task_id}/execution-overview")

    # timeline 实际结构（已用 django-11001 真实数据核对）：
    # [{status, enteredAtEpochMillis, durationMillis, ...}]
    events = timeline if isinstance(timeline, list) else timeline.get("events", [])
    waiting_minutes = sum(
        (event.get("durationMillis") or 0)
        for event in events if event.get("status") == "WAITING_APPROVAL"
    ) / 60000.0
    wall_clock = minutes_between(meta.get("startedAt"), meta.get("finishedAt"))
    if wall_clock is None and events:
        first = events[0].get("enteredAtEpochMillis")
        last = events[-1].get("enteredAtEpochMillis")
        if first and last:
            wall_clock = round((last - first) / 60000.0, 1)
    effective = None if wall_clock is None else round(wall_clock - waiting_minutes, 1)

    # 每角色 attempt 数与成本（providerAttempts[].estimatedCostUsd 为字符串）
    stages = overview.get("stageRuns") or []
    stage_attempts = {}
    cost_usd = 0.0
    coding_succeeded = False
    for stage in stages:
        role = stage.get("role") or "UNKNOWN"
        stage_attempts[role] = max(stage_attempts.get(role, 0), stage.get("attemptNo", 1))
        if role == "CODING_AGENT" and stage.get("status") == "SUCCEEDED":
            coding_succeeded = True
        for attempt in stage.get("providerAttempts") or []:
            try:
                cost_usd += float(attempt.get("estimatedCostUsd") or 0)
            except (TypeError, ValueError):
                pass

    # 三件套（数据源已用真实任务核对）：
    #   补丁 = CODING_AGENT 存在 SUCCEEDED attempt
    #   回归测试 = executionResultJson 全文匹配测试文件/测试命令特征
    #   验收证据 = qa-evidence 接口返回 ≥1 个 artifact
    result_blob = detail.get("executionResultJson") or ""
    has_patch = coding_succeeded
    has_regression = bool(TEST_FILE_PATTERN.search(result_blob)) or bool(TEST_COMMAND_PATTERN.search(result_blob))
    has_evidence = False
    try:
        evidence = api(base_url, f"/admin/rd-tasks/{task_id}/qa-evidence")
        items = evidence if isinstance(evidence, list) else evidence.get("items", [])
        has_evidence = len(items) > 0
    except Exception:
        pass

    return {
        "instance": meta["instance"],
        "arm": "rd-bot",
        "taskId": task_id,
        "finalStatus": meta.get("finalStatus") or detail.get("status"),
        "wallClockMinutes": wall_clock,
        "effectiveMinutes": effective,
        "gateHit": meta.get("gateHit", any(e.get("status") == "WAITING_APPROVAL" for e in events)),
        "stageAttempts": stage_attempts,
        "totalAttempts": sum(stage_attempts.values()) if stage_attempts else None,
        "costUsd": round(cost_usd, 2) if cost_usd else None,
        "turns": None,
        "hasPatch": has_patch,
        "hasRegressionTest": has_regression,
        "hasAcceptanceEvidence": has_evidence,
        "deliveryComplete": has_patch and has_regression and has_evidence,
    }


def collect_claude_code(meta: dict) -> dict:
    events_path = Path(meta_path_dir / "claude-events.jsonl")
    cost = turns = duration_ms = None
    wrote_test = ran_test = produced_patch = False
    first_ts = last_ts = None
    with events_path.open() as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            ts = event.get("timestamp")
            if ts:
                first_ts = first_ts or ts
                last_ts = ts
            if event.get("type") == "result":
                # 实测字段（已用真实 events.jsonl 核对）：cost_usd / total_cost / num_turns / duration_ms
                cost = event.get("total_cost_usd") or event.get("cost_usd") or event.get("total_cost") or cost
                turns = event.get("num_turns", turns)
                duration_ms = event.get("duration_ms", duration_ms)
            blob = json.dumps(event, ensure_ascii=False)
            if '"name": "Write"' in blob or '"name": "Edit"' in blob or '"name":"Write"' in blob or '"name":"Edit"' in blob:
                produced_patch = True
                if TEST_FILE_PATTERN.search(blob):
                    wrote_test = True
            if TEST_COMMAND_PATTERN.search(blob):
                ran_test = True

    wall_clock = minutes_between(meta.get("startedAt"), meta.get("finishedAt"))
    if wall_clock is None and first_ts and last_ts:
        wall_clock = minutes_between(first_ts, last_ts)
    if wall_clock is None and duration_ms:
        wall_clock = round(duration_ms / 60000.0, 1)
    has_regression = wrote_test and ran_test
    return {
        "instance": meta["instance"],
        "arm": "claude-code",
        "taskId": None,
        "finalStatus": "EXIT_" + str(meta.get("exitCode")),
        "wallClockMinutes": wall_clock,
        "effectiveMinutes": wall_clock,  # 无人工等待段，两口径相同
        "gateHit": False,
        "stageAttempts": None,
        "totalAttempts": None,
        "costUsd": cost,
        "turns": turns,
        "hasPatch": produced_patch,
        "hasRegressionTest": has_regression,
        "hasAcceptanceEvidence": False,  # 单次直出无结构化验收证据，属产品事实
        "deliveryComplete": produced_patch and has_regression and False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("arm", choices=["rd-bot", "claude-code"])
    parser.add_argument("--meta", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    global meta_path_dir
    meta_path = Path(args.meta)
    meta_path_dir = meta_path.parent
    meta = json.loads(meta_path.read_text())

    row = collect_rd_bot(meta) if args.arm == "rd-bot" else collect_claude_code(meta)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # 幂等：同 instance+arm 重复采集时覆盖旧行
    rows = []
    if out.exists():
        rows = [json.loads(l) for l in out.read_text().splitlines() if l.strip()]
        rows = [r for r in rows if not (r["instance"] == row["instance"] and r["arm"] == row["arm"])]
    rows.append(row)
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n")
    print(json.dumps(row, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
