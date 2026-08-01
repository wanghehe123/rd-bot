#!/usr/bin/env python3
"""Readiness verifier for fully offline coding benchmark environments."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path, PurePosixPath
from typing import Any


class ReadinessError(RuntimeError):
    """Raised when a benchmark case cannot prove its offline execution contract."""


@dataclass(frozen=True)
class CaseReadinessResult:
    case_id: str
    passed: bool
    runs: tuple[dict[str, Any], ...]


def _is_sha256_digest(value: str) -> bool:
    """Return True when value is 'sha256:' followed by exactly 64 hex characters."""
    if not value.startswith("sha256:"):
        return False
    hex_part = value[len("sha256:") :]
    return bool(re.fullmatch(r"[a-f0-9]{64}", hex_part))


def _is_sha256_or_hex(value: str) -> bool:
    """Return True for sha256: prefixed or bare hex digest (64 chars)."""
    if value.startswith("sha256:"):
        return _is_sha256_digest(value)
    return bool(re.fullmatch(r"[a-f0-9]{64}", value))


def validate_case_contract(case: dict[str, Any]) -> None:
    """Validate a single case against spec §7.2 Case Manifest field contract.

    Enforces:
    - caseId safe identifier
    - required digest fields with sha256: prefix
    - required command arrays (non-empty string lists)
    - resource quota fields are non-empty
    - enumeration fields use allowed values
    - freshnessEvidence structure for FRESH_PRIMARY
    """

    case_id = str(case.get("caseId", "")).strip()
    if not case_id or not all(
        character.isalnum() or character in "._-"
        for character in case_id
    ):
        raise ReadinessError("caseId must be a safe immutable identifier")

    # Spec §7.2: baseCommit — already validated in validate_benchmark_config (40-char hex)
    base_commit = str(case.get("baseCommit", "")).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", base_commit):
        raise ReadinessError(
            f"[{case_id}] baseCommit must be a 40-character hexadecimal Git commit hash"
        )

    # Spec §7.2: repositoryMirrorId — required, non-empty string
    repository_mirror_id = str(case.get("repositoryMirrorId", "")).strip()
    if not repository_mirror_id:
        raise ReadinessError(f"[{case_id}] repositoryMirrorId is required")

    # Spec §7.2: platform — required, must be linux/amd64
    platform = str(case.get("platform", "")).strip()
    if platform not in {"linux/amd64", "linux/arm64"}:
        raise ReadinessError(
            f"[{case_id}] platform must be 'linux/amd64' or 'linux/arm64', got '{platform}'"
        )

    # Spec §7.2: agentImageDigest — sha256: prefix + 64 hex chars
    agent_image_digest = str(case.get("agentImageDigest", "")).strip()
    if not agent_image_digest:
        raise ReadinessError(
            f"[{case_id}] agentImageDigest is required"
        )
    if not _is_sha256_digest(agent_image_digest):
        raise ReadinessError(
            f"[{case_id}] agentImageDigest must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: verifierImageDigest — sha256: prefix + 64 hex chars
    verifier_image_digest = str(case.get("verifierImageDigest", "")).strip()
    if not verifier_image_digest:
        raise ReadinessError(f"[{case_id}] verifierImageDigest is required")
    if not _is_sha256_digest(verifier_image_digest):
        raise ReadinessError(
            f"[{case_id}] verifierImageDigest must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: dependencyBundleSha256 — sha256: prefix + 64 hex chars
    dep_bundle = str(case.get("dependencyBundleSha256", "")).strip()
    if not dep_bundle:
        raise ReadinessError(f"[{case_id}] dependencyBundleSha256 is required")
    if not _is_sha256_digest(dep_bundle):
        raise ReadinessError(
            f"[{case_id}] dependencyBundleSha256 must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: oracleTestBundleSha256 — sha256: prefix + 64 hex chars
    oracle_bundle = str(case.get("oracleTestBundleSha256", "")).strip()
    if not oracle_bundle:
        raise ReadinessError(f"[{case_id}] oracleTestBundleSha256 is required")
    if not _is_sha256_digest(oracle_bundle):
        raise ReadinessError(
            f"[{case_id}] oracleTestBundleSha256 must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: toolchainSha256 — sha256: prefix + 64 hex chars
    toolchain_sha = str(case.get("toolchainSha256", "")).strip()
    if not toolchain_sha:
        raise ReadinessError(f"[{case_id}] toolchainSha256 is required")
    if not _is_sha256_digest(toolchain_sha):
        raise ReadinessError(
            f"[{case_id}] toolchainSha256 must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: privateRepoTreeSha256 — sha256: prefix + 64 hex chars
    repo_tree_sha = str(case.get("privateRepoTreeSha256", "")).strip()
    if not repo_tree_sha:
        raise ReadinessError(f"[{case_id}] privateRepoTreeSha256 is required")
    if not _is_sha256_digest(repo_tree_sha):
        raise ReadinessError(
            f"[{case_id}] privateRepoTreeSha256 must be 'sha256:' followed by 64 hex characters"
        )

    # Spec §7.2: agentTestCommands — non-empty list of non-empty strings
    agent_test_commands = case.get("agentTestCommands")
    if not isinstance(agent_test_commands, list) or not agent_test_commands:
        raise ReadinessError(
            f"[{case_id}] agentTestCommands must be a non-empty list of strings"
        )
    if not all(
        isinstance(cmd, str) and cmd.strip()
        for cmd in agent_test_commands
    ):
        raise ReadinessError(
            f"[{case_id}] agentTestCommands must contain only non-empty strings"
        )

    # Spec §7.2: oracleTestCommands — non-empty list of non-empty strings
    oracle_test_commands = case.get("oracleTestCommands")
    if not isinstance(oracle_test_commands, list) or not oracle_test_commands:
        raise ReadinessError(
            f"[{case_id}] oracleTestCommands must be a non-empty list of strings"
        )
    if not all(
        isinstance(cmd, str) and cmd.strip()
        for cmd in oracle_test_commands
    ):
        raise ReadinessError(
            f"[{case_id}] oracleTestCommands must contain only non-empty strings"
        )

    # Spec §7.2: agentNetworkPolicy — allowed values
    agent_network_policy = str(case.get("agentNetworkPolicy", "")).strip()
    if agent_network_policy not in {
        "relay-only",
        "MODEL_RELAY_ONLY",
        "offline",
        "none",
    }:
        raise ReadinessError(
            f"[{case_id}] agentNetworkPolicy must be one of: "
            f"'relay-only', 'MODEL_RELAY_ONLY', 'offline', 'none'"
        )

    # Spec §7.2: oracleNetworkMode — allowed values
    oracle_network_mode = str(case.get("oracleNetworkMode", "")).strip()
    if oracle_network_mode not in {"offline", "none"}:
        raise ReadinessError(
            f"[{case_id}] oracleNetworkMode must be 'offline' or 'none'"
        )

    # Spec §7.2: browserQaRequired — boolean
    browser_qa = case.get("browserQaRequired")
    if not isinstance(browser_qa, bool):
        raise ReadinessError(
            f"[{case_id}] browserQaRequired must be a boolean"
        )

    # Spec §7.2: resource quota fields — non-empty strings
    for field in ("cpuLimit", "memoryLimit", "shmSize", "storageLimit"):
        value = case.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ReadinessError(
                f"[{case_id}] {field} must be a non-empty string"
            )

    # Spec §7.2: pidsLimit — positive integer
    pids_limit = case.get("pidsLimit")
    if not isinstance(pids_limit, int) or pids_limit <= 0:
        raise ReadinessError(
            f"[{case_id}] pidsLimit must be a positive integer"
        )

    # Spec §7.2: slice — required, FRESH_PRIMARY or PUBLIC_ANCHOR
    benchmark_slice = str(case.get("slice", "")).strip().upper()
    if benchmark_slice not in {"FRESH_PRIMARY", "PUBLIC_ANCHOR"}:
        raise ReadinessError(
            f"[{case_id}] slice must be 'FRESH_PRIMARY' or 'PUBLIC_ANCHOR'"
        )

    # Spec §7.2: language — required, JAVA / TSJS
    language = str(case.get("language", "")).strip().upper()
    if language not in {"JAVA", "TSJS"}:
        raise ReadinessError(
            f"[{case_id}] language must be 'JAVA' or 'TSJS'"
        )

    # Spec §7.2: difficulty — required, EASY / MEDIUM / HARD
    difficulty = str(case.get("difficulty", "")).strip().upper()
    if difficulty not in {"EASY", "MEDIUM", "HARD"}:
        raise ReadinessError(
            f"[{case_id}] difficulty must be 'EASY', 'MEDIUM', or 'HARD'"
        )

    # Spec §7.2: leakageAuditVerdict — required, CLEAN / LEAK / UNAUDITED
    leakage_verdict = str(case.get("leakageAuditVerdict", "")).strip().upper()
    if leakage_verdict not in {"CLEAN", "LEAK", "UNAUDITED"}:
        raise ReadinessError(
            f"[{case_id}] leakageAuditVerdict must be 'CLEAN', 'LEAK', or 'UNAUDITED'"
        )

    # Spec §7.2: freshnessEvidence — required for FRESH_PRIMARY
    if benchmark_slice == "FRESH_PRIMARY":
        evidence = case.get("freshnessEvidence")
        if not isinstance(evidence, dict):
            raise ReadinessError(
                f"[{case_id}] FRESH_PRIMARY cases require freshnessEvidence"
            )
        kind = str(evidence.get("kind", "")).strip().upper()
        if kind not in {"PRIVATE_TASK", "POST_CUTOFF_ISSUE"}:
            raise ReadinessError(
                f"[{case_id}] freshnessEvidence.kind must be 'PRIVATE_TASK' or "
                f"'POST_CUTOFF_ISSUE', got '{kind}'"
            )
        reference = str(evidence.get("reference", "")).strip()
        if not reference or len(reference) > 500:
            raise ReadinessError(
                f"[{case_id}] freshnessEvidence.reference must be a non-empty string "
                f"≤ 500 characters"
            )
    else:
        # PUBLIC_ANCHOR: freshnessEvidence must be dict with dataset/datasetRevision
        evidence = case.get("freshnessEvidence")
        if not isinstance(evidence, dict):
            raise ReadinessError(
                f"[{case_id}] PUBLIC_ANCHOR cases require freshnessEvidence"
            )
        kind = str(evidence.get("kind", "")).strip().upper()
        if kind not in {"PUBLIC_ANCHOR", "PUBLIC_TASK"}:
            raise ReadinessError(
                f"[{case_id}] freshnessEvidence.kind for PUBLIC_ANCHOR must be "
                f"'PUBLIC_ANCHOR' or 'PUBLIC_TASK', got '{kind}'"
            )
        dataset = str(evidence.get("dataset", "")).strip()
        dataset_revision = str(evidence.get("datasetRevision", "")).strip()
        if not dataset or not dataset_revision:
            raise ReadinessError(
                f"[{case_id}] PUBLIC_ANCHOR freshnessEvidence requires both "
                f"'dataset' and 'datasetRevision'"
            )


def validate_benchmark_config(config: dict[str, Any]) -> list[dict[str, Any]]:
    """Validate the fixed 10 fresh + 10 public benchmark composition before execution.

    Fresh and public rows must remain explicit at this boundary: a local cache of
    public SWE-bench cases cannot silently become the fresh-primary slice.
    """

    cases = config.get("cases")
    if not isinstance(cases, list) or len(cases) != 20 or not all(
        isinstance(case, dict) for case in cases
    ):
        raise ReadinessError(
            "coding benchmark config must contain exactly 20 case objects"
        )
    ids = [str(case.get("caseId", "")).strip() for case in cases]
    if any(not case_id for case_id in ids) or len(set(ids)) != 20:
        raise ReadinessError("coding benchmark case IDs must be unique")
    slices = [str(case.get("slice", "")).strip().upper() for case in cases]
    if any(
        benchmark_slice not in {"FRESH_PRIMARY", "PUBLIC_ANCHOR"}
        for benchmark_slice in slices
    ):
        raise ReadinessError(
            "every coding benchmark case must declare FRESH_PRIMARY or PUBLIC_ANCHOR"
        )
    if slices.count("FRESH_PRIMARY") != 10 or slices.count("PUBLIC_ANCHOR") != 10:
        raise ReadinessError(
            "coding benchmark config must contain exactly 10 FRESH_PRIMARY "
            "and 10 PUBLIC_ANCHOR cases"
        )
    for case, benchmark_slice in zip(cases, slices, strict=True):
        base_commit = str(case.get("baseCommit", "")).strip().lower()
        if not re.fullmatch(r"[a-f0-9]{40}", base_commit):
            raise ReadinessError(
                f"[{case.get('caseId', '?')}] every formal coding benchmark case "
                f"must declare a 40-character baseCommit"
            )
        if benchmark_slice != "FRESH_PRIMARY":
            continue
        evidence = case.get("freshnessEvidence")
        if not isinstance(evidence, dict):
            raise ReadinessError(
                f"[{case.get('caseId', '?')}] every FRESH_PRIMARY case "
                f"must declare freshnessEvidence"
            )
        kind = str(evidence.get("kind", "")).strip().upper()
        reference = str(evidence.get("reference", "")).strip()
        if (
            kind not in {"PRIVATE_TASK", "POST_CUTOFF_ISSUE"}
            or not reference
            or len(reference) > 500
        ):
            raise ReadinessError(
                f"[{case.get('caseId', '?')}] freshnessEvidence must identify "
                f"a PRIVATE_TASK or POST_CUTOFF_ISSUE reference"
            )
    return cases


def verify_base_history(case: dict[str, Any], repository_root: Path) -> None:
    """Require the declared base commit and its ancestry in the private prepared Git repository.

    Uses repositoryMirrorId (spec §7.2) to locate the case repository relative to
    the repository root. The repositoryMirrorId is treated as a directory path
    within the root (mirrors are stored as mirror-id directories).
    """

    base_commit = str(case.get("baseCommit", "")).strip().lower()
    if not re.fullmatch(r"[a-f0-9]{40}", base_commit):
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] case baseCommit must be a "
            f"40-character Git commit"
        )

    # Spec §7.2: repositoryMirrorId used as path within repositoryRoot
    repository_mirror_id = str(case.get("repositoryMirrorId", "")).strip()
    if not repository_mirror_id:
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] repositoryMirrorId is required "
            f"for base history verification"
        )

    pure_path = PurePosixPath(repository_mirror_id)
    if pure_path.is_absolute() or ".." in pure_path.parts:
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] repositoryMirrorId must stay beneath "
            f"the repository root"
        )

    root = Path(repository_root).resolve()
    candidate = (root / pure_path).resolve()
    if not candidate.is_relative_to(root) or not candidate.is_dir():
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] prepared repository path "
            f"'{repository_mirror_id}' is unavailable under the repository root"
        )

    object_check = subprocess.run(
        ["git", "-C", str(candidate), "cat-file", "-e", base_commit + "^{commit}"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if object_check.returncode != 0:
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] declared baseCommit object is "
            f"unavailable in the prepared repository"
        )

    ancestry_check = subprocess.run(
        [
            "git",
            "-C",
            str(candidate),
            "merge-base",
            "--is-ancestor",
            base_commit,
            "HEAD",
        ],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if ancestry_check.returncode != 0:
        raise ReadinessError(
            f"[{case.get('caseId', '?')}] declared baseCommit is not an ancestor "
            f"of the prepared repository HEAD"
        )


def verify_case(
    case: dict[str, Any], repository_root: Path, timeout_seconds: int = 300
) -> CaseReadinessResult:
    """Runs agent and oracle test commands with package-manager offline guards enabled.

    Executes agentTestCommands and oracleTestCommands (spec §7.2 field names)
    in the case repository.  Unlike the old spec, oracleTestCommands is run
    once — the BASE/TEST/FIX three-state verification is the responsibility of
    the offline harness that the Oracle runs independently.
    """

    validate_case_contract(case)

    case_id = str(case.get("caseId", "")).strip()
    repository_mirror_id = str(case.get("repositoryMirrorId", "")).strip()
    mirror_root = (repository_root / repository_mirror_id).resolve()

    if not mirror_root.is_relative_to(repository_root.resolve()) or not mirror_root.is_dir():
        raise ReadinessError(
            f"[{case_id}] case repository mirror '{repository_mirror_id}' is unavailable"
        )

    environment = os.environ.copy()
    environment.update({
        "PIP_NO_INDEX": "1",
        "PIP_DISABLE_PIP_VERSION_CHECK": "1",
        "npm_config_offline": "true",
        "YARN_ENABLE_NETWORK": "0",
        "RD_EVAL_OFFLINE": "1",
    })

    runs: list[dict[str, Any]] = []

    # agentTestCommands (spec §7.2) — run once per round (3 rounds)
    for round_number in range(1, 4):
        agent_commands = case.get("agentTestCommands", [])
        completed = subprocess.run(
            agent_commands,
            cwd=mirror_root,
            env=environment,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout_seconds,
        )
        runs.append({
            "round": round_number,
            "phase": "AGENT_TEST",
            "returnCode": completed.returncode,
            "outputSha256": "sha256:"
            + hashlib.sha256(completed.stdout.encode("utf-8")).hexdigest(),
        })
        if completed.returncode != 0:
            return CaseReadinessResult(case_id, False, tuple(runs))

    # oracleTestCommands (spec §7.2) — run once
    oracle_commands = case.get("oracleTestCommands", [])
    completed = subprocess.run(
        oracle_commands,
        cwd=mirror_root,
        env=environment,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout_seconds,
    )
    runs.append({
        "round": 1,
        "phase": "ORACLE_TEST",
        "returnCode": completed.returncode,
        "outputSha256": "sha256:"
        + hashlib.sha256(completed.stdout.encode("utf-8")).hexdigest(),
    })
    if completed.returncode != 0:
        return CaseReadinessResult(case_id, False, tuple(runs))

    return CaseReadinessResult(case_id, True, tuple(runs))


def verify_config(
    config_path: Path, output_path: Path, timeout_seconds: int = 300
) -> dict[str, Any]:
    """Verifies every declared case before writing a deterministic readiness report."""

    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    cases = validate_benchmark_config(config)
    root = Path(config.get("repositoryRoot", Path(config_path).parent)).resolve()

    for case in cases:
        verify_base_history(case, root)

    results = [
        verify_case(case, root, timeout_seconds) for case in cases
    ]

    report = {
        "ready": all(result.passed for result in results),
        "caseCount": len(results),
        "results": [asdict(result) for result in results],
    }

    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify offline coding benchmark readiness."
    )
    parser.add_argument("verify", nargs="?")
    parser.add_argument(
        "--config",
        required=True,
        help="JSON content is accepted even when this file uses a .yaml suffix.",
    )
    parser.add_argument("--output", default="")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    args = parser.parse_args()
    output = (
        Path(args.output)
        if args.output
        else Path(args.config).with_name("readiness-report.json")
    )
    report = verify_config(
        Path(args.config), output, max(1, args.timeout_seconds)
    )
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ReadinessError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(f"readiness error: {error}", file=sys.stderr)
        raise SystemExit(2)
