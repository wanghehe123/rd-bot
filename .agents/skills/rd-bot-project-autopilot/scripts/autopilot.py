"""Deterministic command-line entry point for the repo-scoped autopilot Skill."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Mapping

if __package__ in {None, ""}:  # Support the explicit fallback invocation from SKILL.md.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from scripts.github_client import GitHubClient, GitHubPolicyError, GitHubResponseError, GitHubTransportError
    from scripts.iteration_state import (
        ManifestError, ManifestStore, RunStatus, create_manifest, create_provision_manifest,
    )
    from scripts.provisioning import ProvisionPlanError, build_provision_plan, canonical_json
    from scripts.rd_bot_client import ApiResponseError, ApiTransportError, ClientPolicyError, SafeRdBotClient
    from scripts.workflow import AutopilotWorkflow, ProjectProvisioningWorkflow, WorkflowError
    from scripts.workspace_guard import WorkspaceError, assert_unchanged, capture
    from scripts.run_artifacts import redact
else:
    from .github_client import GitHubClient, GitHubPolicyError, GitHubResponseError, GitHubTransportError
    from .iteration_state import (
        ManifestError, ManifestStore, RunStatus, create_manifest, create_provision_manifest,
    )
    from .provisioning import ProvisionPlanError, build_provision_plan, canonical_json
    from .rd_bot_client import ApiResponseError, ApiTransportError, ClientPolicyError, SafeRdBotClient
    from .workflow import AutopilotWorkflow, ProjectProvisioningWorkflow, WorkflowError
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
    _add_subcommand_common(init)
    init.add_argument("--run-id", required=True)
    init.add_argument("--mode", choices=("dry-run", "live-test"), default="dry-run")
    init.add_argument("--project-id", required=True)
    init.add_argument("--idea", required=True)
    init.add_argument("--success-criterion", action="append", required=True, dest="success_criteria")

    projects = sub.add_parser("projects")
    _add_subcommand_common(projects)
    projects.add_argument("--keyword")
    project = sub.add_parser("project")
    _add_subcommand_common(project)
    project.add_argument("--project-id", required=True)

    freeze = sub.add_parser("freeze-plan")
    _add_subcommand_common(freeze)
    freeze.add_argument("--plan-file", required=True)
    for name in ("dispatch", "observe", "retry", "evaluate"):
        command = sub.add_parser(name)
        _add_subcommand_common(command)
        command.add_argument("--iteration", type=int, required=True)
        if name in {"dispatch", "retry", "evaluate"}:
            command.add_argument("--live-test", action="store_true")

    decision = sub.add_parser("record-decision")
    _add_subcommand_common(decision)
    decision.add_argument("--decision-file", required=True)
    report = sub.add_parser("report")
    _add_subcommand_common(report)
    verify = sub.add_parser("verify-workspace")
    _add_subcommand_common(verify)

    provision_init = sub.add_parser("provision-init")
    _add_subcommand_common(provision_init)
    provision_init.add_argument("--run-id", required=True)
    provision_init.add_argument("--mode", choices=("dry-run", "live-provision"), default="dry-run")
    provision_init.add_argument("--idea", required=True)
    provision_init.add_argument("--success-criterion", action="append", required=True, dest="success_criteria")

    provision_plan = sub.add_parser("provision-plan")
    _add_subcommand_common(provision_plan)
    provision_plan.add_argument("--plan-file", required=True)
    provision_plan.add_argument("--github-owner", default=None)

    provision_confirm = sub.add_parser("provision-confirm")
    _add_subcommand_common(provision_confirm)
    provision_confirm.add_argument("--plan-sha256", required=True)

    for name in ("provision-run", "provision-resume"):
        command = sub.add_parser(name)
        _add_subcommand_common(command)
        command.add_argument("--live-provision", action="store_true")
        command.add_argument("--confirm-plan-sha256", default=None)
    return parser


def _add_subcommand_common(command: argparse.ArgumentParser) -> None:
    command.add_argument("--repo-root", default=argparse.SUPPRESS)
    command.add_argument("--run-dir", default=argparse.SUPPRESS)
    command.add_argument("--base-url", default=argparse.SUPPRESS)


def main(argv: list[str] | None = None, environ: Mapping[str, str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    env = dict(os.environ if environ is None else environ)
    try:
        repo_root = Path(args.repo_root).expanduser().resolve()
        if args.command in {"projects", "project"}:
            return _run_discovery(args, env)
        run_dir = _resolve_run_dir(repo_root, args.run_dir)
        if args.command == "init":
            return _init(args, repo_root, run_dir)
        if args.command == "freeze-plan":
            store = ManifestStore(run_dir)
            plan = _read_json_file(args.plan_file)
            if RunStatus(store.load()["status"]) == RunStatus.DRAFT:
                store.transition(RunStatus.PLANNING, "plan input received")
            _print_json(AutopilotWorkflow(store, None).freeze_plan(plan))
            return 0
        if args.command == "record-decision":
            return _record_decision(run_dir, args.decision_file)
        if args.command == "report":
            _report(run_dir)
            return 0
        if args.command == "verify-workspace":
            return _verify_workspace(repo_root, run_dir)
        if args.command == "provision-init":
            return _provision_init(args, repo_root, run_dir)
        if args.command == "provision-plan":
            return _provision_plan(args, run_dir)
        if args.command == "provision-confirm":
            return _provision_confirm(args, run_dir)
        if args.command in {"provision-run", "provision-resume"}:
            return _provision_run(args, run_dir, env)
        return _run_workflow(args, repo_root, run_dir, env)
    except (
        ApiResponseError, ApiTransportError, ClientPolicyError, GitHubPolicyError, GitHubResponseError,
        GitHubTransportError, ManifestError, ProvisionPlanError, WorkflowError, WorkspaceError, ValueError, OSError,
    ) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


def _run_discovery(args: argparse.Namespace, env: Mapping[str, str]) -> int:
    client = SafeRdBotClient(args.base_url, mode="dry-run", live_flag=False, environ=env)
    if args.command == "projects":
        _print_json(client.list_projects(keyword=args.keyword))
    else:
        _print_json(client.get_project(args.project_id))
    return 0


def _init(args: argparse.Namespace, repo_root: Path, run_dir: Path) -> int:
    if run_dir.name != args.run_id:
        raise ValueError("run directory name must equal --run-id")
    store = ManifestStore(run_dir)
    manifest = create_manifest(args.run_id, args.mode, args.project_id, args.idea, args.success_criteria)
    before = capture(repo_root, exclude_paths=[run_dir])
    store.initialize(manifest)
    store.set_workspace_before(before)
    _print_json(store.load())
    return 0


def _provision_init(args: argparse.Namespace, repo_root: Path, run_dir: Path) -> int:
    if run_dir.name != args.run_id:
        raise ValueError("run directory name must equal --run-id")
    store = ManifestStore(run_dir)
    manifest = create_provision_manifest(args.run_id, args.mode, args.idea, args.success_criteria)
    before = capture(repo_root, exclude_paths=[run_dir])
    store.initialize(manifest)
    store.set_workspace_before(before)
    _print_json(store.load())
    return 0


def _provision_plan(args: argparse.Namespace, run_dir: Path) -> int:
    store = ManifestStore(run_dir)
    manifest = store.load()
    plan_path = Path(args.plan_file).expanduser()
    if args.github_owner is not None:
        plan = build_provision_plan(
            run_id=manifest["runId"],
            idea=manifest["idea"],
            success_criteria=manifest["successCriteria"],
            github_owner=args.github_owner,
        )
        plan_path.write_text(canonical_json(plan) + "\n", encoding="utf-8")
    else:
        plan = _read_json_file(str(plan_path))
    digest = store.freeze_provision_plan(plan)
    _print_json({"planSha256": digest, "status": store.load()["status"]})
    return 0


def _provision_confirm(args: argparse.Namespace, run_dir: Path) -> int:
    store = ManifestStore(run_dir)
    result = store.confirm_provision(args.plan_sha256)
    _print_json({"planSha256": args.plan_sha256, "status": result["status"]})
    return 0


def _provision_run(args: argparse.Namespace, run_dir: Path, env: Mapping[str, str]) -> int:
    store = ManifestStore(run_dir)
    manifest = store.load()
    if manifest.get("schemaVersion") != "rd-bot-autopilot/v2":
        raise ValueError("provisioning commands require a provision-init run")
    if manifest["mode"] == "dry-run":
        workflow = ProjectProvisioningWorkflow(store, None, None, live_enabled=False)
        result = workflow.provision() if args.command == "provision-run" else workflow.resume_provision()
        _print_json(result)
        return 0 if RunStatus(result["status"]) not in {RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP} else 1
    if not args.live_provision:
        raise ValueError(f"{args.command} requires --live-provision")
    confirmation = manifest.get("provisioning", {}).get("confirmation")
    if not isinstance(confirmation, dict):
        raise ValueError(f"{args.command} requires a persisted provision-confirm receipt")
    if args.confirm_plan_sha256 != confirmation.get("planSha256"):
        raise ValueError(f"{args.command} requires --confirm-plan-sha256 equal to the confirmed plan digest")
    if env.get("RD_BOT_AUTOPILOT_LIVE_PROVISION") != "1":
        raise ValueError("RD_BOT_AUTOPILOT_LIVE_PROVISION=1 is required for live provisioning")
    rd_client = SafeRdBotClient(
        args.base_url,
        mode="live-provision",
        live_flag=True,
        environ=env,
        request_ledger=store.append_request_ledger,
    )
    github_client = GitHubClient()
    workflow = ProjectProvisioningWorkflow(store, github_client, rd_client, live_enabled=True)
    result = workflow.provision() if args.command == "provision-run" else workflow.resume_provision()
    _print_json(result)
    return 0 if RunStatus(result["status"]) not in {RunStatus.WAITING_HUMAN, RunStatus.BOUNDED_STOP} else 1


def _run_workflow(args: argparse.Namespace, repo_root: Path, run_dir: Path, env: Mapping[str, str]) -> int:
    del repo_root
    store = ManifestStore(run_dir)
    manifest = store.load()
    client = SafeRdBotClient(
        args.base_url,
        mode=manifest["mode"],
        live_flag=bool(getattr(args, "live_test", False)),
        environ=env,
        request_ledger=store.append_request_ledger,
    )
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
    if decision not in {"COMPLETE", "NEXT_ITERATION", "STOP", "WAITING_HUMAN", "RESUME"}:
        raise ValueError("decision must be COMPLETE, NEXT_ITERATION, STOP, WAITING_HUMAN, or RESUME")
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
    after = capture(repo_root, exclude_paths=[run_dir])
    store.set_workspace_after(after)
    assert_unchanged(before, after)
    print("WORKSPACE_UNCHANGED")
    return 0


def _report(run_dir: Path) -> None:
    manifest = ManifestStore(run_dir).load()
    status = RunStatus(manifest["status"])
    label = {
        RunStatus.COMPLETED: "GOAL_ACHIEVED",
        RunStatus.DRY_RUN_COMPLETED: "DRY_RUN_OK",
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
