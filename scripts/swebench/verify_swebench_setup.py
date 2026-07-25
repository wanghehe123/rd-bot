#!/usr/bin/env python3
"""Verify the local RD-Bot, prompt, and exact-worktree SWE-bench setup."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path
from typing import Any

import swebench_lib as lib


PROMPT_FORBIDDEN_MARKERS = (
    "gold patch",
    "test_patch",
    "fail_to_pass",
    "pass_to_pass",
    "hints_text",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=lib.DEFAULT_RD_BOT_BASE_URL)
    parser.add_argument("--manifest", type=Path, default=lib.DEFAULT_MANIFEST_PATH)
    parser.add_argument("--run-plan", type=Path, default=lib.DEFAULT_RUN_ROOT / "run-plan.json")
    parser.add_argument("--expected-count", type=int, default=10)
    parser.add_argument("--skip-worktrees", action="store_true")
    parser.add_argument("--allow-dirty-worktrees", action="store_true")
    parser.add_argument("--allow-no-repository-documents", action="store_true")
    return parser.parse_args()


def git_value(checkout: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(checkout), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"git command failed in {checkout}: {' '.join(args)}\n{completed.stderr.strip()}")
    return completed.stdout.strip()


def check_prompt(task: dict[str, Any], agent_key: str) -> None:
    prompt_config = task.get("prompt_paths", {}).get(agent_key)
    if not isinstance(prompt_config, dict):
        raise RuntimeError(f"run plan has no prompt entry for {agent_key}")
    path = lib.REPO_ROOT / str(prompt_config.get("path", ""))
    if not path.is_file():
        raise RuntimeError(f"prompt does not exist: {path}")
    content = path.read_text(encoding="utf-8")
    if lib.sha256_text(content) != prompt_config.get("sha256"):
        raise RuntimeError(f"prompt changed after setup: {path}")
    lowered = content.lower()
    leaked = [marker for marker in PROMPT_FORBIDDEN_MARKERS if marker in lowered]
    if leaked:
        raise RuntimeError(f"prompt contains evaluator-only marker(s) {leaked}: {path}")


def verify_task(
    client: lib.RdBotApi,
    row: dict[str, Any],
    task: dict[str, Any],
    args: argparse.Namespace,
) -> None:
    instance_id = row["instance_id"]
    if task.get("instance_id") != instance_id:
        raise RuntimeError(f"run plan task ordering does not match manifest at {instance_id}")
    if task.get("repo") != row["repo"] or task.get("base_commit") != row["base_commit"]:
        raise RuntimeError(f"run plan task metadata does not match manifest for {instance_id}")
    agent_workspaces = task.get("agent_workspaces", {})
    for agent_id in lib.AGENT_IDS:
        check_prompt(task, lib.agent_plan_key(agent_id))

    project = client.get_project(str(task["project"]["project_id"]))
    knowledge_base = client.get_knowledge_base(str(task["knowledge_base"]["id"]))
    if project.get("projectKey") != lib.project_key(row):
        raise RuntimeError(f"project key mismatch for {instance_id}")
    if project.get("repositoryUrl") != lib.repository_url(row):
        raise RuntimeError(f"project repository mismatch for {instance_id}")
    if not project.get("enabled", False):
        raise RuntimeError(f"project is disabled for {instance_id}")
    if project.get("knowledgeBaseId") != knowledge_base.get("id"):
        raise RuntimeError(f"project/knowledge-base binding mismatch for {instance_id}")
    if knowledge_base.get("name") != lib.knowledge_base_name(row) or not knowledge_base.get("enabled", False):
        raise RuntimeError(f"knowledge base is not ready for {instance_id}")

    documents = {
        str(document.get("sourceName")): document
        for document in client.list_documents(str(knowledge_base["id"]))
    }
    task_context = documents.get(lib.TASK_CONTEXT_SOURCE_NAME)
    if task_context is None or task_context.get("status") != "INDEXED":
        raise RuntimeError(f"task context document is not indexed for {instance_id}")
    if not args.allow_no_repository_documents:
        repository_documents = task.get("repository_documents", [])
        if not repository_documents:
            raise RuntimeError(f"no repository documents recorded for {instance_id}")
        for expected in repository_documents:
            actual = documents.get(expected["source_name"])
            if actual is None or actual.get("status") != "INDEXED":
                raise RuntimeError(
                    f"repository document is not indexed for {instance_id}: {expected['source_name']}"
                )

    if not args.skip_worktrees:
        for agent_id in lib.AGENT_IDS:
            agent_key = lib.agent_plan_key(agent_id)
            checkout_path = agent_workspaces.get(agent_key)
            if not isinstance(checkout_path, str):
                raise RuntimeError(f"run plan has no worktree entry for {instance_id} [{agent_id}]")
            checkout = Path(checkout_path).resolve()
            if not checkout.is_dir():
                raise RuntimeError(f"worktree does not exist for {instance_id} [{agent_id}]: {checkout}")
            actual_commit = git_value(checkout, "rev-parse", "HEAD")
            if actual_commit != row["base_commit"]:
                raise RuntimeError(
                    f"worktree commit mismatch for {instance_id} [{agent_id}]: "
                    f"expected {row['base_commit']}, got {actual_commit}"
                )
            if not args.allow_dirty_worktrees:
                status = git_value(checkout, "status", "--porcelain", "--untracked-files=all")
                if status:
                    raise RuntimeError(f"worktree is not clean for {instance_id} [{agent_id}]: {checkout}")


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest.resolve()
    rows = lib.load_manifest(manifest_path, args.expected_count)
    plan = lib.read_json(args.run_plan.resolve())
    if plan.get("manifest_sha256") != lib.sha256_file(manifest_path):
        raise RuntimeError("run plan does not match the safe manifest")
    if plan.get("task_count") != args.expected_count or len(plan.get("tasks", [])) != args.expected_count:
        raise RuntimeError("run plan has the wrong task count")

    client = lib.RdBotApi(args.base_url)
    client.assert_ready()
    for row, task in zip(rows, plan["tasks"], strict=True):
        verify_task(client, row, task, args)
        print(f"OK {row['instance_id']}")

    print(
        f"Verified {args.expected_count} SWE-bench Lite tasks: RD-Bot project/knowledge-base bindings, "
        "agent-safe prompts, indexed documents, and exact clean worktrees."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
