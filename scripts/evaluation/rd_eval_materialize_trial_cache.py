#!/usr/bin/env python3
"""Create a short-lived trial cache from one immutable, prewarmed cache artifact."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    from scripts.evaluation.rd_eval_capture_dependency_cache import CacheCaptureError, _tree_sha256
except ModuleNotFoundError:  # Direct script execution does not put the repository root on sys.path.
    from rd_eval_capture_dependency_cache import CacheCaptureError, _tree_sha256


class TrialCacheError(RuntimeError):
    """Raised when a prepared cache cannot be copied safely into a Trial workspace."""


def _read_manifest(root: Path) -> dict[str, Any]:
    manifest_path = root / "dependency-cache.json"
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise TrialCacheError("dependency cache manifest is unavailable")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise TrialCacheError("dependency cache manifest is unreadable") from error
    if not isinstance(manifest, dict):
        raise TrialCacheError("dependency cache manifest is unreadable")
    return manifest


def _require_match(manifest: dict[str, Any], field: str, expected: str) -> str:
    actual = str(manifest.get(field, "")).strip()
    if actual != expected:
        raise TrialCacheError(f"dependency cache {field} does not match the frozen Trial")
    return actual


def materialize_trial_cache(
    cache_artifact: Path,
    case_id: str,
    base_commit: str,
    toolchain_image: str,
    destination: Path,
) -> dict[str, Any]:
    """Verify a host-only cache artifact, then atomically copy it to one writable Trial cache."""

    root = Path(cache_artifact).resolve()
    if not root.is_dir() or root.is_symlink():
        raise TrialCacheError("dependency cache artifact is unavailable")
    manifest = _read_manifest(root)
    safe_case_id = _require_match(manifest, "caseId", str(case_id).strip().lower())
    safe_base = _require_match(manifest, "baseCommit", str(base_commit).strip().lower())
    safe_image = _require_match(manifest, "toolchainImage", str(toolchain_image).strip().lower())
    cache_kind = str(manifest.get("cacheKind", "")).strip().lower()
    if cache_kind not in {"gradle", "m2", "npm", "yarn", "pip"}:
        raise TrialCacheError("dependency cache kind is unsupported")
    source = root / cache_kind
    if not source.is_dir() or source.is_symlink():
        raise TrialCacheError("dependency cache payload is unavailable")
    try:
        source_digest, _, _ = _tree_sha256(source)
    except CacheCaptureError as error:
        raise TrialCacheError(str(error)) from error
    if source_digest != str(manifest.get("cacheTreeSha256", "")).strip():
        raise TrialCacheError("dependency cache digest does not match its manifest")
    target = Path(destination).resolve()
    if target.exists() or target.is_symlink():
        raise TrialCacheError("trial cache destination must not already exist")
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="." + target.name + ".staging-", dir=target.parent))
    try:
        shutil.copytree(source, staging / cache_kind)
        staged_digest, _, _ = _tree_sha256(staging / cache_kind)
        if staged_digest != source_digest:
            raise TrialCacheError("trial cache copy digest does not match the immutable source")
        staging.rename(target)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return {
        "caseId": safe_case_id,
        "baseCommit": safe_base,
        "toolchainImage": safe_image,
        "cacheKind": cache_kind,
        "cacheTreeSha256": source_digest,
        "trialCache": str(target),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Materialize a verified local dependency cache for one trial.")
    parser.add_argument("--cache-artifact", required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--toolchain-image", required=True)
    parser.add_argument("--destination", required=True)
    args = parser.parse_args()
    result = materialize_trial_cache(
        Path(args.cache_artifact), args.case_id, args.base_commit, args.toolchain_image, Path(args.destination)
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except TrialCacheError as error:
        print(f"trial cache materialization error: {error}", file=sys.stderr)
        raise SystemExit(2)
