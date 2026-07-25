#!/usr/bin/env python3
"""Reject SWE-bench task runs that are incomplete or expose benchmark-only material."""

from __future__ import annotations

import argparse
import json
from typing import Any

import swebench_lib as lib


REFERENCE_MARKERS = (
    "gold test",
    "gold tests",
    "fix commit",
    "reference answer",
    "reference patch",
    "held-out test",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task_id")
    parser.add_argument("--base-url", default=lib.DEFAULT_RD_BOT_BASE_URL)
    return parser.parse_args()


def stages(task: dict[str, Any]) -> list[dict[str, Any]]:
    raw = task.get("executionResultJson")
    if not isinstance(raw, str) or not raw.strip():
        return []
    parsed = json.loads(raw)
    values = parsed.get("stages", []) if isinstance(parsed, dict) else []
    return [value for value in values if isinstance(value, dict)]


def role_result_text(stage: dict[str, Any]) -> str:
    values = [stage.get("summary", ""), stage.get("errorMessage", ""), stage.get("resultJson", "")]
    return "\n".join(value for value in values if isinstance(value, str)).lower()


def main() -> int:
    args = parse_args()
    client = lib.RdBotApi(args.base_url)
    task = client.request("GET", f"/admin/rd-tasks/{args.task_id}")
    if not isinstance(task, dict):
        raise RuntimeError("task API did not return an object")

    findings: list[str] = []
    task_stages = stages(task)
    if not task_stages:
        findings.append("no structured role stages were recorded")
    for stage in task_stages:
        role = str(stage.get("role", "unknown"))
        if not stage.get("success", False):
            findings.append(f"{role} did not succeed: {stage.get('errorMessage', '')[:240]}")
        text = role_result_text(stage)
        matched = [marker for marker in REFERENCE_MARKERS if marker in text]
        if matched:
            findings.append(
                f"{role} mentions benchmark-only material ({', '.join(matched)}); "
                "do not score this run"
            )

    print(f"task={args.task_id} status={task.get('status', '')}")
    if findings:
        print("SWE-bench score gate: NOT ELIGIBLE")
        for finding in findings:
            print(f"- {finding}")
        return 2

    print("SWE-bench score gate: ELIGIBLE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
