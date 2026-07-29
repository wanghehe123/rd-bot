#!/usr/bin/env python3
"""Readiness verifier for fully offline coding benchmark environments."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any


class ReadinessError(RuntimeError):
    """Raised when a benchmark case cannot prove its offline execution contract."""


@dataclass(frozen=True)
class CaseReadinessResult:
    case_id: str
    passed: bool
    runs: tuple[dict[str, Any], ...]


def validate_case_contract(case: dict[str, Any]) -> None:
    """Rejects commands, image references, and paths that would make a case non-reproducible."""

    case_id = str(case.get("caseId", "")).strip()
    if not case_id or not all(character.isalnum() or character in "._-" for character in case_id):
        raise ReadinessError("caseId must be a safe immutable identifier")
    for command_name in ("baseCommand", "testCommand", "fixCommand"):
        command = case.get(command_name)
        if not isinstance(command, list) or not command or not all(isinstance(value, str) and value.strip() for value in command):
            raise ReadinessError(f"{command_name} must be a non-empty argv list")
        if command[0] in {"sh", "bash", "zsh"}:
            raise ReadinessError(f"{command_name} must not invoke a shell")
    for image_name in ("agentImage", "oracleImage"):
        image = str(case.get(image_name, "")).strip().lower()
        if not image or "@sha256:" not in image or len(image.rsplit("@sha256:", 1)[1]) != 64:
            raise ReadinessError(f"{image_name} must be an immutable image digest")
    workdir = str(case.get("workdir", ".")).strip().replace("\\", "/")
    pure = PurePosixPath(workdir)
    if pure.is_absolute() or ".." in pure.parts:
        raise ReadinessError("workdir must stay beneath the prepared case repository")


def verify_case(case: dict[str, Any], repository_root: Path, timeout_seconds: int = 300) -> CaseReadinessResult:
    """Runs BASE/TEST/FIX three times with package-manager offline guards enabled."""

    validate_case_contract(case)
    workdir = (repository_root / str(case.get("workdir", "."))).resolve()
    if not workdir.is_relative_to(repository_root.resolve()) or not workdir.is_dir():
        raise ReadinessError("case workdir is unavailable")
    environment = os.environ.copy()
    environment.update({
        "PIP_NO_INDEX": "1",
        "PIP_DISABLE_PIP_VERSION_CHECK": "1",
        "npm_config_offline": "true",
        "YARN_ENABLE_NETWORK": "0",
        "RD_EVAL_OFFLINE": "1",
    })
    runs: list[dict[str, Any]] = []
    for round_number in range(1, 4):
        for phase, command_name in (("BASE", "baseCommand"), ("TEST", "testCommand"), ("FIX", "fixCommand")):
            completed = subprocess.run(
                case[command_name],
                cwd=workdir,
                env=environment,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=timeout_seconds,
            )
            runs.append({
                "round": round_number,
                "phase": phase,
                "returnCode": completed.returncode,
                "outputSha256": "sha256:" + hashlib.sha256(completed.stdout.encode("utf-8")).hexdigest(),
            })
            if completed.returncode != 0:
                return CaseReadinessResult(str(case["caseId"]), False, tuple(runs))
    return CaseReadinessResult(str(case["caseId"]), True, tuple(runs))


def verify_config(config_path: Path, output_path: Path, timeout_seconds: int = 300) -> dict[str, Any]:
    """Verifies every declared case before writing a deterministic readiness report."""

    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    cases = config.get("cases")
    if not isinstance(cases, list) or len(cases) != 20:
        raise ReadinessError("coding benchmark config must contain exactly 20 cases")
    ids = [str(case.get("caseId", "")) for case in cases if isinstance(case, dict)]
    if len(ids) != 20 or len(set(ids)) != 20:
        raise ReadinessError("coding benchmark case IDs must be unique")
    root = Path(config.get("repositoryRoot", Path(config_path).parent)).resolve()
    results = [verify_case(case, root, timeout_seconds) for case in cases]
    report = {
        "ready": all(result.passed for result in results),
        "caseCount": len(results),
        "results": [asdict(result) for result in results],
    }
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify offline coding benchmark readiness.")
    parser.add_argument("verify", nargs="?")
    parser.add_argument("--config", required=True, help="JSON content is accepted even when this file uses a .yaml suffix.")
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    args = parser.parse_args()
    output = Path(args.output) if args.output else Path(args.config).with_name("readiness-report.json")
    report = verify_config(Path(args.config), output, max(1, args.timeout_seconds))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ReadinessError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(f"readiness error: {error}", file=sys.stderr)
        raise SystemExit(2)
