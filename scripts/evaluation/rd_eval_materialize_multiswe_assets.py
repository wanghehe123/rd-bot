#!/usr/bin/env python3
"""Materialize Multi-SWE Gold and runtime-withheld patches into a trusted host-only area."""

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

try:
    from scripts.evaluation.rd_eval_import_public_anchors import read_trusted_dataset
except ModuleNotFoundError:  # Direct `python scripts/evaluation/...` execution has only this directory on sys.path.
    from rd_eval_import_public_anchors import read_trusted_dataset


class MaterializationError(RuntimeError):
    """Raised when trusted Multi-SWE assets cannot be isolated from the agent repository."""


_COMMIT = re.compile(r"[a-f0-9]{40}")
_CASE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def _sha256(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def _git(repository: Path, arguments: list[str]) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise MaterializationError(completed.stdout.strip() or "Git command failed")
    return completed.stdout


def _expected_tests(row: dict[str, Any], field: str, case_id: str) -> list[str]:
    value = row.get(field, [])
    if isinstance(value, list) and all(isinstance(test_id, str) and test_id.strip() for test_id in value):
        return [test_id.strip() for test_id in value]
    if isinstance(value, dict) and all(isinstance(test_id, str) and test_id.strip() for test_id in value):
        return sorted(test_id.strip() for test_id in value)
    else:
        raise MaterializationError(f"case {case_id} has malformed {field}")


def _verify_patch_sequence(repository: Path, runtime_patch: Path, gold_patch: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="rd-eval-multiswe-oracle-check-") as temp_dir:
        verifier = Path(temp_dir) / "verifier"
        clone = subprocess.run(
            ["git", "clone", "--no-hardlinks", "--quiet", str(repository), str(verifier)],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if clone.returncode != 0:
            raise MaterializationError("cannot create isolated Gold-patch verification repository")
        for patch, label in ((runtime_patch, "runtime withheld test"), (gold_patch, "Gold fix")):
            applied = subprocess.run(
                ["git", "apply", "--check", "--binary", str(patch)],
                cwd=verifier,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            if applied.returncode != 0:
                raise MaterializationError(f"{label} patch does not apply to the prepared base")
            applied = subprocess.run(
                ["git", "apply", "--binary", str(patch)],
                cwd=verifier,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            if applied.returncode != 0:
                raise MaterializationError(f"{label} patch cannot be applied to the prepared base")


def materialize_case_assets(
    trusted_dataset: Path,
    case_id: str,
    prepared_repository: Path,
    destination: Path,
) -> dict[str, Any]:
    """Copy protected patches to a mode-0400 host-only directory after exact-base validation."""

    safe_case_id = str(case_id or "").strip()
    if not _CASE_ID.fullmatch(safe_case_id):
        raise MaterializationError("case ID is unsafe")
    rows, source_sha256 = read_trusted_dataset(Path(trusted_dataset))
    row = rows.get(safe_case_id)
    if row is None:
        raise MaterializationError("case is unavailable in the trusted dataset")
    base = row.get("base")
    base_commit = str(base.get("sha", "") if isinstance(base, dict) else "").strip().lower()
    if not _COMMIT.fullmatch(base_commit):
        raise MaterializationError("case has no immutable base commit")
    repository = Path(prepared_repository).resolve()
    if not repository.is_dir() or repository.is_symlink():
        raise MaterializationError("prepared repository is unavailable")
    head = _git(repository, ["rev-parse", "HEAD"]).strip().lower()
    if head != base_commit:
        raise MaterializationError("prepared repository HEAD does not match the declared base commit")
    fix_patch = row.get("fix_patch")
    runtime_patch = row.get("test_patch")
    if not isinstance(fix_patch, str) or not fix_patch.strip():
        raise MaterializationError("case has no Gold fix patch")
    if not isinstance(runtime_patch, str) or not runtime_patch.strip():
        raise MaterializationError("case has no runtime-withheld test patch")
    target = Path(destination).resolve()
    if target.exists() or target.is_symlink():
        raise MaterializationError("trusted asset destination must not already exist")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="." + target.name + ".staging-", dir=target.parent))
    try:
        gold_path = temporary / "gold-fix.patch"
        runtime_path = temporary / "runtime-withheld.patch"
        gold_path.write_text(fix_patch, encoding="utf-8")
        runtime_path.write_text(runtime_patch, encoding="utf-8")
        os.chmod(gold_path, 0o400)
        os.chmod(runtime_path, 0o400)
        _verify_patch_sequence(repository, runtime_path, gold_path)
        contract = {
            "caseId": safe_case_id,
            "baseCommit": base_commit,
            "trustedDatasetSha256": source_sha256,
            "goldPatchSha256": _sha256(fix_patch),
            "runtimeWithheldPatchSha256": _sha256(runtime_patch),
            "expectedTests": {
                "failToPass": _expected_tests(row, "f2p_tests", safe_case_id),
                "passToPass": _expected_tests(row, "p2p_tests", safe_case_id),
                "skipToPass": _expected_tests(row, "s2p_tests", safe_case_id),
                "newToPass": _expected_tests(row, "n2p_tests", safe_case_id),
            },
        }
        contract_path = temporary / "oracle-contract.json"
        contract_path.write_text(json.dumps(contract, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        os.chmod(contract_path, 0o400)
        temporary.rename(target)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return {
        "caseId": safe_case_id,
        "assetRoot": str(target),
        "baseCommit": base_commit,
        "goldPatchSha256": _sha256(fix_patch),
        "runtimeWithheldPatchSha256": _sha256(runtime_patch),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Materialize host-only Multi-SWE Oracle assets.")
    parser.add_argument("--trusted-dataset", required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--prepared-repository", required=True)
    parser.add_argument("--destination", required=True)
    args = parser.parse_args()
    result = materialize_case_assets(
        Path(args.trusted_dataset), args.case_id, Path(args.prepared_repository), Path(args.destination)
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MaterializationError as error:
        print(f"Multi-SWE asset materialization error: {error}", file=sys.stderr)
        raise SystemExit(2)
