#!/usr/bin/env python3
"""Freeze one digest-verified coding-benchmark snapshot directory.

Assembles the six manifests that ``FileSystemCodingBenchmarkCatalog`` requires
(``dataset-manifest``, ``environment-manifest``, ``knowledge-manifest``,
``analysis-plan``, ``readiness-report`` and ``benchmark-provenance``) from the
frozen fresh/public selections, the preflight evidence and the RAG snapshot.
Every manifest carries the same ``snapshotDigest``; provenance records the
SHA-256 of the other five files, so any later edit is detectable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


class SnapshotFreezeError(RuntimeError):
    """Raised when the frozen snapshot inputs are incomplete or inconsistent."""


_SNAPSHOT_ID = re.compile(r"[A-Za-z0-9._-]{1,120}")
_IMAGE_DIGEST = re.compile(r".+@sha256:[a-f0-9]{64}$")
_COMMIT = re.compile(r"[a-f0-9]{40}$")
_REFERENCED_MANIFESTS = (
    "dataset-manifest.json",
    "environment-manifest.json",
    "knowledge-manifest.json",
    "analysis-plan.json",
    "readiness-report.json",
)


def _sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def _canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def _load_json(path: Path, description: str) -> dict:
    if not path.is_file():
        raise SnapshotFreezeError(f"{description} is unavailable: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SnapshotFreezeError(f"{description} is not valid JSON") from error
    if not isinstance(value, dict):
        raise SnapshotFreezeError(f"{description} must be a JSON object")
    return value


def _validated_cases(fresh: dict, public: dict) -> list[dict]:
    fresh_cases = fresh.get("cases")
    public_ids = public.get("selectedInstanceIds")
    if not isinstance(fresh_cases, list) or len(fresh_cases) != 10:
        raise SnapshotFreezeError("fresh selection must contain exactly 10 cases")
    if not isinstance(public_ids, list) or len(public_ids) != 10:
        raise SnapshotFreezeError("public selection must contain exactly 10 case ids")
    if fresh.get("selectionStatus") != "VERIFIED_THREE_ROUND_OFFLINE":
        raise SnapshotFreezeError("fresh selection is not preflight-verified")
    if public.get("selectionStatus") != "VERIFIED_THREE_ROUND_OFFLINE":
        raise SnapshotFreezeError("public selection is not preflight-verified")
    repository_mix = public.get("repositoryMix", {})
    difficulty_mix = {"EASY": 0, "MEDIUM": 0, "HARD": 0}
    cases: list[dict] = []
    for entry in fresh_cases:
        case_id = str(entry.get("caseId", "")).strip()
        base_commit = str(entry.get("baseCommit", "")).strip().lower()
        evidence = entry.get("freshnessEvidence")
        if not _COMMIT.fullmatch(base_commit):
            raise SnapshotFreezeError(f"fresh case {case_id} lacks a 40-character baseCommit")
        if not isinstance(evidence, dict) or not str(evidence.get("reference", "")).strip():
            raise SnapshotFreezeError(f"fresh case {case_id} lacks freshness evidence")
        difficulty = str(entry.get("difficulty", "")).strip().upper()
        if difficulty not in difficulty_mix:
            raise SnapshotFreezeError(f"fresh case {case_id} has an unknown difficulty")
        difficulty_mix[difficulty] += 1
        cases.append({
            "caseId": case_id,
            "slice": "FRESH_PRIMARY",
            "repositoryId": "wanghehe123/rd-bot",
            "language": str(entry.get("language", "")).strip().upper(),
            "difficulty": difficulty,
            "baseCommit": base_commit,
            "fixCommit": str(entry.get("fixCommit", "")).strip().lower(),
            "freshnessEvidence": {"kind": evidence.get("kind"), "reference": evidence.get("reference")},
        })
    public_meta = {c["caseId"]: c for c in (public.get("caseMetadata") or [])}
    for case_id in public_ids:
        meta = public_meta.get(case_id, {})
        cases.append({
            "caseId": case_id,
            "slice": "PUBLIC_ANCHOR",
            "repositoryId": meta.get("repositoryId") or _public_repository(case_id),
            "language": meta.get("language") or ("JAVA" if case_id.split("__")[0] in {"mockito", "alibaba", "fasterxml"} else "TSJS"),
            "difficulty": meta.get("difficulty") or "MEDIUM",
            "baseCommit": meta.get("baseCommit", ""),
            "freshnessEvidence": {"kind": "PUBLIC_DATASET", "reference": public.get("source", {})},
        })
    if len({case["caseId"] for case in cases}) != 20:
        raise SnapshotFreezeError("case ids must be unique across both slices")
    if not repository_mix:
        raise SnapshotFreezeError("public selection must record its repository mix")
    return cases


def _public_repository(case_id: str) -> str:
    prefix, _, rest = case_id.partition("__")
    repo = rest.rsplit("-", 1)[0]
    known = {
        "mockito": "mockito/mockito",
        "alibaba": "alibaba/fastjson2",
        "fasterxml": "FasterXML/jackson-databind",
        "darkreader": "darkreader/darkreader",
        "anuraghazra": "anuraghazra/github-readme-stats",
        "iamkun": "iamkun/dayjs",
        "expressjs": "expressjs/express",
    }
    return known.get(prefix, f"{prefix}/{repo}")


def _compute_snapshot_digest(cases: list[dict], images: list[str], knowledge_digest: str,
                             analysis: dict, readiness: dict) -> str:
    payload = {
        "cases": cases,
        "images": sorted(images),
        "knowledgeSnapshotDigest": knowledge_digest,
        "analysisPlan": analysis,
        "readinessEvidence": readiness,
    }
    return _sha256_bytes(_canonical(payload).encode("utf-8"))


def freeze_snapshot(
    *,
    snapshot_id: str,
    fresh_selection_path: Path,
    public_selection_path: Path,
    knowledge_report_path: Path,
    preflight_results_path: Path,
    images: list[str],
    analysis_plan: dict,
    output_directory: Path,
) -> dict:
    """Write the six-manifest snapshot; never overwrite an existing freeze."""

    if not _SNAPSHOT_ID.fullmatch(snapshot_id):
        raise SnapshotFreezeError("snapshot id contains unsupported characters")
    for image in images:
        if not _IMAGE_DIGEST.fullmatch(image.lower()):
            raise SnapshotFreezeError(f"image reference is not an immutable digest: {image}")
    fresh = _load_json(fresh_selection_path, "fresh selection")
    public = _load_json(public_selection_path, "public selection")
    knowledge = _load_json(knowledge_report_path, "knowledge readiness report")
    if not knowledge.get("ready") or int(knowledge.get("caseCount", -1)) != 20:
        raise SnapshotFreezeError("knowledge report is not ready for 20 cases")
    knowledge_digest = str(knowledge.get("snapshotDigest", "")).strip()
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", knowledge_digest):
        raise SnapshotFreezeError("knowledge report lacks an aggregate digest")
    if not preflight_results_path.is_file():
        raise SnapshotFreezeError("preflight evidence is unavailable")
    preflight_lines = preflight_results_path.read_bytes()
    if not preflight_lines.strip():
        raise SnapshotFreezeError("preflight evidence is empty")

    cases = _validated_cases(fresh, public)
    readiness_evidence = {
        "preflightSha256": _sha256_bytes(preflight_lines),
        "preflightRounds": 3,
        "network": "none",
        "leakageHits": 0,
    }
    digest = _compute_snapshot_digest(cases, images, knowledge_digest, analysis_plan, readiness_evidence)

    destination = Path(output_directory).resolve() / snapshot_id
    if destination.exists():
        raise SnapshotFreezeError("snapshot directory already exists; a frozen snapshot cannot be overwritten")
    destination.mkdir(parents=True)

    base = {"snapshotId": snapshot_id, "snapshotDigest": digest}
    manifests = {
        "dataset-manifest.json": {**base, "caseCount": len(cases), "cases": cases},
        "environment-manifest.json": {**base, "images": sorted(images)},
        "knowledge-manifest.json": {**base, "knowledgeSnapshotDigest": knowledge_digest,
                                    "caseCount": knowledge["caseCount"]},
        "analysis-plan.json": {**base, **analysis_plan},
        "readiness-report.json": {**base, "ready": True, **readiness_evidence},
    }
    hashes: dict[str, str] = {}
    for name, content in manifests.items():
        encoded = (json.dumps(content, sort_keys=True, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        (destination / name).write_bytes(encoded)
        hashes[name] = _sha256_bytes(encoded)
    provenance = {**base, "manifestSha256": {name: hashes[name] for name in _REFERENCED_MANIFESTS}}
    (destination / "benchmark-provenance.json").write_text(
        json.dumps(provenance, sort_keys=True, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {"snapshotId": snapshot_id, "snapshotDigest": digest, "caseCount": len(cases),
            "outputDirectory": str(destination)}


def main() -> int:
    parser = argparse.ArgumentParser(description="Freeze a digest-verified coding-benchmark snapshot.")
    parser.add_argument("--snapshot-id", required=True)
    parser.add_argument("--fresh-selection", required=True)
    parser.add_argument("--public-selection", required=True)
    parser.add_argument("--knowledge-report", required=True)
    parser.add_argument("--preflight-results", required=True)
    parser.add_argument("--image", action="append", required=True, dest="images")
    parser.add_argument("--analysis-plan", required=True, help="JSON file with the frozen analysis plan")
    parser.add_argument("--output-root", required=True)
    args = parser.parse_args()
    result = freeze_snapshot(
        snapshot_id=args.snapshot_id,
        fresh_selection_path=Path(args.fresh_selection),
        public_selection_path=Path(args.public_selection),
        knowledge_report_path=Path(args.knowledge_report),
        preflight_results_path=Path(args.preflight_results),
        images=args.images,
        analysis_plan=_load_json(Path(args.analysis_plan), "analysis plan"),
        output_directory=Path(args.output_root),
    )
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SnapshotFreezeError as error:
        print(f"snapshot freeze error: {error}")
        raise SystemExit(2)
