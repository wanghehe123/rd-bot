#!/usr/bin/env python3
"""Clone the selected repositories once and create exact detached worktrees.

The script is resumable and intentionally never removes or resets an existing
worktree. An existing path must already point at the requested clean commit.
"""

from __future__ import annotations

import argparse
import fcntl
import os
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import swebench_lib as lib


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=lib.DEFAULT_MANIFEST_PATH)
    parser.add_argument("--output-dir", type=Path, default=lib.DEFAULT_RUN_ROOT)
    parser.add_argument("--root", type=Path, default=lib.DEFAULT_CHECKOUT_ROOT)
    parser.add_argument("--expected-count", type=int, default=10)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def command_text(command: list[str]) -> str:
    return " ".join(subprocess.list2cmdline([part]) for part in command)


def run(command: list[str], dry_run: bool, capture: bool = False) -> str:
    print("+ " + command_text(command))
    if dry_run:
        return ""
    completed = subprocess.run(
        command,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )
    if completed.returncode != 0:
        detail = ""
        if capture:
            detail = "\n" + (completed.stderr or completed.stdout or "").strip()
        raise RuntimeError(f"command failed with exit code {completed.returncode}: {command_text(command)}{detail}")
    return (completed.stdout or "").strip() if capture else ""


def git_value(path: Path, *args: str, dry_run: bool) -> str:
    return run(["git", "-C", str(path), *args], dry_run=dry_run, capture=True)


def stable_worktree_status(path: Path, dry_run: bool) -> str:
    """Avoid rejecting a just-materialized partial-clone worktree on a transient scan."""
    first = git_value(path, "status", "--porcelain", "--untracked-files=all", dry_run=dry_run)
    if dry_run or not first:
        return first
    time.sleep(0.5)
    second = git_value(path, "status", "--porcelain", "--untracked-files=all", dry_run=False)
    if not second:
        print(f"Git status stabilized after checkout scan: {path}")
    return second


@contextmanager
def exclusive_clone_lock(root: Path):
    """Prevent concurrent setup runs from sharing one promisor object store."""
    root.mkdir(parents=True, exist_ok=True)
    lock_path = root / ".swebench-clone.lock"
    with lock_path.open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError(
                f"another clone_swebench_worktrees.py process is active for {root}; wait for it to finish"
            ) from error
        lock.seek(0)
        lock.truncate()
        lock.write(f"pid={os.getpid()}\n")
        lock.flush()
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def ensure_repository_clone(root: Path, row: dict[str, Any], dry_run: bool) -> Path:
    repository_path = lib.repository_clone_path(root, row)
    if repository_path.exists():
        if not (repository_path / ".git").exists():
            raise RuntimeError(f"repository path exists but is not a Git clone: {repository_path}")
        if dry_run:
            print(f"Would reuse existing repository clone: {repository_path}")
            return repository_path
        remote = git_value(repository_path, "remote", "get-url", "origin", dry_run=dry_run)
        if remote.rstrip("/") != lib.repository_url(row).rstrip("/"):
            raise RuntimeError(
                f"repository path has a different origin: {repository_path}; expected {lib.repository_url(row)}, got {remote}"
            )
        return repository_path

    if not dry_run:
        repository_path.parent.mkdir(parents=True, exist_ok=True)
    run(
        [
            "git",
            "clone",
            "--filter=blob:none",
            "--depth",
            "1",
            "--no-checkout",
            "--no-tags",
            lib.repository_url(row),
            str(repository_path),
        ],
        dry_run=dry_run,
    )
    return repository_path


def ensure_commit(repository_path: Path, commit: str, dry_run: bool) -> None:
    if dry_run:
        run(
            [
                "git",
                "-C",
                str(repository_path),
                "fetch",
                "--quiet",
                "--depth",
                "1",
                "--filter=blob:none",
                "--no-tags",
                "origin",
                commit,
            ],
            dry_run=True,
        )
        return
    environment = dict(os.environ)
    environment["GIT_NO_LAZY_FETCH"] = "1"
    has_commit = subprocess.run(
        ["git", "-C", str(repository_path), "cat-file", "-e", f"{commit}^{{commit}}"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=environment,
    ).returncode == 0
    if not has_commit:
        run(
            [
                "git",
                "-C",
                str(repository_path),
                "fetch",
                "--quiet",
                "--depth",
                "1",
                "--filter=blob:none",
                "--no-tags",
                "origin",
                commit,
            ],
            dry_run=False,
        )
    verified = subprocess.run(
        ["git", "-C", str(repository_path), "cat-file", "-e", f"{commit}^{{commit}}"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=environment,
    ).returncode == 0
    if not verified:
        raise RuntimeError(f"requested commit is still unavailable after fetch: {commit}")


def ensure_worktree(repository_path: Path, row: dict[str, Any], target: Path, dry_run: bool) -> dict[str, Any]:
    expected_commit = row["base_commit"]
    if target.exists():
        if not (target / ".git").exists():
            raise RuntimeError(f"worktree path exists but is not a Git worktree: {target}")
        actual_commit = git_value(target, "rev-parse", "HEAD", dry_run=dry_run)
        if not dry_run and actual_commit != expected_commit:
            raise RuntimeError(
                f"existing worktree has the wrong commit: {target}; expected {expected_commit}, got {actual_commit}"
            )
        status = stable_worktree_status(target, dry_run=dry_run)
        if not dry_run and status:
            raise RuntimeError(f"existing worktree is not clean: {target}")
        return {"path": str(target), "head": actual_commit or expected_commit, "reused": True}

    if not dry_run:
        target.parent.mkdir(parents=True, exist_ok=True)
    run(
        ["git", "-C", str(repository_path), "worktree", "add", "--detach", str(target), expected_commit],
        dry_run=dry_run,
    )
    actual_commit = git_value(target, "rev-parse", "HEAD", dry_run=dry_run)
    if not dry_run and actual_commit != expected_commit:
        raise RuntimeError(f"new worktree did not check out the requested commit: {target}")
    status = stable_worktree_status(target, dry_run=dry_run)
    if not dry_run and status:
        raise RuntimeError(f"new worktree is not clean: {target}")
    return {"path": str(target), "head": actual_commit or expected_commit, "reused": False}


def main() -> int:
    args = parse_args()
    rows = lib.load_manifest(args.manifest.resolve(), args.expected_count)
    root = args.root.resolve()
    output_dir = args.output_dir.resolve()
    def run_setup() -> list[dict[str, Any]]:
        reports: list[dict[str, Any]] = []
        clones: dict[str, Path] = {}
        for row in rows:
            repo = row["repo"]
            if repo not in clones:
                clones[repo] = ensure_repository_clone(root, row, args.dry_run)
            repository_path = clones[repo]
            ensure_commit(repository_path, row["base_commit"], args.dry_run)
            worktrees: dict[str, dict[str, Any]] = {}
            for agent_id in lib.AGENT_IDS:
                agent_key = lib.agent_plan_key(agent_id)
                worktree = ensure_worktree(
                    repository_path,
                    row,
                    lib.agent_checkout_path(root, row, agent_id),
                    args.dry_run,
                )
                worktrees[agent_key] = worktree
                print(f"Ready {row['instance_id']} [{agent_id}]: {worktree['path']}")
            reports.append(
                {
                    "instance_id": row["instance_id"],
                    "repo": repo,
                    "base_commit": row["base_commit"],
                    "repository_clone": str(repository_path),
                    "worktrees": worktrees,
                }
            )
        return reports

    reports = run_setup() if args.dry_run else None
    if not args.dry_run:
        with exclusive_clone_lock(root):
            reports = run_setup()
        report_path = output_dir / "clone-report.json"
        lib.write_json(
            report_path,
            {
                "created_at": lib.utc_now(),
                "root": str(root),
                "task_count": len(reports),
                "worktrees": reports,
            },
        )
        print(f"Clone report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
