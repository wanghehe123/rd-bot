#!/usr/bin/env python3
"""Create a private Git worktree that exposes only one benchmark base history."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


class RepositoryStageError(RuntimeError):
    """Raised when a case worktree cannot prove it contains no future repository history."""


_COMMIT = re.compile(r"[a-f0-9]{40}")


def _git(repository: Path, arguments: list[str]) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise RepositoryStageError(completed.stdout.strip() or "Git command failed")
    return completed.stdout


def _require_base_commit(value: str) -> str:
    commit = str(value or "").strip().lower()
    if not _COMMIT.fullmatch(commit):
        raise RepositoryStageError("base commit must be a 40-character immutable Git commit")
    return commit


def _commit_objects(repository: Path) -> set[str]:
    output = _git(repository, ["cat-file", "--batch-all-objects", "--batch-check=%(objectname) %(objecttype)"])
    return {
        fields[0]
        for line in output.splitlines()
        if len(fields := line.split()) == 2 and fields[1] == "commit"
    }


def _validate_private_history(repository: Path, base_commit: str) -> None:
    head = _git(repository, ["rev-parse", "HEAD"]).strip().lower()
    if head != base_commit:
        raise RepositoryStageError("prepared repository HEAD does not match the declared base commit")
    remotes = _git(repository, ["remote"]).strip()
    if remotes:
        raise RepositoryStageError("prepared repository must not retain a source remote")
    allowed_commits = set(_git(repository, ["rev-list", base_commit]).split())
    actual_commits = _commit_objects(repository)
    if actual_commits != allowed_commits:
        raise RepositoryStageError("prepared repository contains commit objects outside the declared base ancestry")


def stage_repository_at_base(source_repository: Path, base_commit: str, destination: Path) -> dict[str, Any]:
    """Fetch exactly one base history into a new private, remote-free worktree.

    The source may be a local bare mirror or a normal Git repository.  The
    destination is created atomically and is never overwritten.
    """

    source = Path(source_repository).resolve()
    commit = _require_base_commit(base_commit)
    target = Path(destination).resolve()
    if not source.is_dir() or source.is_symlink():
        raise RepositoryStageError("source repository is unavailable")
    if target.exists() or target.is_symlink():
        raise RepositoryStageError("destination must not already exist")
    try:
        _git(source, ["cat-file", "-e", commit + "^{commit}"])
    except RepositoryStageError as error:
        raise RepositoryStageError("declared base commit is unavailable in the source repository") from error
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.parent.is_symlink():
        raise RepositoryStageError("destination parent must not be a symlink")
    temporary = Path(tempfile.mkdtemp(prefix="." + target.name + ".staging-", dir=target.parent))
    try:
        _git(temporary, ["init", "-q"])
        _git(temporary, ["config", "core.logAllRefUpdates", "false"])
        _git(temporary, ["fetch", "--no-tags", "--no-recurse-submodules", str(source), commit])
        _git(temporary, ["checkout", "--detach", "--quiet", commit])
        _git(temporary, ["reflog", "expire", "--expire=now", "--all"])
        _git(temporary, ["gc", "--prune=now"])
        _validate_private_history(temporary, commit)
        temporary.rename(target)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return {
        "repositoryRoot": str(target),
        "baseCommit": commit,
        "treeCommit": _git(target, ["rev-parse", commit + "^{tree}"]).strip().lower(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Stage a future-history-free coding benchmark repository.")
    parser.add_argument("--source-repository", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--output", default="")
    args = parser.parse_args()
    result = stage_repository_at_base(Path(args.source_repository), args.base_commit, Path(args.destination))
    encoded = json.dumps(result, sort_keys=True) + "\n"
    if args.output:
        output = Path(args.output).resolve()
        if output.exists():
            raise RepositoryStageError("stage output already exists and cannot be overwritten")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RepositoryStageError as error:
        print(f"repository stage error: {error}", file=sys.stderr)
        raise SystemExit(2)
