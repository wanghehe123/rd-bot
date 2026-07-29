#!/usr/bin/env python3
"""Author one FRESH_PRIMARY case from a private repository's real fix commit.

A fresh case cannot be imported from a published dataset, so its Gold patch and its
runtime-withheld tests have to be derived from history the model never saw. This tool
splits a single-parent fix commit along the test boundary: production changes become the
host-only Gold patch, test changes become the runtime-withheld patch, and the expected
test IDs are read back out of the withheld patch instead of being typed by hand.

The output directory matches ``rd_eval_materialize_multiswe_assets.py`` so both slices
feed the same Oracle contract.
"""

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
from typing import Any, Sequence


class FreshCaseError(RuntimeError):
    """Raised when a commit cannot become an auditable FRESH_PRIMARY case."""


_COMMIT = re.compile(r"[a-f0-9]{40}")
_CASE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_MAX_REFERENCE_LENGTH = 500

DEFAULT_TEST_PATH_PATTERNS: tuple[str, ...] = (
    "src/test/",
    "src/it/",
    "/test/",
    "/tests/",
    "/__tests__/",
    ".test.",
    ".spec.",
)

# A Gold patch must not add a dependency, retarget a build, or rewrite the harness that
# decides which tests run. Such a commit is excluded by the design rather than repaired.
_MANIFEST_NAMES: frozenset[str] = frozenset({
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "gradle.properties",
    "package.json",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "requirements.txt",
    "pyproject.toml",
    "poetry.lock",
    "go.mod",
    "go.sum",
    "cargo.toml",
    "cargo.lock",
})
_MANIFEST_PREFIXES: tuple[str, ...] = ("gradle/", ".mvn/")
_MANIFEST_STEMS: tuple[str, ...] = ("jest.config", "vitest.config", "karma.conf", "playwright.config")

_PRODUCTION_CODE_SUFFIXES: tuple[str, ...] = (".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".py")

_JAVA_TEST_ROOTS: tuple[str, ...] = ("src/test/java/", "src/it/java/")
_JAVA_ANNOTATION = re.compile(r"^\+\s*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
_JAVA_METHOD = re.compile(r"^\+\s*(?:(?:public|private|protected|static|final|default)\s+)*void\s+(\w+)\s*\(")
_JS_TEST_CASE = re.compile(r"""^\+\s*(?:it|test)(?:\.\w+)?\s*\(\s*(['"`])(.+?)\1""")
_DIFF_HEADER = re.compile(r"^\+\+\+ b/(.+)$")


def _sha256(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def _git(repository: Path, arguments: Sequence[str]) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if completed.returncode != 0:
        raise FreshCaseError(completed.stdout.strip() or "Git command failed")
    return completed.stdout


def _is_test_path(path: str, patterns: Sequence[str]) -> bool:
    return any(pattern in path for pattern in patterns)


def _is_manifest_path(path: str) -> bool:
    name = path.rsplit("/", 1)[-1].lower()
    if name in _MANIFEST_NAMES or any(name.startswith(stem) for stem in _MANIFEST_STEMS):
        return True
    return any(path.startswith(prefix) or "/" + prefix in path for prefix in _MANIFEST_PREFIXES)


def classify_commit_paths(
    paths: Sequence[str], test_path_patterns: Sequence[str] = DEFAULT_TEST_PATH_PATTERNS
) -> tuple[list[str], list[str]]:
    """Split a commit's paths into production and runtime-withheld test paths.

    Raises when the commit also rewrites a build or dependency manifest, because such a
    case would require the repair phase to resolve dependencies that the offline Trial
    environment deliberately cannot fetch.
    """

    production: list[str] = []
    tests: list[str] = []
    for path in paths:
        normalized = path.strip()
        if not normalized:
            continue
        if _is_manifest_path(normalized):
            raise FreshCaseError(f"commit changes a build or dependency manifest: {normalized}")
        if _is_test_path(normalized, test_path_patterns):
            tests.append(normalized)
        else:
            production.append(normalized)
    return production, tests


def derive_expected_test_ids(withheld_patch: str) -> list[str]:
    """Read the newly added test cases back out of the runtime-withheld patch.

    Deriving the IDs from the patch, rather than accepting a hand-written list, keeps the
    frozen expectation and the withheld tests from drifting apart.
    """

    identifiers: list[str] = []
    current_path = ""
    java_annotation_pending = False
    for line in withheld_patch.splitlines():
        header = _DIFF_HEADER.match(line)
        if header:
            current_path = header.group(1)
            java_annotation_pending = False
            continue
        if not current_path or not line.startswith("+"):
            continue
        if current_path.endswith(".java"):
            if _JAVA_ANNOTATION.match(line):
                java_annotation_pending = True
                continue
            method = _JAVA_METHOD.match(line)
            if method and java_annotation_pending:
                identifiers.append(_java_class_name(current_path) + "#" + method.group(1))
                java_annotation_pending = False
            continue
        case = _JS_TEST_CASE.match(line)
        if case:
            identifiers.append(current_path + "::" + case.group(2))
    ordered: list[str] = []
    for identifier in identifiers:
        if identifier not in ordered:
            ordered.append(identifier)
    return ordered


def _java_class_name(path: str) -> str:
    for root in _JAVA_TEST_ROOTS:
        index = path.find(root)
        if index >= 0:
            return path[index + len(root):].removesuffix(".java").replace("/", ".")
    return path.rsplit("/", 1)[-1].removesuffix(".java")


def _single_parent_base(repository: Path, fix_commit: str) -> str:
    parents = _git(repository, ["rev-list", "--parents", "-n", "1", fix_commit]).split()
    if len(parents) > 2:
        raise FreshCaseError("a merge commit cannot become a case: its base is ambiguous")
    if len(parents) < 2:
        raise FreshCaseError("commit has no parent, so it has no reproducible base")
    return parents[1].lower()


def _changed_paths(repository: Path, base_commit: str, fix_commit: str) -> list[str]:
    output = _git(repository, ["diff", "--name-only", "--no-renames", base_commit, fix_commit])
    return [line.strip() for line in output.splitlines() if line.strip()]


def _patch(repository: Path, base_commit: str, fix_commit: str, paths: Sequence[str]) -> str:
    return _git(
        repository,
        ["diff", "--binary", "--no-color", "--no-renames", base_commit, fix_commit, "--", *paths],
    )


def _verify_patch_sequence(prepared_repository: Path, withheld_patch: Path, gold_patch: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="rd-eval-fresh-oracle-check-") as temp_dir:
        verifier = Path(temp_dir) / "verifier"
        clone = subprocess.run(
            ["git", "clone", "--no-hardlinks", "--quiet", str(prepared_repository), str(verifier)],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if clone.returncode != 0:
            raise FreshCaseError("cannot create an isolated Gold-patch verification repository")
        for patch, label in ((withheld_patch, "runtime-withheld test"), (gold_patch, "Gold fix")):
            applied = subprocess.run(
                ["git", "apply", "--binary", str(patch)],
                cwd=verifier,
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            if applied.returncode != 0:
                raise FreshCaseError(f"{label} patch does not apply to the prepared base")


def author_fresh_case(
    source_repository: Path,
    fix_commit: str,
    prepared_repository: Path,
    destination: Path,
    case_id: str,
    freshness_reference: str,
    test_path_patterns: Sequence[str] = DEFAULT_TEST_PATH_PATTERNS,
    minimum_production_code_files: int = 2,
) -> dict[str, Any]:
    """Freeze one fresh case's host-only Gold patch, withheld tests and Oracle contract."""

    safe_case_id = str(case_id or "").strip()
    if not _CASE_ID.fullmatch(safe_case_id):
        raise FreshCaseError("case ID is unsafe")
    reference = str(freshness_reference or "").strip()
    if not reference or len(reference) > _MAX_REFERENCE_LENGTH:
        raise FreshCaseError("freshness evidence needs a non-empty reference of at most 500 characters")
    source = Path(source_repository).resolve()
    if not source.is_dir() or source.is_symlink():
        raise FreshCaseError("source repository is unavailable")
    prepared = Path(prepared_repository).resolve()
    if not prepared.is_dir() or prepared.is_symlink():
        raise FreshCaseError("prepared repository is unavailable")
    target = Path(destination).resolve()
    if target.exists() or target.is_symlink():
        raise FreshCaseError("fresh case asset destination must not already exist")

    resolved_fix = _git(source, ["rev-parse", str(fix_commit or "").strip()]).strip().lower()
    if not _COMMIT.fullmatch(resolved_fix):
        raise FreshCaseError("fix commit does not resolve to an immutable commit")
    base_commit = _single_parent_base(source, resolved_fix)
    head = _git(prepared, ["rev-parse", "HEAD"]).strip().lower()
    if head != base_commit:
        raise FreshCaseError("prepared repository HEAD does not match the fix commit's base commit")

    production, tests = classify_commit_paths(_changed_paths(source, base_commit, resolved_fix), test_path_patterns)
    if not tests:
        raise FreshCaseError("commit has no runtime-withheld test change, so the Oracle cannot judge it")
    code_files = [path for path in production if path.endswith(_PRODUCTION_CODE_SUFFIXES)]
    if len(code_files) < minimum_production_code_files:
        raise FreshCaseError(
            f"commit changes {len(code_files)} production files; a case needs at least "
            f"{minimum_production_code_files}"
        )
    gold_patch = _patch(source, base_commit, resolved_fix, production)
    withheld_patch = _patch(source, base_commit, resolved_fix, tests)
    if not gold_patch.strip() or not withheld_patch.strip():
        raise FreshCaseError("commit does not produce both a Gold patch and a runtime-withheld test patch")
    overlap = sorted(set(production).intersection(tests))
    if overlap:
        raise FreshCaseError(f"Gold patch and runtime-withheld tests must not share a path: {overlap[0]}")
    fail_to_pass = derive_expected_test_ids(withheld_patch)
    if not fail_to_pass:
        raise FreshCaseError("runtime-withheld patch adds no expected test case the Oracle can require")

    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="." + target.name + ".staging-", dir=target.parent))
    try:
        gold_path = temporary / "gold-fix.patch"
        withheld_path = temporary / "runtime-withheld.patch"
        gold_path.write_text(gold_patch, encoding="utf-8")
        withheld_path.write_text(withheld_patch, encoding="utf-8")
        _verify_patch_sequence(prepared, withheld_path, gold_path)
        contract = {
            "caseId": safe_case_id,
            "slice": "FRESH_PRIMARY",
            "baseCommit": base_commit,
            "fixCommit": resolved_fix,
            "freshnessEvidence": {"kind": "PRIVATE_TASK", "reference": reference},
            "goldPatchSha256": _sha256(gold_patch),
            "runtimeWithheldPatchSha256": _sha256(withheld_patch),
            "productionPaths": sorted(production),
            "runtimeWithheldPaths": sorted(tests),
            "expectedTests": {
                "failToPass": fail_to_pass,
                "passToPass": [],
                "skipToPass": [],
                "newToPass": [],
            },
        }
        contract_path = temporary / "oracle-contract.json"
        contract_path.write_text(json.dumps(contract, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        for artifact in (gold_path, withheld_path, contract_path):
            os.chmod(artifact, 0o400)
        temporary.rename(target)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return {
        "caseId": safe_case_id,
        "assetRoot": str(target),
        "baseCommit": base_commit,
        "fixCommit": resolved_fix,
        "goldPatchSha256": _sha256(gold_patch),
        "runtimeWithheldPatchSha256": _sha256(withheld_patch),
        "expectedFailToPass": fail_to_pass,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Author a host-only FRESH_PRIMARY case from a private fix commit.")
    parser.add_argument("--source-repository", required=True)
    parser.add_argument("--fix-commit", required=True)
    parser.add_argument("--prepared-repository", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--freshness-reference", required=True)
    parser.add_argument("--test-path-pattern", action="append", default=[])
    parser.add_argument("--minimum-production-code-files", type=int, default=2)
    args = parser.parse_args()
    result = author_fresh_case(
        source_repository=Path(args.source_repository),
        fix_commit=args.fix_commit,
        prepared_repository=Path(args.prepared_repository),
        destination=Path(args.destination),
        case_id=args.case_id,
        freshness_reference=args.freshness_reference,
        test_path_patterns=tuple(args.test_path_pattern) or DEFAULT_TEST_PATH_PATTERNS,
        minimum_production_code_files=args.minimum_production_code_files,
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FreshCaseError as error:
        print(f"fresh case authoring error: {error}", file=sys.stderr)
        raise SystemExit(2)
