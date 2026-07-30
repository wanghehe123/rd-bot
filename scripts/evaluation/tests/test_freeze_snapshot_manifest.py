from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_freeze_snapshot_manifest import (
    SnapshotFreezeError,
    freeze_snapshot,
)

_AGENT = "rd-bot/coding-eval-agent@sha256:" + "a" * 64
_ORACLE = "rd-bot/coding-eval-oracle@sha256:" + "b" * 64
_ANALYSIS = {
    "schemaVersion": "rd-eval-analysis-plan-v1",
    "arms": ["A", "B", "C", "D"],
    "firstAttemptTrials": 80,
    "sentinelCaseIds": ["fresh-00", "fresh-05", "mockito__mockito-3133", "iamkun__dayjs-857"],
    "sentinelRepeatTrials": 8,
}


def _fresh_case(index: int) -> dict:
    return {
        "caseId": f"fresh-{index:02d}",
        "slice": "FRESH_PRIMARY",
        "language": "JAVA",
        "difficulty": "MEDIUM",
        "baseCommit": f"{index:040x}",
        "fixCommit": f"{index + 100:040x}",
        "freshnessEvidence": {"kind": "PRIVATE_TASK", "reference": f"private commit {index}"},
    }


def _fresh_selection() -> dict:
    return {
        "selectionStatus": "VERIFIED_THREE_ROUND_OFFLINE",
        "cases": [_fresh_case(index) for index in range(10)],
    }


def _public_selection() -> dict:
    return {
        "selectionStatus": "VERIFIED_THREE_ROUND_OFFLINE",
        "selectedInstanceIds": [f"public__case-{index}" for index in range(10)],
        "repositoryMix": {"public/case": 10},
        "source": {"datasetId": "ByteDance-Seed/Multi-SWE-bench"},
    }


def _knowledge_report() -> dict:
    return {
        "ready": True,
        "caseCount": 20,
        "snapshotDigest": "sha256:" + "c" * 64,
        "results": [],
    }


def _inputs(root: Path) -> dict:
    (root / "fresh.json").write_text(json.dumps(_fresh_selection()), encoding="utf-8")
    (root / "public.json").write_text(json.dumps(_public_selection()), encoding="utf-8")
    (root / "knowledge.json").write_text(json.dumps(_knowledge_report()), encoding="utf-8")
    (root / "preflight.jsonl").write_text('{"ok": true}\n', encoding="utf-8")
    return {
        "snapshot_id": "20260730-test",
        "fresh_selection_path": root / "fresh.json",
        "public_selection_path": root / "public.json",
        "knowledge_report_path": root / "knowledge.json",
        "preflight_results_path": root / "preflight.jsonl",
        "images": [_AGENT, _ORACLE],
        "analysis_plan": _ANALYSIS,
        "output_directory": root / "snapshots",
    }


class FreezeSnapshotManifestTest(unittest.TestCase):
    def test_the_snapshot_satisfies_the_catalog_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = freeze_snapshot(**_inputs(Path(directory)))
            snapshot = Path(result["outputDirectory"])

            names = {path.name for path in snapshot.iterdir()}
            self.assertEqual({
                "dataset-manifest.json",
                "environment-manifest.json",
                "knowledge-manifest.json",
                "analysis-plan.json",
                "readiness-report.json",
                "benchmark-provenance.json",
            }, names)

            digest = result["snapshotDigest"]
            manifests = {name: json.loads((snapshot / name).read_text(encoding="utf-8")) for name in names}
            for name, manifest in manifests.items():
                self.assertEqual("20260730-test", manifest["snapshotId"], name)
                self.assertEqual(digest, manifest["snapshotDigest"], name)
            self.assertEqual(20, manifests["dataset-manifest.json"]["caseCount"])
            self.assertEqual(10, sum(1 for case in manifests["dataset-manifest.json"]["cases"] if case["slice"] == "FRESH_PRIMARY"))
            self.assertEqual(10, sum(1 for case in manifests["dataset-manifest.json"]["cases"] if case["slice"] == "PUBLIC_ANCHOR"))
            self.assertTrue(manifests["readiness-report.json"]["ready"])
            for image in manifests["environment-manifest.json"]["images"]:
                self.assertIn("@sha256:", image)
            provenance = manifests["benchmark-provenance.json"]
            for name in (
                "dataset-manifest.json",
                "environment-manifest.json",
                "knowledge-manifest.json",
                "analysis-plan.json",
                "readiness-report.json",
            ):
                actual = "sha256:" + hashlib.sha256((snapshot / name).read_bytes()).hexdigest()
                self.assertEqual(actual, provenance["manifestSha256"][name], name)

    def test_an_existing_snapshot_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            inputs = _inputs(Path(directory))
            freeze_snapshot(**inputs)
            with self.assertRaisesRegex(SnapshotFreezeError, "cannot be overwritten"):
                freeze_snapshot(**inputs)

    def test_unverified_selections_cannot_be_frozen(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = _inputs(root)
            broken = _fresh_selection()
            broken["selectionStatus"] = "CANDIDATE_UNVERIFIED"
            (root / "fresh.json").write_text(json.dumps(broken), encoding="utf-8")
            with self.assertRaisesRegex(SnapshotFreezeError, "fresh selection is not preflight-verified"):
                freeze_snapshot(**inputs)

    def test_floating_image_references_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            inputs = _inputs(Path(directory))
            inputs["images"] = [_AGENT, "rd-bot/coding-eval-oracle:latest"]
            with self.assertRaisesRegex(SnapshotFreezeError, "immutable digest"):
                freeze_snapshot(**inputs)

    def test_the_digest_changes_when_any_input_changes(self) -> None:
        with tempfile.TemporaryDirectory() as left, tempfile.TemporaryDirectory() as right:
            first = freeze_snapshot(**_inputs(Path(left)))
            inputs = _inputs(Path(right))
            fresh = _fresh_selection()
            fresh["cases"][0]["fixCommit"] = "f" * 40
            (Path(right) / "fresh.json").write_text(json.dumps(fresh), encoding="utf-8")
            second = freeze_snapshot(**inputs)
            self.assertNotEqual(first["snapshotDigest"], second["snapshotDigest"])


if __name__ == "__main__":
    unittest.main()
