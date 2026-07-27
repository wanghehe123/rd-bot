from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.run_artifacts import ArtifactError, RunArtifacts, redact


class RunArtifactsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp.name) / "run"
        self.artifacts = RunArtifacts(self.run_dir)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_rejects_path_escape(self) -> None:
        with self.assertRaisesRegex(ArtifactError, "outside run directory"):
            self.artifacts.write_text("../escape.txt", "bad")

    def test_redacts_secret_keys_and_bearer_values(self) -> None:
        value = redact({"authorization": "Bearer abc", "safe": "ok"})
        self.assertEqual(value["authorization"], "[REDACTED]")
        self.assertEqual(value["safe"], "ok")
        self.assertNotIn("abc", str(value))

    def test_truncates_large_text(self) -> None:
        value = redact({"message": "x" * 120_000})
        self.assertLessEqual(len(value["message"]), 100_020)
        self.assertTrue(value["message"].endswith("[TRUNCATED]"))

    def test_writes_json_inside_run_directory(self) -> None:
        path = self.artifacts.write_json("nested/evidence.json", {"token": "secret", "ok": True})
        self.assertTrue(path.is_relative_to(self.run_dir.resolve()))
        self.assertEqual(path.read_text(encoding="utf-8"), '{"token": "[REDACTED]", "ok": true}\n')


if __name__ == "__main__":
    unittest.main()
