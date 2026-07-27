from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.workspace_guard import WorkspaceError, assert_unchanged, capture


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=root, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def commit(root: Path, message: str) -> None:
    git(root, "-c", "user.name=autopilot-test", "-c", "user.email=autopilot@example.invalid", "commit", "-m", message)


class WorkspaceGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        git(self.root, "init", "-q")
        (self.root / "tracked.txt").write_text("one\n", encoding="utf-8")
        git(self.root, "add", "tracked.txt")
        commit(self.root, "initial")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_detects_tracked_content_change(self) -> None:
        before = capture(self.root)
        (self.root / "tracked.txt").write_text("changed\n", encoding="utf-8")
        after = capture(self.root)
        with self.assertRaisesRegex(WorkspaceError, "fingerprint changed"):
            assert_unchanged(before, after)

    def test_ignored_run_artifact_does_not_change_fingerprint(self) -> None:
        (self.root / ".gitignore").write_text("qa-runs/\n", encoding="utf-8")
        git(self.root, "add", ".gitignore")
        commit(self.root, "ignore run artifacts")
        before = capture(self.root)
        run_file = self.root / "qa-runs" / "autopilot" / "run" / "manifest.json"
        run_file.parent.mkdir(parents=True)
        run_file.write_text("{}\n", encoding="utf-8")
        after = capture(self.root)
        assert_unchanged(before, after)

    def test_missing_repository_fails_closed(self) -> None:
        with self.assertRaises(WorkspaceError):
            capture(self.root / "missing")


if __name__ == "__main__":
    unittest.main()
