from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_materialize_multiswe_assets import (
    MaterializationError,
    materialize_case_assets,
)


class MaterializeMultiSweAssetsTest(unittest.TestCase):
    def test_assets_keep_gold_and_runtime_test_patches_outside_the_agent_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "dataset.jsonl"
            source.write_text(json.dumps(self._row(base_commit)) + "\n", encoding="utf-8")
            destination = root / "trusted-assets"

            result = materialize_case_assets(source, "org__service-17", repository, destination)

            metadata = json.loads((destination / "oracle-contract.json").read_text(encoding="utf-8"))
            self.assertEqual("org__service-17", result["caseId"])
            self.assertEqual("sha256:", metadata["goldPatchSha256"][:7])
            self.assertNotIn("GOLD_FIX_SECRET", json.dumps(metadata))
            self.assertNotIn("WITHHELD_TEST_SECRET", json.dumps(metadata))
            self.assertTrue((destination / "gold-fix.patch").read_text(encoding="utf-8").endswith("GOLD_FIX_SECRET\n"))
            self.assertTrue((destination / "runtime-withheld.patch").read_text(encoding="utf-8").endswith("WITHHELD_TEST_SECRET\n"))
            self.assertEqual(0, os.stat(destination / "gold-fix.patch").st_mode & 0o077)
            self.assertEqual(0, os.stat(destination / "runtime-withheld.patch").st_mode & 0o077)
            self.assertFalse((repository / "gold-fix.patch").exists())
            self.assertFalse((repository / "runtime-withheld.patch").exists())

    def test_assets_require_the_declared_base_to_match_the_prepared_repository(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            source = root / "dataset.jsonl"
            source.write_text(json.dumps(self._row("a" * 40)) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(MaterializationError, "base commit"):
                materialize_case_assets(source, "org__service-17", repository, root / "trusted-assets")

    def test_cli_runs_directly_from_the_repository_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "dataset.jsonl"
            source.write_text(json.dumps(self._row(base_commit)) + "\n", encoding="utf-8")
            script = Path(__file__).parents[1] / "rd_eval_materialize_multiswe_assets.py"

            completed = subprocess.run(
                [
                    sys.executable, str(script),
                    "--trusted-dataset", str(source),
                    "--case-id", "org__service-17",
                    "--prepared-repository", str(repository),
                    "--destination", str(root / "trusted-assets"),
                ],
                cwd=Path(__file__).parents[3],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

            self.assertEqual(0, completed.returncode, completed.stdout)
            self.assertTrue((root / "trusted-assets" / "gold-fix.patch").is_file())

    def test_assets_preserve_upstream_dictionary_test_identifiers_for_the_host_oracle(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            row = self._row(base_commit)
            row["f2p_tests"] = {"ServiceTest.regression": {"run": "FAIL", "test": "FAIL", "fix": "PASS"}}
            source = root / "dataset.jsonl"
            source.write_text(json.dumps(row) + "\n", encoding="utf-8")

            materialize_case_assets(source, "org__service-17", repository, root / "trusted-assets")

            contract = json.loads((root / "trusted-assets" / "oracle-contract.json").read_text(encoding="utf-8"))
            self.assertEqual(["ServiceTest.regression"], contract["expectedTests"]["failToPass"])

    @staticmethod
    def _repository(path: Path) -> Path:
        path.mkdir()
        MaterializeMultiSweAssetsTest._git(path, "init", "-q")
        MaterializeMultiSweAssetsTest._git(path, "config", "user.email", "test@example.com")
        MaterializeMultiSweAssetsTest._git(path, "config", "user.name", "Test User")
        (path / "src.txt").write_text("before\n", encoding="utf-8")
        (path / "test.txt").write_text("base assertion\n", encoding="utf-8")
        MaterializeMultiSweAssetsTest._git(path, "add", "src.txt", "test.txt")
        MaterializeMultiSweAssetsTest._git(path, "commit", "-qm", "base")
        return path

    @staticmethod
    def _row(base_commit: str) -> dict[str, object]:
        return {
            "instance_id": "org__service-17",
            "org": "org",
            "repo": "service",
            "number": 17,
            "base": {"sha": base_commit},
            "fix_patch": "diff --git a/src.txt b/src.txt\n--- a/src.txt\n+++ b/src.txt\n@@ -1 +1 @@\n-before\n+GOLD_FIX_SECRET\n",
            "test_patch": "diff --git a/test.txt b/test.txt\n--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-base assertion\n+WITHHELD_TEST_SECRET\n",
            "f2p_tests": ["ServiceTest.regression"],
            "p2p_tests": ["ServiceTest.compatibility"],
            "s2p_tests": [],
            "n2p_tests": [],
        }

    @staticmethod
    def _git(directory: Path, *arguments: str) -> str:
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True)


if __name__ == "__main__":
    unittest.main()
