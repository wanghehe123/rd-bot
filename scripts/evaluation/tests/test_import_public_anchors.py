from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_import_public_anchors import (
    AnchorImportError,
    build_public_anchor_catalog,
    write_public_anchor_catalog,
)


class ImportPublicAnchorsTest(unittest.TestCase):
    def test_catalog_pins_provenance_without_exposing_gold_or_withheld_test_content(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source.jsonl"
            source.write_text(
                "\n".join([
                    json.dumps(self._row("org__service-17", "a" * 40, "GOLD_FIX_SECRET", "WITHHELD_TEST_SECRET")),
                    json.dumps(self._row("other__widget-18", "b" * 40, "UNSELECTED_GOLD", "UNSELECTED_TEST")),
                ]) + "\n",
                encoding="utf-8",
            )

            catalog = build_public_anchor_catalog(
                source,
                ["org__service-17"],
                dataset_id="ByteDance-Seed/Multi-SWE-bench-flash",
                dataset_revision="b0485dbebaf8a1317ebf140e80e6fc6c02d3502b",
            )
            destination = Path(temp_dir) / "public-anchor-candidates.json"
            write_public_anchor_catalog(catalog, destination)
            serialized = destination.read_text(encoding="utf-8")

            self.assertEqual("PUBLIC_ANCHOR", catalog["cases"][0]["slice"])
            self.assertEqual("a" * 40, catalog["cases"][0]["baseCommit"])
            self.assertEqual("https://github.com/org/service.git", catalog["cases"][0]["repositoryUrl"])
            self.assertTrue(catalog["cases"][0]["goldPatchSha256"].startswith("sha256:"))
            self.assertTrue(catalog["cases"][0]["withheldTestPatchSha256"].startswith("sha256:"))
            self.assertNotIn("GOLD_FIX_SECRET", serialized)
            self.assertNotIn("WITHHELD_TEST_SECRET", serialized)
            self.assertNotIn("UNSELECTED_GOLD", serialized)
            self.assertNotIn('"fix_patch"', serialized)
            self.assertNotIn('"test_patch"', serialized)

    def test_catalog_rejects_missing_or_non_immutable_selected_base_commit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source.jsonl"
            source.write_text(json.dumps(self._row("org__service-17", "not-a-commit", "gold", "test")) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(AnchorImportError, "base commit"):
                build_public_anchor_catalog(source, ["org__service-17"], dataset_id="dataset", dataset_revision="c" * 40)
            with self.assertRaisesRegex(AnchorImportError, "selected instance"):
                build_public_anchor_catalog(source, ["missing__case-1"], dataset_id="dataset", dataset_revision="c" * 40)

    def test_catalog_can_infer_language_from_a_partitioned_official_dataset_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source_directory = Path(temp_dir) / "dataset"
            source = source_directory / "ts" / "cases.jsonl"
            source.parent.mkdir(parents=True)
            row = self._row("org__service-17", "a" * 40, "GOLD_FIX_SECRET", "WITHHELD_TEST_SECRET")
            row.pop("language")
            source.write_text(json.dumps(row) + "\n", encoding="utf-8")

            catalog = build_public_anchor_catalog(
                source_directory,
                ["org__service-17"],
                dataset_id="dataset",
                dataset_revision="c" * 40,
            )

            self.assertEqual("TYPESCRIPT", catalog["cases"][0]["language"])

    @staticmethod
    def _row(instance_id: str, base_sha: str, fix_patch: str, test_patch: str) -> dict[str, object]:
        org, suffix = instance_id.split("__", maxsplit=1)
        repository, number = suffix.rsplit("-", maxsplit=1)
        return {
            "instance_id": instance_id,
            "org": org,
            "repo": repository,
            "number": int(number),
            "language": "java",
            "difficulty": "1h - 4h",
            "title": "Repair service behavior",
            "body": "The service should retain its compatibility contract.",
            "base": {"sha": base_sha},
            "fix_patch": "diff --git a/src/Service.java b/src/Service.java\n--- a/src/Service.java\n+++ b/src/Service.java\n+" + fix_patch,
            "test_patch": "diff --git a/test/ServiceTest.java b/test/ServiceTest.java\n--- a/test/ServiceTest.java\n+++ b/test/ServiceTest.java\n+" + test_patch,
            "f2p_tests": ["ServiceTest.regression"],
            "p2p_tests": ["ServiceTest.compatibility"],
            "s2p_tests": [],
            "n2p_tests": [],
        }


if __name__ == "__main__":
    unittest.main()
