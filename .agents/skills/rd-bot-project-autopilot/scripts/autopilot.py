"""Deterministic command-line entry point for the repo-scoped autopilot Skill."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Mapping

from .iteration_state import ManifestError, ManifestStore, RunStatus, create_manifest
from .rd_bot_client import ClientPolicyError, SafeRdBotClient
from .workflow import AutopilotWorkflow, WorkflowError
from .workspace_guard import WorkspaceError, assert_unchanged, capture
from .run_artifacts import redact


DEFAULT_BASE_URL = "http://127.0.0.1:18080"
_RUN_ROOT = Path("qa-runs") / "autopilot"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run one bounded RD-Bot project iteration.")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--run-dir", default=None)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    sub = parser.add_subparsers(dest="command", required=True)

    init = sub.add_parser("init")
    init.add_argument("--run-id", required=True)
    init.add_argument("--mode", choices=("dry-run", "live-test"), default="dry-run")
    init.add_argument("--project-id", required=True)
    init.add_argument("--idea", required=True)
    init.add_argument("--success-criterion", action="append", required=True, dest="success_criteria")

    projects = sub.add_parser("projects")
    projects.add_argument("--keyword")
    project = sub.add_parser("project")
    project.add_argument("--project-id", required=True)

    freeze = sub.add_parser("freeze-plan")
    freeze.add_argument("--plan-file", required=True)
    for name in ("dispatch", "observe", "retry", "evaluate"):
        command = sub.add_parser(name)
        command.add_argument("--iteration", type=int, required=True)
        if name in {"dispatch", "retry", "evaluate"}:
            command.add_argument("--live-test", action="store_true")

    decision = sub.add_parser("record-decision")
    decision.add_argument("--decision-file", required=True)
    sub.add_parser("report")
    sub.add_parser("verify-workspace")
    return parser


def main(argv: list[str] | None = None, environ: Mapping[str, str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    env = dict(os.environ if environ is None else environ)
    try:
        repo_root = Path(args.repo_root).expanduser().resolve()
        run_dir = _resolve_run_dir(repo_root, args.run_dir)
        if args.command == "init":
            return _init(args, repo_root, run_dir)
        if args.command == "projects":
            client = SafeRdBotClient(args.base_url, mode="dry-run", live_flag=False, environ=env)
            _print_json(client.list_projects(keyword=args.keyword))
            return 0
        if args.command == "project":
            client = SafeRdBotClient(args.base_url, mode="dry-run", live_flag=False, environ=env)
            _print_json(client.get_project(args.project_id))
            return 0
        if args.command == "freeze-plan":
            store = ManifestStore(run_dir)
            plan = _read_json_file(args.plan_file)
            _print_json(AutopilotWorkflow(store, None).freeze_plan(plan))
            return 0
        if args.command == "record-decision":
            return _record_decision(run_dir, args.decision_file)
        if args.command == "report":
            _report(run_dir)
            return 0
        if args.command == "verify-workspace":
            return _verify_workspace(repo_root, run_dir)
        return _run_workflow(args, repo_root, run_dir, env)
    except (ClientPolicyError, ManifestError, WorkflowError, WorkspaceError, ValueError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


def _init(args: argparse.Namespace, repo_root: Path, run_dir: Path) -> int:
    if run_dir.name != args.run_id:
        raise ValueError("run directory name must equal --run-id")
    store = ManifestStore(run_dir)
    manifest = create_manifest(args.run_id, args.mode, args.project_id, args.idea, args.success_criteria)
    store.initialize(manifest)
    store.transition(RunStatus.PLANNING, "run initialized; planning may begin")
    store.set_workspace_before(capture(repo_root))
    _print_json(store.load())
    return 0


def _run_workflow(args: argparse.Namespace, repo_root: Path, run_dir: Path, env: Mapping[str, str]) -> int:
    del repo_root
    store = ManifestStore(run_dir)
    manifest = store.load()
    client = SafeRdBotClient(args.base_url, mode=manifest["mode"], live_flag=bool(getattr(args, "live_test", False)), environ=env)
    workflow = AutopilotWorkflow(store, client)
    iteration_no = args.iteration
    status = RunStatus(manifest["status"])
    if args.command == "dispatch":
        if status == RunStatus.READY:
            result = workflow.dispatch(iteration_no)
        else:
            result = workflow.resume(iteration_no)
    elif args.command == "observe":
        result = workflow.observe(iteration_no)
    elif args.command == "retry":
        result = workflow.retry(iteration_no)
    elif args.command == "evaluate":
        result = workflow.evaluate(iteration_no)
    else:
        raise ValueError(f"unsupported command: {args.command}")
    _print_json(result)
    return 0 if RunStatus(result["status"]) not in {RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP} else 1


def _record_decision(run_dir: Path, decision_file: str) -> int:
    value = _read_json_file(decision_file)
    if set(value) - {"decision", "reason", "evidenceIds", "nextGoal"}:
        raise ValueError("decision contains unknown fields")
    decision = value.get("decision")
    if decision not in {"COMPLETE", "NEXT_ITERATION", "STOP", "WAITING_HUMAN"}:
        raise ValueError("decision must be COMPLETE, NEXT_ITERATION, STOP, or WAITING_HUMAN")
    store = ManifestStore(run_dir)
    result = store.record_decision(
        decision,
        str(value.get("reason", "")),
        value.get("evidenceIds", []),
        next_goal=value.get("nextGoal"),
    )
    _print_json(result)
    return 0


def _verify_workspace(repo_root: Path, run_dir: Path) -> int:
    store = ManifestStore(run_dir)
    manifest = store.load()
    before = manifest.get("workspaceBefore")
    if not isinstance(before, dict):
        raise WorkspaceError("workspaceBefore is missing")
    after = capture(repo_root)
    store.set_workspace_after(after)
    assert_unchanged(before, after)
    print("WORKSPACE_UNCHANGED")
    return 0


def _report(run_dir: Path) -> None:
    manifest = ManifestStore(run_dir).load()
    status = RunStatus(manifest["status"])
    label = {
        RunStatus.COMPLETED: "GOAL_ACHIEVED",
        RunStatus.BOUNDED_STOP: "BOUNDED_STOP",
        RunStatus.WAITING_HUMAN: "HUMAN_REQUIRED",
    }.get(status, "IN_PROGRESS")
    print(f"# RD-Bot Autopilot Report\n\n- Result: `{label}`\n- Run: `{manifest['runId']}`\n- Status: `{status.value}`\n- Project: `{manifest['projectId']}`")
    if manifest.get("stopReason"):
        print(f"- Reason: {manifest['stopReason']}")
    for item in manifest.get("iterations", []):
        print(f"\n## Iteration {item.get('iterationNo')}\n- Task: `{item.get('taskId') or 'not created'}`\n- Task status: `{item.get('taskStatus') or 'unknown'}`")
        if item.get("evaluationRunId"):
            print(f"- Evaluation: `{item['evaluationRunId']}`")


def _read_json_file(path: str) -> dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("JSON input must be an object")
    return value


def _print_json(value: Any) -> None:
    print(json.dumps(redact(value), ensure_ascii=False, sort_keys=True))


def _resolve_run_dir(repo_root: Path, value: str | None) -> Path:
    if value is None:
        raise ValueError("--run-dir is required")
    candidate = Path(value).expanduser()
    if not candidate.is_absolute():
        candidate = repo_root / candidate
    candidate = candidate.resolve()
    root = (repo_root / _RUN_ROOT).resolve()
    try:
        relative = candidate.relative_to(root)
    except ValueError as exc:
        raise ValueError("run directory must be inside qa-runs/autopilot") from exc
    if len(relative.parts) != 1 or not relative.name:
        raise ValueError("run directory must be one child of qa-runs/autopilot")
    return candidate


if __name__ == "__main__":
    raise SystemExit(main())
