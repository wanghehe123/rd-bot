#!/usr/bin/env python3
"""Binary-safe, policy-enforced patch extraction for coding benchmark trials."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


class PatchContractError(RuntimeError):
    """Raised when a trial patch crosses a protected or unsafe repository boundary."""


@dataclass(frozen=True)
class PatchExtraction:
    """The exact candidate patch and its immutable provenance."""

    patch: str
    sha256: str
    changed_paths: tuple[str, ...]


DEFAULT_EXCLUDED_PATHS = frozenset({".git", "build", "cache", "output", "target", "node_modules", ".venv"})


def extract_patch(
    repository: Path,
    *,
    protected_paths: Iterable[str],
    excluded_paths: Iterable[str] = DEFAULT_EXCLUDED_PATHS,
) -> PatchExtraction:
    """Extract a Git binary patch without modifying the repository index or worktree.

    Protected paths are rejected rather than silently omitted. Build/cache/output changes are
    omitted, while tracked and non-ignored new source files are represented in the returned patch.
    """

    repo = _repository_root(repository)
    protected = {_normalise_relative_path(path) for path in protected_paths}
    excluded = {_normalise_relative_path(path) for path in excluded_paths}
    changed = _changed_paths(repo)
    selected: list[str] = []
    for path in changed:
        _assert_safe_path(path)
        if _matches_any(path, protected):
            raise PatchContractError(f"protected path changed: {path}")
        if _matches_any(path, excluded):
            continue
        candidate = repo / path
        if candidate.exists() and candidate.is_symlink():
            raise PatchContractError(f"symlink changes are not permitted: {path}")
        selected.append(path)

    _reject_submodule_changes(repo, selected)
    tracked = [path for path in selected if _is_tracked_at_head(repo, path)]
    untracked = [path for path in selected if path not in tracked]
    pieces: list[str] = []
    if tracked:
        pieces.append(_git(repo, ["diff", "--binary", "--full-index", "--no-ext-diff", "HEAD", "--", *tracked]))
    for path in untracked:
        result = subprocess.run(
            ["git", "diff", "--no-index", "--binary", "--full-index", "--", os.devnull, path],
            cwd=repo,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if result.returncode not in (0, 1):
            raise PatchContractError(f"cannot render untracked patch for {path}: {result.stderr.strip()}")
        pieces.append(result.stdout)
    patch = "".join(piece if piece.endswith("\n") else piece + "\n" for piece in pieces)
    if not patch.strip():
        raise PatchContractError("candidate patch is empty")
    return PatchExtraction(
        patch=patch,
        sha256="sha256:" + hashlib.sha256(patch.encode("utf-8")).hexdigest(),
        changed_paths=tuple(selected),
    )


def verify_round_trip_apply(clean_base_repository: Path, patch: str) -> None:
    """Applies the exact binary patch in a throwaway clone of the clean base repository."""

    repo = _repository_root(clean_base_repository)
    with tempfile.TemporaryDirectory(prefix="rd-eval-patch-roundtrip-") as temp_dir:
        verifier = Path(temp_dir) / "verifier"
        clone = subprocess.run(
            ["git", "clone", "--no-hardlinks", "--quiet", str(repo), str(verifier)],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if clone.returncode != 0:
            raise PatchContractError(f"cannot create clean patch verifier: {clone.stderr.strip()}")
        result = subprocess.run(
            ["git", "apply", "--check", "--binary", "-"],
            cwd=verifier,
            check=False,
            text=True,
            input=patch,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if result.returncode != 0:
            raise PatchContractError(f"candidate patch does not round-trip apply: {result.stderr.strip()}")
        applied = subprocess.run(
            ["git", "apply", "--binary", "-"],
            cwd=verifier,
            check=False,
            text=True,
            input=patch,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if applied.returncode != 0:
            raise PatchContractError(f"candidate patch did not apply in clean verifier: {applied.stderr.strip()}")


def _repository_root(repository: Path) -> Path:
    root = Path(repository).expanduser().resolve()
    if not root.is_dir():
        raise PatchContractError("candidate repository does not exist")
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=root,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise PatchContractError("candidate repository is not a Git worktree")
    return Path(result.stdout.strip()).resolve()


def _changed_paths(repository: Path) -> list[str]:
    values = _git(repository, ["diff", "--name-status", "-z", "--find-renames", "HEAD"]).split("\0")
    paths: list[str] = []
    index = 0
    while index < len(values):
        status = values[index]
        if not status:
            break
        index += 1
        if status.startswith(("R", "C")):
            if index + 1 >= len(values):
                raise PatchContractError("malformed Git rename status")
            paths.extend((values[index], values[index + 1]))
            index += 2
        else:
            if index >= len(values):
                raise PatchContractError("malformed Git path status")
            paths.append(values[index])
            index += 1
    untracked = [value for value in _git(repository, ["ls-files", "--others", "--exclude-standard", "-z"]).split("\0") if value]
    return list(dict.fromkeys(path for path in [*paths, *untracked] if path))


def _reject_submodule_changes(repository: Path, paths: Iterable[str]) -> None:
    output = _git(repository, ["ls-files", "-s", "--"])
    submodules = {
        line.split(maxsplit=3)[3]
        for line in output.splitlines()
        if len(line.split(maxsplit=3)) == 4 and line.split(maxsplit=3)[0] == "160000"
    }
    for path in paths:
        if _matches_any(path, submodules):
            raise PatchContractError(f"submodule changes are not permitted: {path}")


def _is_tracked_at_head(repository: Path, path: str) -> bool:
    result = subprocess.run(
        ["git", "cat-file", "-e", f"HEAD:{path}"],
        cwd=repository,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def _git(repository: Path, arguments: list[str]) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise PatchContractError(f"Git command failed: {result.stderr.strip()}")
    return result.stdout


def _normalise_relative_path(value: str) -> str:
    path = str(value or "").strip().replace("\\", "/")
    _assert_safe_path(path)
    return path.rstrip("/")


def _assert_safe_path(path: str) -> None:
    pure = PurePosixPath(path)
    if not path or pure.is_absolute() or ".." in pure.parts or path.startswith("./"):
        raise PatchContractError(f"unsafe repository path: {path}")


def _matches_any(path: str, prefixes: Iterable[str]) -> bool:
    return any(path == prefix or path.startswith(prefix + "/") for prefix in prefixes)


def main() -> int:
    parser = argparse.ArgumentParser(description="Extract a policy-enforced coding benchmark patch.")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata-output", required=True)
    parser.add_argument("--protected-path", action="append", default=[])
    parser.add_argument("--excluded-path", action="append", default=[])
    args = parser.parse_args()
    extraction = extract_patch(
        Path(args.repository),
        protected_paths=args.protected_path,
        excluded_paths=args.excluded_path or DEFAULT_EXCLUDED_PATHS,
    )
    output = Path(args.output).resolve()
    metadata = Path(args.metadata_output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(extraction.patch, encoding="utf-8")
    metadata.write_text(json.dumps(asdict(extraction), sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PatchContractError as error:
        print(f"patch contract error: {error}", file=sys.stderr)
        raise SystemExit(2)
