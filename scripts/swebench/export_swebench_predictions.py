#!/usr/bin/env python3
"""Export one agent's local worktree diffs in SWE-bench prediction formats.

Run this only after the manual agent pass. It never stages, commits, resets, or
otherwise changes a worktree. By default untracked files cause a clear failure
so that accidental local artifacts are not silently submitted.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any

import swebench_lib as lib


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", choices=lib.AGENT_IDS, required=True)
    parser.add_argument("--model-name", required=True)
    parser.add_argument("--manifest", type=Path, default=lib.DEFAULT_MANIFEST_PATH)
    parser.add_argument("--run-plan", type=Path, default=lib.DEFAULT_RUN_ROOT / "run-plan.json")
    parser.add_argument("--output-dir", type=Path, default=lib.DEFAULT_RUN_ROOT)
    parser.add_argument("--expected-count", type=int, default=10)
    parser.add_argument("--include-untracked", action="store_true")
    parser.add_argument("--require-change", action="store_true")
    return parser.parse_args()


def git(checkout: Path, *args: str, accepted_exit_codes: set[int] | None = None) -> str:
    accepted = {0} if accepted_exit_codes is None else accepted_exit_codes
    completed = subprocess.run(
        ["git", "-C", str(checkout), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode not in accepted:
        raise RuntimeError(
            f"git {' '.join(args)} failed in {checkout} with exit code {completed.returncode}: "
            f"{completed.stderr.strip()}"
        )
    return completed.stdout


def untracked_files(checkout: Path) -> list[str]:
    completed = subprocess.run(
        ["git", "-C", str(checkout), "ls-files", "--others", "--exclude-standard", "-z"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"could not list untracked files in {checkout}: {completed.stderr.decode('utf-8', errors='replace')}"
        )
    return [
        path.decode("utf-8", errors="surrogateescape")
        for path in completed.stdout.split(b"\0")
        if path
    ]


def collect_model_patch(checkout: Path, include_untracked: bool) -> str:
    """Collect a complete binary-safe patch without mutating the Git index."""
    patch = git(checkout, "diff", "--binary", "--no-ext-diff", "HEAD")
    pending_untracked = untracked_files(checkout)
    if pending_untracked and not include_untracked:
        preview = ", ".join(pending_untracked[:5])
        suffix = " ..." if len(pending_untracked) > 5 else ""
        raise RuntimeError(
            f"untracked files found in {checkout}: {preview}{suffix}; rerun with --include-untracked after review"
        )
    for relative_path in pending_untracked:
        addition = git(
            checkout,
            "diff",
            "--binary",
            "--no-index",
            "--",
            "/dev/null",
            relative_path,
            accepted_exit_codes={0, 1},
        )
        if addition and not patch.endswith("\n"):
            patch += "\n"
        patch += addition
    return patch


def write_jsonl(path: Path, records: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest.resolve()
    rows = lib.load_manifest(manifest_path, args.expected_count)
    plan = lib.read_json(args.run_plan.resolve())
    if plan.get("manifest_sha256") != lib.sha256_file(manifest_path):
        raise RuntimeError("run plan does not match the safe manifest")
    task_by_instance = {task.get("instance_id"): task for task in plan.get("tasks", [])}
    if len(task_by_instance) != args.expected_count:
        raise RuntimeError("run plan has the wrong task count")

    agent_key = lib.agent_plan_key(args.agent)
    records: list[dict[str, str]] = []
    empty_patches: list[str] = []
    for row in rows:
        task = task_by_instance.get(row["instance_id"])
        if not isinstance(task, dict):
            raise RuntimeError(f"run plan has no task for {row['instance_id']}")
        checkout_path = task.get("agent_workspaces", {}).get(agent_key)
        if not isinstance(checkout_path, str):
            raise RuntimeError(f"run plan has no {args.agent} worktree for {row['instance_id']}")
        checkout = Path(checkout_path).resolve()
        if not checkout.is_dir():
            raise RuntimeError(f"worktree does not exist for {row['instance_id']}: {checkout}")
        actual_commit = git(checkout, "rev-parse", "HEAD").strip()
        if actual_commit != row["base_commit"]:
            raise RuntimeError(
                f"worktree commit mismatch for {row['instance_id']}: expected {row['base_commit']}, got {actual_commit}"
            )
        patch = collect_model_patch(checkout, args.include_untracked)
        if not patch:
            empty_patches.append(row["instance_id"])
        records.append(
            {
                "instance_id": row["instance_id"],
                "model_name_or_path": args.model_name,
                "model_patch": patch,
            }
        )

    if args.require_change and empty_patches:
        raise RuntimeError("empty patches for: " + ", ".join(empty_patches))

    output_dir = args.output_dir.resolve()
    jsonl_path = output_dir / f"predictions-{args.agent}.jsonl"
    json_path = output_dir / f"predictions-{args.agent}.json"
    write_jsonl(jsonl_path, records)
    lib.write_json(json_path, records)
    print(f"Wrote {len(records)} predictions to {jsonl_path}")
    print(f"Wrote sb-cli JSON list to {json_path}")
    if empty_patches:
        print("Empty patches: " + ", ".join(empty_patches))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
