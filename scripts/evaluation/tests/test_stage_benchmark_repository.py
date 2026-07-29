from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_stage_benchmark_repository import (
    RepositoryStageError,
    stage_repository_at_base,
)


class StageBenchmarkRepositoryTest(unittest.TestCase):
    def test_staged_repository_contains_only_the_declared_base_and_its_ancestors(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source"
            source.mkdir()
            self._git(source, "init", "-q")
            self._git(source, "config", "user.email", "test@example.com")
            self._git(source, "config", "user.name", "Test User")
            (source / "README.md").write_text("ancestor\n", encoding="utf-8")
            self._git(source, "add", "README.md")
            self._git(source, "commit", "-qm", "ancestor")
            (source / "README.md").write_text("base\n", encoding="utf-8")
            self._git(source, "commit", "-am", "base", "-q")
            base_commit = self._git(source, "rev-parse", "HEAD").strip()
            (source / "README.md").write_text("future\n", encoding="utf-8")
            self._git(source, "commit", "-am", "future", "-q")
            future_commit = self._git(source, "rev-parse", "HEAD").strip()

            staged = Path(temp_dir) / "staged"
            result = stage_repository_at_base(source, base_commit, staged)

            self.assertEqual(base_commit, result["baseCommit"])
            self.assertEqual(base_commit, self._git(staged, "rev-parse", "HEAD").strip())
            self.assertEqual("base", self._git(staged, "log", "-1", "--format=%s").strip())
            self.assertNotIn(future_commit, self._git(staged, "cat-file", "--batch-all-objects", "--batch-check=%(objectname) %(objecttype)"))
            self.assertEqual("", self._git(staged, "remote").strip())

    def test_stage_rejects_a_missing_or_overwrite_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source"
            source.mkdir()
            self._git(source, "init", "-q")
            self._git(source, "config", "user.email", "test@example.com")
            self._git(source, "config", "user.name", "Test User")
            (source / "README.md").write_text("base\n", encoding="utf-8")
            self._git(source, "add", "README.md")
            self._git(source, "commit", "-qm", "base")
            base_commit = self._git(source, "rev-parse", "HEAD").strip()
            destination = Path(temp_dir) / "staged"
            destination.mkdir()

            with self.assertRaisesRegex(RepositoryStageError, "must not already exist"):
                stage_repository_at_base(source, base_commit, destination)
            with self.assertRaisesRegex(RepositoryStageError, "base commit"):
                stage_repository_at_base(source, "a" * 40, Path(temp_dir) / "missing")

    @staticmethod
    def _git(directory: Path, *arguments: str) -> str:
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True)


if __name__ == "__main__":
    unittest.main()
