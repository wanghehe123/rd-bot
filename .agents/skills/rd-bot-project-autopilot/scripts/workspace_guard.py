"""Read-only repository fingerprinting for the autopilot safety boundary."""

from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path
from typing import Any


class WorkspaceError(RuntimeError):
    """Raised when repository state cannot be captured or has changed."""


def _git(repo_root: Path, *args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=repo_root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise WorkspaceError(f"unable to inspect repository: git {' '.join(args)}") from exc
    return result.stdout


def _repo_root(path: Path) -> Path:
    candidate = path.expanduser().resolve()
    if not candidate.exists() or not candidate.is_dir():
        raise WorkspaceError(f"repository path does not exist: {candidate}")
    raw = _git(candidate, "rev-parse", "--show-toplevel").decode("utf-8", "replace").strip()
    if not raw:
        raise WorkspaceError("git returned an empty repository root")
    return Path(raw).resolve()


def capture(repo_root: str | Path, *, exclude_paths: list[str | Path] | None = None) -> dict[str, Any]:
    """Capture tracked diff and non-ignored untracked file content without mutating git."""

    root = _repo_root(Path(repo_root))
    excluded = [Path(item).expanduser().resolve() for item in (exclude_paths or [])]
    tracked_diff = _git(root, "diff", "--binary", "--no-ext-diff", "HEAD")
    untracked_raw = _git(root, "ls-files", "--others", "--exclude-standard", "-z")
    untracked_paths = [item for item in untracked_raw.decode("utf-8", "surrogateescape").split("\0") if item]

    digest = hashlib.sha256()
    digest.update(b"tracked-diff\0")
    digest.update(tracked_diff)
    included_untracked_count = 0
    for relative in sorted(untracked_paths):
        file_path = root / relative
        if any(file_path == path or path in file_path.parents for path in excluded):
            continue
        included_untracked_count += 1
        try:
            payload = file_path.read_bytes()
        except OSError as exc:
            raise WorkspaceError(f"unable to read untracked path: {relative}") from exc
        digest.update(b"untracked\0")
        digest.update(relative.encode("utf-8", "surrogateescape"))
        digest.update(b"\0")
        digest.update(payload)
    return {
        "repoRoot": str(root),
        "sha256": digest.hexdigest(),
        "untrackedCount": included_untracked_count,
    }


def assert_unchanged(before: dict[str, Any], after: dict[str, Any]) -> None:
    if (
        before.get("repoRoot") != after.get("repoRoot")
        or before.get("sha256") != after.get("sha256")
        or before.get("untrackedCount") != after.get("untrackedCount")
    ):
        raise WorkspaceError("workspace fingerprint changed")
