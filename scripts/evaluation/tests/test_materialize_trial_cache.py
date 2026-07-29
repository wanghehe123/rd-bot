from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_capture_dependency_cache import capture_dependency_cache
from scripts.evaluation.rd_eval_materialize_trial_cache import TrialCacheError, materialize_trial_cache


class MaterializeTrialCacheTest(unittest.TestCase):
    TOOLCHAIN = "registry.example/java@sha256:" + "a" * 64

    def test_materializes_two_independent_trial_caches_from_one_hashed_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "warm-gradle"
            (source / "caches").mkdir(parents=True)
            (source / "caches" / "dependency.bin").write_bytes(b"prepared dependency")
            artifact = root / "cache-artifact"
            capture_dependency_cache(
                "org__service-17", base_commit, self.TOOLCHAIN, repository, "gradle", source, artifact
            )

            agent = materialize_trial_cache(artifact, "org__service-17", base_commit, self.TOOLCHAIN, root / "agent-cache")
            oracle = materialize_trial_cache(artifact, "org__service-17", base_commit, self.TOOLCHAIN, root / "oracle-cache")

            self.assertEqual("sha256:", agent["cacheTreeSha256"][:7])
            self.assertEqual(agent["cacheTreeSha256"], oracle["cacheTreeSha256"])
            self.assertEqual(b"prepared dependency", (root / "agent-cache" / "gradle" / "caches" / "dependency.bin").read_bytes())
            (root / "agent-cache" / "gradle" / "caches" / "dependency.bin").write_bytes(b"agent-only mutation")
            self.assertEqual(b"prepared dependency", (root / "oracle-cache" / "gradle" / "caches" / "dependency.bin").read_bytes())
            self.assertEqual(b"prepared dependency", (artifact / "gradle" / "caches" / "dependency.bin").read_bytes())

    def test_rejects_a_cache_artifact_whose_contents_no_longer_match_its_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "warm-gradle"
            source.mkdir()
            artifact = root / "cache-artifact"
            capture_dependency_cache(
                "org__service-17", base_commit, self.TOOLCHAIN, repository, "gradle", source, artifact
            )
            (artifact / "gradle" / "unexpected.bin").write_bytes(b"tampered")

            with self.assertRaisesRegex(TrialCacheError, "digest"):
                materialize_trial_cache(artifact, "org__service-17", base_commit, self.TOOLCHAIN, root / "agent-cache")

    @staticmethod
    def _repository(path: Path) -> Path:
        path.mkdir()
        MaterializeTrialCacheTest._git(path, "init", "-q")
        MaterializeTrialCacheTest._git(path, "config", "user.email", "test@example.com")
        MaterializeTrialCacheTest._git(path, "config", "user.name", "Test User")
        (path / "README.md").write_text("base\n", encoding="utf-8")
        MaterializeTrialCacheTest._git(path, "add", "README.md")
        MaterializeTrialCacheTest._git(path, "commit", "-qm", "base")
        return path

    @staticmethod
    def _git(directory: Path, *arguments: str) -> str:
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True)


if __name__ == "__main__":
    unittest.main()
