from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_patch import PatchContractError, extract_patch, verify_round_trip_apply


class PatchContractTest(unittest.TestCase):
    def test_extract_patch_rejects_runtime_withheld_test_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            self._init_repo(repo)
            protected = repo / "tests/runtime_withheld/test_issue.py"
            protected.parent.mkdir(parents=True)
            protected.write_text("def test_regression():\n    assert False\n", encoding="utf-8")

            with self.assertRaisesRegex(PatchContractError, "protected path"):
                extract_patch(repo, protected_paths={"tests/runtime_withheld"})

    def test_extract_patch_includes_binary_safe_tracked_change(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo = Path(temp_dir)
            self._init_repo(repo)
            source = repo / "src/service.py"
            source.write_text("VALUE = 2\n", encoding="utf-8")

            extraction = extract_patch(repo, protected_paths={"tests/runtime_withheld"})

            self.assertIn("diff --git a/src/service.py b/src/service.py", extraction.patch)
            self.assertEqual(("src/service.py",), extraction.changed_paths)
            self.assertTrue(extraction.sha256.startswith("sha256:"))
            verify_round_trip_apply(repo, extraction.patch)

    @staticmethod
    def _init_repo(repo: Path) -> None:
        subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.name", "Test User"], cwd=repo, check=True)
        source = repo / "src/service.py"
        source.parent.mkdir(parents=True)
        source.write_text("VALUE = 1\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-qm", "base"], cwd=repo, check=True)


if __name__ == "__main__":
    unittest.main()
