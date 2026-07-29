#!/usr/bin/env python3
"""Capture one prewarmed package-manager cache as a small, attestable case artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


class CacheCaptureError(RuntimeError):
    """Raised when a prewarmed dependency cache is not safe to freeze for a case."""


_CASE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*$")
_COMMIT = re.compile(r"[a-f0-9]{40}$")
_IMAGE = re.compile(r"[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{64}$")
_CACHE_KINDS = frozenset({"gradle", "m2", "npm", "yarn", "pip"})


def _safe_text(value: str, label: str, pattern: re.Pattern[str]) -> str:
    normalized = str(value or "").strip().lower()
    if not pattern.fullmatch(normalized):
        raise CacheCaptureError(f"{label} is invalid")
    return normalized


def _require_tree(path: Path, label: str) -> Path:
    root = Path(path).resolve()
    if not root.is_dir() or root.is_symlink():
        raise CacheCaptureError(f"{label} is unavailable")
    for current, directories, files in os.walk(root, followlinks=False):
        current_path = Path(current)
        if current_path.is_symlink() or any((current_path / name).is_symlink() for name in [*directories, *files]):
            raise CacheCaptureError(f"{label} must not contain symlinks")
    return root


def _git(repository: Path, *arguments: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise CacheCaptureError(completed.stdout.strip() or "prepared repository is unreadable")
    return completed.stdout


def _tree_sha256(root: Path) -> tuple[str, int, int]:
    digest = hashlib.sha256()
    file_count = 0
    byte_count = 0
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise CacheCaptureError("prepared cache must not contain symlinks")
        if path.is_file():
            relative = path.relative_to(root).as_posix().encode("utf-8")
            contents = path.read_bytes()
            digest.update(relative)
            digest.update(b"\0")
            digest.update(contents)
            digest.update(b"\0")
            file_count += 1
            byte_count += len(contents)
    return "sha256:" + digest.hexdigest(), file_count, byte_count


def _make_agent_readable(root: Path) -> None:
    for path in sorted(root.rglob("*")):
        if path.is_dir():
            os.chmod(path, 0o755)
        elif path.is_file():
            os.chmod(path, 0o644)
    os.chmod(root, 0o755)


def capture_dependency_cache(
    case_id: str,
    base_commit: str,
    toolchain_image: str,
    prepared_repository: Path,
    cache_kind: str,
    cache_source: Path,
    destination: Path,
) -> dict[str, Any]:
    """Copy one verified cache into ``destination/<kind>`` without embedding a repository image."""

    safe_case_id = _safe_text(case_id, "case ID", _CASE_ID)
    safe_base = _safe_text(base_commit, "base commit", _COMMIT)
    safe_image = str(toolchain_image or "").strip().lower()
    if not _IMAGE.fullmatch(safe_image):
        raise CacheCaptureError("toolchain image must use an immutable sha256 digest")
    safe_kind = str(cache_kind or "").strip().lower()
    if safe_kind not in _CACHE_KINDS:
        raise CacheCaptureError("cache kind must be one of gradle, m2, npm, yarn or pip")
    repository = _require_tree(Path(prepared_repository), "prepared repository")
    if _git(repository, "rev-parse", "HEAD").strip().lower() != safe_base:
        raise CacheCaptureError("prepared repository HEAD does not match the declared base commit")
    source = _require_tree(Path(cache_source), "prewarmed cache")
    target = Path(destination).resolve()
    if target.exists() or target.is_symlink():
        raise CacheCaptureError("cache destination must not already exist")
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="." + target.name + ".staging-", dir=target.parent))
    try:
        frozen_cache = staging / safe_kind
        shutil.copytree(source, frozen_cache)
        _make_agent_readable(frozen_cache)
        tree_digest, file_count, byte_count = _tree_sha256(frozen_cache)
        manifest = {
            "caseId": safe_case_id,
            "baseCommit": safe_base,
            "toolchainImage": safe_image,
            "cacheKind": safe_kind,
            "cacheTreeSha256": tree_digest,
            "fileCount": file_count,
            "byteCount": byte_count,
        }
        (staging / "dependency-cache.json").write_text(
            json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8"
        )
        os.chmod(staging / "dependency-cache.json", 0o644)
        staging.rename(target)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return {
        "caseId": safe_case_id,
        "baseCommit": safe_base,
        "toolchainImage": safe_image,
        "cacheKind": safe_kind,
        "cacheTreeSha256": tree_digest,
        "assetRoot": str(target),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture one prepared dependency cache for a coding benchmark case.")
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--toolchain-image", required=True)
    parser.add_argument("--prepared-repository", required=True)
    parser.add_argument("--cache-kind", required=True)
    parser.add_argument("--cache-source", required=True)
    parser.add_argument("--destination", required=True)
    args = parser.parse_args()
    result = capture_dependency_cache(
        args.case_id, args.base_commit, args.toolchain_image, Path(args.prepared_repository),
        args.cache_kind, Path(args.cache_source), Path(args.destination),
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CacheCaptureError as error:
        print(f"dependency cache capture error: {error}", file=sys.stderr)
        raise SystemExit(2)
