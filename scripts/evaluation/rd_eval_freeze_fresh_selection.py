#!/usr/bin/env python3
"""Freeze a FRESH_PRIMARY case selection as an auditable, seeded record.

The case pool is already screened for cleanliness by the leakage audit; this step turns a
hand-reviewable shortlist into a frozen record so the selection cannot be silently changed
after any arm's result is seen. The seed is recorded even though the caller names the exact
case IDs: it anchors the stratified sampling claim and is required by the design's
freeze-then-never-replace rule.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Sequence


class FreshSelectionError(RuntimeError):
    """Raised when a fresh case selection cannot be frozen into an auditable record."""


_COMMIT = re.compile(r"[a-f0-9]{40}")
_CASE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_REQUIRED_LANGUAGES: frozenset[str] = frozenset({"JAVA", "TSJS"})
_REQUIRED_DIFFICULTIES: frozenset[str] = frozenset({"EASY", "MEDIUM", "HARD"})
_ALLOWED_DIFFICULTIES: frozenset[str] = frozenset({"EASY", "MEDIUM", "HARD"})


def _safe_case_id(value: Any) -> str:
    case_id = str(value or "").strip()
    if not _CASE_ID.fullmatch(case_id):
        raise FreshSelectionError("case ID is unsafe")
    return case_id


def _commit(value: Any, field: str) -> str:
    text = str(value or "").strip().lower()
    if not _COMMIT.fullmatch(text):
        raise FreshSelectionError(f"{field} must be a 40-character immutable commit")
    return text


def _validate_pool_entry(entry: dict[str, Any]) -> dict[str, Any]:
    case_id = _safe_case_id(entry.get("caseId"))
    base_commit = _commit(entry.get("baseCommit"), "baseCommit")
    fix_commit = _commit(entry.get("fixCommit"), "fixCommit")
    language = str(entry.get("language", "")).strip().upper()
    if language not in _REQUIRED_LANGUAGES:
        raise FreshSelectionError(f"case {case_id} declares an unsupported language")
    difficulty = str(entry.get("difficulty", "")).strip().upper()
    if difficulty not in _ALLOWED_DIFFICULTIES:
        raise FreshSelectionError(f"case {case_id} declares an unsupported difficulty")
    audit = entry.get("leakageAudit")
    verdict = str(audit.get("verdict", "")).strip().upper() if isinstance(audit, dict) else ""
    if verdict != "CLEAN":
        raise FreshSelectionError(f"case {case_id} has no CLEAN leakage audit")
    evidence = entry.get("freshnessEvidence")
    if not isinstance(evidence, dict) or not str(evidence.get("reference", "")).strip():
        raise FreshSelectionError(f"case {case_id} has no auditable freshness evidence")
    return {
        "caseId": case_id,
        "slice": "FRESH_PRIMARY",
        "baseCommit": base_commit,
        "fixCommit": fix_commit,
        "language": language,
        "difficulty": difficulty,
        "productionFileCount": int(entry.get("productionFileCount", 0)),
        "subject": str(entry.get("subject", "")).strip(),
        "freshnessEvidence": {
            "kind": str(evidence.get("kind", "PRIVATE_TASK")).strip().upper() or "PRIVATE_TASK",
            "reference": str(evidence.get("reference", "")).strip(),
        },
        "leakageAuditVerdict": verdict,
    }


def freeze_fresh_selection(
    pool: Sequence[dict[str, Any]],
    selected_case_ids: Sequence[str],
    seed: int,
    destination: Path,
) -> dict[str, Any]:
    """Freeze the named cases into a seeded selection record without overwriting anything."""

    validated: dict[str, dict[str, Any]] = {}
    for entry in pool:
        record = _validate_pool_entry(entry)
        validated[record["caseId"]] = record

    selected: list[dict[str, Any]] = []
    for case_id in selected_case_ids:
        safe_id = _safe_case_id(case_id)
        record = validated.get(safe_id)
        if record is None:
            raise FreshSelectionError(f"selected case is not in the audited pool: {safe_id}")
        selected.append(record)
    if len({record["caseId"] for record in selected}) != len(selected):
        raise FreshSelectionError("selected case IDs must be unique")

    languages = {record["language"] for record in selected}
    difficulties = {record["difficulty"] for record in selected}
    if not _REQUIRED_LANGUAGES.issubset(languages):
        raise FreshSelectionError("selection must contain at least one JAVA and one TSJS case")
    if not _REQUIRED_DIFFICULTIES.issubset(difficulties):
        raise FreshSelectionError("selection must cover EASY, MEDIUM and HARD difficulty")

    selection = {
        "schemaVersion": "rd-eval-fresh-selection-v1",
        "seed": int(seed),
        "caseCount": len(selected),
        "languageMix": {
            "JAVA": sum(1 for record in selected if record["language"] == "JAVA"),
            "TSJS": sum(1 for record in selected if record["language"] == "TSJS"),
        },
        "difficultyMix": {
            band: sum(1 for record in selected if record["difficulty"] == band)
            for band in sorted(_ALLOWED_DIFFICULTIES)
        },
        "cases": selected,
    }

    target = Path(destination).resolve()
    if target.exists() or target.is_symlink():
        raise FreshSelectionError("fresh selection destination must not already exist")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(selection, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return selection


def main() -> int:
    parser = argparse.ArgumentParser(description="Freeze a FRESH_PRIMARY case selection.")
    parser.add_argument("--pool", required=True, help="JSON array of audited fresh case candidates.")
    parser.add_argument("--select", required=True, help="Comma-separated case IDs to freeze.")
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    pool = json.loads(Path(args.pool).read_text(encoding="utf-8"))
    if not isinstance(pool, list):
        raise FreshSelectionError("pool must be a JSON array of case candidates")
    freeze_fresh_selection(
        pool,
        [part.strip() for part in args.select.split(",") if part.strip()],
        args.seed,
        Path(args.output),
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FreshSelectionError, json.JSONDecodeError) as error:
        print(f"fresh selection error: {error}", file=sys.stderr)
        raise SystemExit(2)
