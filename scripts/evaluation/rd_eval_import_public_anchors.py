#!/usr/bin/env python3
"""Create a safe public-anchor candidate catalog from a trusted benchmark dataset.

The input dataset contains Gold fixes and runtime-withheld tests.  This importer
deliberately writes only issue-facing metadata, immutable hashes and aggregate
test counts.  Patch contents and individual withheld-test identifiers remain in
the trusted input dataset and are never copied into the repository catalog.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any


class AnchorImportError(RuntimeError):
    """Raised when a public anchor cannot be pinned without leaking protected content."""


_COMMIT = re.compile(r"[a-f0-9]{40}")
_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_LANGUAGES = {"java": "JAVA", "typescript": "TYPESCRIPT", "javascript": "JAVASCRIPT"}
_SCHEMA_VERSION = "rd-eval-public-anchor-candidates-v1"


def _sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def _sha256_text(value: str) -> str:
    return _sha256_bytes(value.encode("utf-8"))


def _canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _source_files(path: Path) -> tuple[Path, list[Path]]:
    source = Path(path).resolve()
    if source.is_symlink():
        raise AnchorImportError("trusted public dataset source must not be a symlink")
    if source.is_file():
        return source.parent, [source]
    if not source.is_dir():
        raise AnchorImportError("trusted public dataset file is unavailable")
    files = [candidate for candidate in sorted(source.rglob("*.jsonl")) if candidate.is_file() and not candidate.is_symlink()]
    if not files:
        raise AnchorImportError("trusted public dataset directory contains no JSONL data")
    return source, files


def _language_from_source_path(path: Path) -> str:
    partition_aliases = {"java": "JAVA", "javascript": "JAVASCRIPT", "js": "JAVASCRIPT", "typescript": "TYPESCRIPT", "ts": "TYPESCRIPT"}
    for part in path.parts:
        language = partition_aliases.get(part.lower())
        if language is not None:
            return language
    return ""


def _read_dataset(path: Path) -> tuple[dict[str, dict[str, Any]], str]:
    source_root, files = _source_files(path)
    rows: dict[str, dict[str, Any]] = {}
    digest = hashlib.sha256()
    for source_file in files:
        raw = source_file.read_bytes()
        if raw.startswith(b"version https://git-lfs.github.com/spec/v1\n"):
            continue
        relative = source_file.relative_to(source_root).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(raw)
        digest.update(b"\0")
        inferred_language = _language_from_source_path(source_file.relative_to(source_root))
        for index, line in enumerate(raw.decode("utf-8").splitlines(), start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise AnchorImportError(f"trusted public dataset has malformed JSON at {relative}:{index}") from error
            if not isinstance(row, dict):
                raise AnchorImportError(f"trusted public dataset row {relative}:{index} is not an object")
            instance_id = str(row.get("instance_id", "")).strip()
            if not _IDENTIFIER.fullmatch(instance_id):
                raise AnchorImportError(f"trusted public dataset row {relative}:{index} has an unsafe instance ID")
            if instance_id in rows:
                raise AnchorImportError("trusted public dataset contains duplicate instance IDs")
            if inferred_language and not str(row.get("language", "")).strip():
                row["_rdEvalSourceLanguage"] = inferred_language
            rows[instance_id] = row
    if not rows:
        raise AnchorImportError("trusted public dataset contains no instances")
    return rows, "sha256:" + digest.hexdigest()


def _required_text(row: dict[str, Any], field: str, instance_id: str) -> str:
    value = str(row.get(field, "")).strip()
    if not value:
        raise AnchorImportError(f"selected instance {instance_id} has no {field}")
    return value


def _test_count(row: dict[str, Any], field: str, instance_id: str) -> int:
    value = row.get(field, [])
    if not isinstance(value, (list, dict)):
        raise AnchorImportError(f"selected instance {instance_id} has malformed {field}")
    return len(value)


def _patch_metrics(patch: str) -> dict[str, int]:
    file_count = sum(1 for line in patch.splitlines() if line.startswith("diff --git "))
    additions = sum(1 for line in patch.splitlines() if line.startswith("+") and not line.startswith("+++ "))
    removals = sum(1 for line in patch.splitlines() if line.startswith("-") and not line.startswith("--- "))
    if file_count == 0:
        raise AnchorImportError("selected instance fix patch is empty or malformed")
    return {"fixPatchFileCount": file_count, "fixPatchAdditions": additions, "fixPatchRemovals": removals}


def _case_metadata(row: dict[str, Any]) -> dict[str, Any]:
    instance_id = _required_text(row, "instance_id", "row")
    if not _IDENTIFIER.fullmatch(instance_id):
        raise AnchorImportError("selected instance has an unsafe ID")
    organization = _required_text(row, "org", instance_id)
    repository = _required_text(row, "repo", instance_id)
    if not _IDENTIFIER.fullmatch(organization) or not _IDENTIFIER.fullmatch(repository):
        raise AnchorImportError(f"selected instance {instance_id} has an unsafe repository identity")
    base = row.get("base")
    if not isinstance(base, dict):
        raise AnchorImportError(f"selected instance {instance_id} has no base commit")
    base_commit = str(base.get("sha", "")).strip().lower()
    if not _COMMIT.fullmatch(base_commit):
        raise AnchorImportError(f"selected instance {instance_id} has a non-immutable base commit")
    declared_language = str(row.get("language", "")).strip().lower()
    language = _LANGUAGES.get(declared_language) or str(row.get("_rdEvalSourceLanguage", "")).strip()
    if language not in _LANGUAGES.values():
        raise AnchorImportError(f"selected instance {instance_id} is not a supported Java or Node anchor")
    fix_patch = row.get("fix_patch")
    test_patch = row.get("test_patch")
    if not isinstance(fix_patch, str) or not fix_patch.strip():
        raise AnchorImportError(f"selected instance {instance_id} has no Gold patch")
    if not isinstance(test_patch, str) or not test_patch.strip():
        raise AnchorImportError(f"selected instance {instance_id} has no runtime-withheld test patch")
    number = row.get("number")
    if not isinstance(number, int) or number < 1:
        raise AnchorImportError(f"selected instance {instance_id} has an invalid pull request number")
    return {
        "caseId": instance_id,
        "slice": "PUBLIC_ANCHOR",
        "language": language,
        "repositoryMirrorId": f"github-{organization}--{repository}".lower(),
        "repositoryUrl": f"https://github.com/{organization}/{repository}.git",
        "baseCommit": base_commit,
        "sourceInstance": {"org": organization, "repo": repository, "number": number, "instanceId": instance_id},
        "problem": {
            "title": _required_text(row, "title", instance_id),
            "body": str(row.get("body", "")).strip(),
        },
        "upstreamDifficulty": str(row.get("difficulty", "")).strip() or None,
        "goldPatchSha256": _sha256_text(fix_patch),
        "withheldTestPatchSha256": _sha256_text(test_patch),
        "expectedTestCounts": {
            "failToPass": _test_count(row, "f2p_tests", instance_id),
            "passToPass": _test_count(row, "p2p_tests", instance_id),
            "skipToPass": _test_count(row, "s2p_tests", instance_id),
            "newToPass": _test_count(row, "n2p_tests", instance_id),
        },
        "selectionMetrics": _patch_metrics(fix_patch),
    }


def build_public_anchor_catalog(
    source_path: Path,
    selected_instance_ids: list[str],
    *,
    dataset_id: str,
    dataset_revision: str,
) -> dict[str, Any]:
    """Return deterministic, agent-safe candidate metadata for selected public tasks."""

    selected = [str(value).strip() for value in selected_instance_ids]
    if not selected or any(not _IDENTIFIER.fullmatch(value) for value in selected):
        raise AnchorImportError("selected instance IDs must be safe and non-empty")
    if len(set(selected)) != len(selected):
        raise AnchorImportError("selected instance IDs must be unique")
    source_name = str(dataset_id).strip()
    revision = str(dataset_revision).strip().lower()
    if not source_name:
        raise AnchorImportError("public dataset ID is required")
    if not _COMMIT.fullmatch(revision):
        raise AnchorImportError("public dataset revision must be a 40-character immutable commit")
    rows, raw_dataset_sha256 = _read_dataset(Path(source_path))
    missing = [instance_id for instance_id in selected if instance_id not in rows]
    if missing:
        raise AnchorImportError(f"selected instance is unavailable: {missing[0]}")
    cases = [_case_metadata(rows[instance_id]) for instance_id in selected]
    catalog = {
        "schemaVersion": _SCHEMA_VERSION,
        "selectionStatus": "CANDIDATE_UNVERIFIED",
        "source": {
            "datasetId": source_name,
            "datasetRevision": revision,
            "rawDatasetSha256": raw_dataset_sha256,
        },
        "caseCount": len(cases),
        "cases": cases,
    }
    catalog["catalogSha256"] = _sha256_text(_canonical_json(catalog))
    return catalog


def write_public_anchor_catalog(catalog: dict[str, Any], output_path: Path) -> None:
    """Write the catalog once; preflight replacements require a new evidence file."""

    destination = Path(output_path).resolve()
    if destination.exists():
        raise AnchorImportError("public anchor catalog already exists and cannot be overwritten")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(_canonical_json(catalog) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a Gold-safe public coding benchmark candidate catalog.")
    parser.add_argument("--source", required=True, help="Trusted JSONL data path; never copied to the output catalog.")
    parser.add_argument("--selected-instance-id", action="append", required=True)
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--dataset-revision", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    catalog = build_public_anchor_catalog(
        Path(args.source),
        args.selected_instance_id,
        dataset_id=args.dataset_id,
        dataset_revision=args.dataset_revision,
    )
    write_public_anchor_catalog(catalog, Path(args.output))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AnchorImportError, UnicodeDecodeError, json.JSONDecodeError) as error:
        print(f"public anchor import error: {error}")
        raise SystemExit(2)
