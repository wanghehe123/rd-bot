from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_generate_benchmark_docs import generate_case_documents


class GenerateBenchmarkDocsTest(unittest.TestCase):
    def test_docs_are_frozen_hashed_and_exclude_gold_and_withheld_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir) / "repository"
            repository.mkdir()
            (repository / "README.md").write_text("# Widget service\n", encoding="utf-8")
            (repository / "src").mkdir()
            (repository / "src/widget.py").write_text(
                "def render_widget(value: str) -> str:\n    return value.strip()\n",
                encoding="utf-8",
            )
            (repository / "tests").mkdir()
            (repository / "tests/test_widget.py").write_text(
                "from src.widget import render_widget\n", encoding="utf-8"
            )
            (repository / "gold").mkdir()
            (repository / "gold/fix.patch").write_text("SECRET_GOLD_FIX\n", encoding="utf-8")
            (repository / "tests/runtime_withheld").mkdir()
            (repository / "tests/runtime_withheld/test_hidden.py").write_text(
                "SECRET_WITHHELD_ASSERTION\n", encoding="utf-8"
            )

            result = generate_case_documents(
                {
                    "caseId": "case-01",
                    "repositoryRoot": str(repository),
                    "goldPatchPaths": ["gold/fix.patch"],
                    "withheldTestPaths": ["tests/runtime_withheld"],
                },
                Path(temp_dir) / "knowledge",
            )

            manifest = json.loads((Path(result["outputDirectory"]) / "knowledge-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(4, len(manifest["documents"]))
            self.assertGreater(len(manifest["chunks"]), 0)
            self.assertTrue(all(chunk["sha256"].startswith("sha256:") for chunk in manifest["chunks"]))
            self.assertEqual([], manifest["leakage"]["hits"])
            rendered = "\n".join((Path(result["outputDirectory"]) / entry["path"]).read_text(encoding="utf-8") for entry in manifest["documents"])
            self.assertNotIn("SECRET_GOLD_FIX", rendered)
            self.assertNotIn("SECRET_WITHHELD_ASSERTION", rendered)

    def test_protected_asset_outside_the_repository_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir) / "repository"
            repository.mkdir()
            (repository / "README.md").write_text("# Widget\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "beneath the base repository"):
                generate_case_documents(
                    {
                        "caseId": "case-01",
                        "repositoryRoot": str(repository),
                        "goldPatchPaths": ["../gold/fix.patch"],
                    },
                    Path(temp_dir) / "knowledge",
                )


if __name__ == "__main__":
    unittest.main()
