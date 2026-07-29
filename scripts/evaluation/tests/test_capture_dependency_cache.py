from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_capture_dependency_cache import CacheCaptureError, capture_dependency_cache


class CaptureDependencyCacheTest(unittest.TestCase):
    def test_capture_uses_a_small_case_artifact_with_a_verifiable_gradle_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "warm-gradle"
            (source / "caches" / "modules-2").mkdir(parents=True)
            (source / "caches" / "modules-2" / "dependency.bin").write_bytes(b"prepared dependency")
            destination = root / "case-cache"

            result = capture_dependency_cache(
                case_id="org__service-17",
                base_commit=base_commit,
                toolchain_image="registry.example/java@sha256:" + "a" * 64,
                prepared_repository=repository,
                cache_kind="gradle",
                cache_source=source,
                destination=destination,
            )

            manifest = json.loads((destination / "dependency-cache.json").read_text(encoding="utf-8"))
            self.assertEqual("org__service-17", result["caseId"])
            self.assertEqual(base_commit, manifest["baseCommit"])
            self.assertEqual("gradle", manifest["cacheKind"])
            self.assertEqual("sha256:", manifest["cacheTreeSha256"][:7])
            self.assertFalse("warm-gradle" in json.dumps(manifest))
            self.assertEqual(b"prepared dependency", (destination / "gradle" / "caches" / "modules-2" / "dependency.bin").read_bytes())

    def test_capture_rejects_unpinned_toolchain_or_an_existing_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository")
            base_commit = self._git(repository, "rev-parse", "HEAD").strip()
            source = root / "warm-gradle"
            source.mkdir()
            destination = root / "case-cache"
            destination.mkdir()

            with self.assertRaisesRegex(CacheCaptureError, "immutable"):
                capture_dependency_cache(
                    "org__service-17", base_commit, "registry.example/java:latest", repository,
                    "gradle", source, root / "other-cache",
                )
            with self.assertRaisesRegex(CacheCaptureError, "must not already exist"):
                capture_dependency_cache(
                    "org__service-17", base_commit, "registry.example/java@sha256:" + "a" * 64, repository,
                    "gradle", source, destination,
                )

    @staticmethod
    def _repository(path: Path) -> Path:
        path.mkdir()
        CaptureDependencyCacheTest._git(path, "init", "-q")
        CaptureDependencyCacheTest._git(path, "config", "user.email", "test@example.com")
        CaptureDependencyCacheTest._git(path, "config", "user.name", "Test User")
        (path / "README.md").write_text("base\n", encoding="utf-8")
        CaptureDependencyCacheTest._git(path, "add", "README.md")
        CaptureDependencyCacheTest._git(path, "commit", "-qm", "base")
        return path

    @staticmethod
    def _git(directory: Path, *arguments: str) -> str:
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True)


if __name__ == "__main__":
    unittest.main()
