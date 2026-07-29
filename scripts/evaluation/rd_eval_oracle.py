#!/usr/bin/env python3
"""Offline verifier contract for coding benchmark candidate patches."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


COLLECTED_TEST_IDS_PREFIX = "RD_EVAL_COLLECTED_TEST_IDS="


@dataclass(frozen=True)
class OracleResult:
    """Oracle result that never converts missing collection evidence into a pass."""

    verdict: str
    missing_test_ids: tuple[str, ...]
    duplicate_test_ids: tuple[str, ...]
    unexpected_test_ids: tuple[str, ...]
    skipped: bool
    exit_code: int


class OracleContractError(RuntimeError):
    """Raised when patch, protected-bundle, or offline-verifier integrity is violated."""


def verify_result(
    *,
    test_output: str,
    expected_test_ids: Iterable[str],
    exit_code: int = 0,
) -> OracleResult:
    """Verifies exact, once-only test collection together with the process outcome."""

    expected = tuple(str(value).strip() for value in expected_test_ids if str(value).strip())
    if not expected or len(set(expected)) != len(expected):
        raise OracleContractError("expected test IDs must be non-empty and unique")
    collected = _collected_test_ids(test_output)
    counts = {test_id: collected.count(test_id) for test_id in expected}
    missing = tuple(test_id for test_id, count in counts.items() if count == 0)
    duplicate = tuple(test_id for test_id, count in counts.items() if count > 1)
    unexpected = tuple(sorted(set(collected).difference(expected)))
    skipped = " skipped" in test_output.lower() or "skipped " in test_output.lower()
    verdict = "PASS" if not (missing or duplicate or unexpected or skipped or exit_code != 0) else "TEST_FAIL"
    return OracleResult(verdict, missing, duplicate, unexpected, skipped, exit_code)


def run_oracle(
    *,
    verifier_repository: Path,
    patch_path: Path,
    protected_bundle: Path | None,
    protected_target: str | None,
    protected_test_patch: Path | None = None,
    command: list[str],
    expected_test_ids: Iterable[str],
    network_mode: str,
    integrity_paths: Iterable[str] = (),
) -> OracleResult:
    """Applies only the candidate patch, then injects one protected test representation offline."""

    if network_mode != "none":
        raise OracleContractError("oracle network mode must be none")
    repository = Path(verifier_repository).resolve()
    if not repository.is_dir():
        raise OracleContractError("verifier repository does not exist")
    patch = Path(patch_path).resolve()
    candidate_patch = patch.read_text(encoding="utf-8")
    bundle: Path | None = None
    target = ""
    source_hashes: dict[str, str] = {}
    protected_paths: set[str]
    runtime_patch: Path | None = None
    if (protected_bundle is None) == (protected_test_patch is None):
        raise OracleContractError("exactly one protected test bundle or protected runtime test patch is required")
    if protected_bundle is not None:
        bundle = Path(protected_bundle).resolve()
        if not bundle.is_dir() or bundle.is_symlink():
            raise OracleContractError("protected test bundle is unavailable")
        target = _safe_relative_path(str(protected_target or ""))
        source_hashes["protectedBundle"] = _tree_sha256(bundle)
        protected_paths = {target}
    elif protected_test_patch is not None:
        runtime_patch = Path(protected_test_patch).resolve()
        if not runtime_patch.is_file() or runtime_patch.is_symlink():
            raise OracleContractError("protected runtime test patch is unavailable")
        runtime_patch_text = runtime_patch.read_text(encoding="utf-8")
        protected_paths = _patch_paths(runtime_patch_text)
        if not protected_paths:
            raise OracleContractError("protected runtime test patch declares no repository paths")
        source_hashes["protectedTestPatch"] = _path_sha256(runtime_patch)
    _reject_protected_patch_paths(candidate_patch, protected_paths)
    watched = [_safe_relative_path(path) for path in integrity_paths]
    if any(_matches_protected(path, protected_paths) for path in watched):
        raise OracleContractError("integrity paths must not overlap protected test paths")
    source_hashes.update({f"source:{path}": _path_sha256(repository / path) for path in watched})
    apply = subprocess.run(
        ["git", "apply", "--binary", str(patch)],
        cwd=repository,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if apply.returncode != 0:
        raise OracleContractError(f"candidate patch cannot be applied: {apply.stderr.strip()}")
    if bundle is not None:
        target_path = repository / target
        if target_path.exists() or target_path.is_symlink():
            raise OracleContractError("protected test target already exists after patch application")
        target_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(bundle, target_path)
    elif runtime_patch is not None:
        injected = subprocess.run(
            ["git", "apply", "--binary", str(runtime_patch)],
            cwd=repository,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if injected.returncode != 0:
            raise OracleContractError(f"protected runtime test patch cannot be applied: {injected.stderr.strip()}")
    completed = subprocess.run(command, cwd=repository, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    result = verify_result(test_output=completed.stdout, expected_test_ids=expected_test_ids, exit_code=completed.returncode)
    if bundle is not None and source_hashes["protectedBundle"] != _tree_sha256(bundle):
        raise OracleContractError("protected test bundle changed during oracle execution")
    if runtime_patch is not None and source_hashes["protectedTestPatch"] != _path_sha256(runtime_patch):
        raise OracleContractError("protected runtime test patch changed during oracle execution")
    for path in watched:
        if source_hashes[f"source:{path}"] != _path_sha256(repository / path):
            raise OracleContractError("protected oracle input changed during execution")
    return result


def _collected_test_ids(test_output: str) -> tuple[str, ...]:
    collected: list[str] = []
    for line in test_output.splitlines():
        if not line.startswith(COLLECTED_TEST_IDS_PREFIX):
            continue
        try:
            parsed = json.loads(line.removeprefix(COLLECTED_TEST_IDS_PREFIX))
        except json.JSONDecodeError as error:
            raise OracleContractError("oracle emitted malformed collected test IDs") from error
        if not isinstance(parsed, list) or not all(isinstance(value, str) and value.strip() for value in parsed):
            raise OracleContractError("oracle collected test IDs must be a non-empty string list")
        collected.extend(value.strip() for value in parsed)
    return tuple(collected)


def _reject_protected_patch_paths(patch: str, protected_paths: Iterable[str]) -> None:
    for line in patch.splitlines():
        if line.startswith("+++ b/") or line.startswith("--- a/"):
            path = line[6:].strip()
            if _matches_protected(path, protected_paths):
                raise OracleContractError("candidate patch touches a protected test path")


def _patch_paths(patch: str) -> set[str]:
    paths: set[str] = set()
    for line in patch.splitlines():
        if line.startswith("+++ b/") or line.startswith("--- a/"):
            paths.add(_safe_relative_path(line[6:].strip()))
    return paths


def _matches_protected(path: str, protected_paths: Iterable[str]) -> bool:
    return any(path == protected or path.startswith(protected + "/") for protected in protected_paths)


def _safe_relative_path(value: str) -> str:
    path = str(value or "").strip().replace("\\", "/")
    pure = PurePosixPath(path)
    if not path or pure.is_absolute() or ".." in pure.parts or path.startswith("./"):
        raise OracleContractError("protected path must be repository-relative")
    return path.rstrip("/")


def _tree_sha256(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise OracleContractError("protected bundle must not contain symlinks")
        if path.is_file():
            digest.update(path.relative_to(root).as_posix().encode("utf-8"))
            digest.update(b"\0")
            digest.update(path.read_bytes())
            digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def _path_sha256(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise OracleContractError("protected oracle input is unavailable")
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run an offline coding benchmark Oracle.")
    parser.add_argument("--verifier-repository", required=True)
    parser.add_argument("--patch", required=True)
    parser.add_argument("--protected-bundle", default="")
    parser.add_argument("--protected-target", default="")
    parser.add_argument("--protected-test-patch", default="")
    parser.add_argument("--expected-test-id", action="append", required=True)
    parser.add_argument("--command-json", required=True, help="JSON argv list; shell strings are not accepted.")
    parser.add_argument("--network-mode", required=True)
    parser.add_argument("--integrity-path", action="append", default=[])
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        command = json.loads(args.command_json)
    except json.JSONDecodeError as error:
        raise OracleContractError("oracle command must be a JSON argv list") from error
    if not isinstance(command, list) or not command or not all(isinstance(value, str) and value for value in command):
        raise OracleContractError("oracle command must be a non-empty JSON argv list")
    result = run_oracle(
        verifier_repository=Path(args.verifier_repository),
        patch_path=Path(args.patch),
        protected_bundle=Path(args.protected_bundle) if args.protected_bundle else None,
        protected_target=args.protected_target or None,
        protected_test_patch=Path(args.protected_test_patch) if args.protected_test_patch else None,
        command=command,
        expected_test_ids=args.expected_test_id,
        network_mode=args.network_mode,
        integrity_paths=args.integrity_path,
    )
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(asdict(result), sort_keys=True) + "\n", encoding="utf-8")
    return 0 if result.verdict == "PASS" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except OracleContractError as error:
        print(f"oracle contract error: {error}", file=sys.stderr)
        raise SystemExit(2)
