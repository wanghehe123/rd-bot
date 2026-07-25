"""Regression coverage for prediction patches produced from a real Git worktree."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import export_swebench_predictions as exporter


class PredictionPatchTest(unittest.TestCase):
    def test_collect_patch_includes_tracked_and_untracked_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            checkout = Path(temporary_directory)
            self.run_git(checkout, "init")
            self.run_git(checkout, "config", "user.email", "swebench@example.test")
            self.run_git(checkout, "config", "user.name", "SWE Bench Test")
            (checkout / "tracked.py").write_text("value = 1\n", encoding="utf-8")
            self.run_git(checkout, "add", "tracked.py")
            self.run_git(checkout, "commit", "-m", "baseline")

            (checkout / "tracked.py").write_text("value = 2\n", encoding="utf-8")
            (checkout / "new_module.py").write_text("name = 'new'\n", encoding="utf-8")

            patch = exporter.collect_model_patch(checkout, include_untracked=True)

            self.assertIn("diff --git a/tracked.py b/tracked.py", patch)
            self.assertIn("diff --git a/new_module.py b/new_module.py", patch)
            self.assertIn("+value = 2", patch)
            self.assertIn("+name = 'new'", patch)

    @staticmethod
    def run_git(checkout: Path, *args: str) -> None:
        completed = subprocess.run(
            ["git", "-C", str(checkout), *args],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if completed.returncode != 0:
            raise AssertionError(f"git {' '.join(args)} failed: {completed.stderr}")


if __name__ == "__main__":
    unittest.main()
