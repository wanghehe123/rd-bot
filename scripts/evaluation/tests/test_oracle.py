from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_oracle import OracleContractError, run_oracle, verify_result


class OracleContractTest(unittest.TestCase):
    def test_oracle_fails_when_expected_test_id_is_not_collected(self) -> None:
        result = verify_result(
            test_output="1 passed",
            expected_test_ids=["test_issue::test_regression"],
        )

        self.assertEqual("TEST_FAIL", result.verdict)
        self.assertEqual(("test_issue::test_regression",), result.missing_test_ids)

    def test_oracle_accepts_exactly_once_collected_passing_test_ids(self) -> None:
        result = verify_result(
            test_output='RD_EVAL_COLLECTED_TEST_IDS=["test_issue::test_regression"]\n1 passed',
            expected_test_ids=["test_issue::test_regression"],
        )

        self.assertEqual("PASS", result.verdict)
        self.assertEqual((), result.missing_test_ids)

    def test_oracle_ignores_an_unrelated_skipped_gradle_task(self) -> None:
        result = verify_result(
            test_output='> Task :retryTest SKIPPED\nRD_EVAL_COLLECTED_TEST_IDS=["jib-core:test"]',
            expected_test_ids=["jib-core:test"],
        )

        self.assertEqual("PASS", result.verdict)
        self.assertFalse(result.skipped)

    def test_oracle_can_apply_a_protected_runtime_test_patch_after_the_candidate_patch(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir) / "repository"
            repository.mkdir()
            self._git(repository, "init", "-q")
            self._git(repository, "config", "user.email", "test@example.com")
            self._git(repository, "config", "user.name", "Test User")
            (repository / "src.txt").write_text("before\n", encoding="utf-8")
            (repository / "test.txt").write_text("base assertion\n", encoding="utf-8")
            self._git(repository, "add", "src.txt", "test.txt")
            self._git(repository, "commit", "-qm", "base")
            candidate = Path(temp_dir) / "candidate.patch"
            candidate.write_text(
                "diff --git a/src.txt b/src.txt\n--- a/src.txt\n+++ b/src.txt\n@@ -1 +1 @@\n-before\n+after\n",
                encoding="utf-8",
            )
            protected_test_patch = Path(temp_dir) / "runtime-withheld.patch"
            protected_test_patch.write_text(
                "diff --git a/test.txt b/test.txt\n--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-base assertion\n+runtime withheld assertion\n",
                encoding="utf-8",
            )

            result = run_oracle(
                verifier_repository=repository,
                patch_path=candidate,
                protected_bundle=None,
                protected_target=None,
                protected_test_patch=protected_test_patch,
                command=[sys.executable, "-c", 'print("RD_EVAL_COLLECTED_TEST_IDS=[\\"case::regression\\"]")'],
                expected_test_ids=["case::regression"],
                network_mode="none",
            )

            self.assertEqual("PASS", result.verdict)
            self.assertEqual("after\n", (repository / "src.txt").read_text(encoding="utf-8"))
            self.assertEqual("runtime withheld assertion\n", (repository / "test.txt").read_text(encoding="utf-8"))
            candidate_touching_test = Path(temp_dir) / "invalid.patch"
            candidate_touching_test.write_text(protected_test_patch.read_text(encoding="utf-8"), encoding="utf-8")
            with self.assertRaisesRegex(OracleContractError, "protected test path"):
                run_oracle(
                    verifier_repository=repository,
                    patch_path=candidate_touching_test,
                    protected_bundle=None,
                    protected_target=None,
                    protected_test_patch=protected_test_patch,
                    command=[sys.executable, "-c", "print('unused')"],
                    expected_test_ids=["case::regression"],
                    network_mode="none",
                )

    def test_oracle_can_collect_gradle_task_ids_without_an_agent_supplied_result_line(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir) / "repository"
            repository.mkdir()
            self._git(repository, "init", "-q")
            self._git(repository, "config", "user.email", "test@example.com")
            self._git(repository, "config", "user.name", "Test User")
            (repository / "src.txt").write_text("before\n", encoding="utf-8")
            (repository / "test.txt").write_text("base assertion\n", encoding="utf-8")
            self._git(repository, "add", "src.txt", "test.txt")
            self._git(repository, "commit", "-qm", "base")
            candidate = Path(temp_dir) / "candidate.patch"
            candidate.write_text(
                "diff --git a/src.txt b/src.txt\n--- a/src.txt\n+++ b/src.txt\n@@ -1 +1 @@\n-before\n+after\n",
                encoding="utf-8",
            )
            protected_test_patch = Path(temp_dir) / "runtime-withheld.patch"
            protected_test_patch.write_text(
                "diff --git a/test.txt b/test.txt\n--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-base assertion\n+runtime withheld assertion\n",
                encoding="utf-8",
            )

            result = run_oracle(
                verifier_repository=repository,
                patch_path=candidate,
                protected_bundle=None,
                protected_target=None,
                protected_test_patch=protected_test_patch,
                command=[
                    sys.executable,
                    "-c",
                    'print("RD_EVAL_COLLECTED_TEST_IDS=[\\\"spoofed\\\"]"); '
                    'print("> Task :jib-core:test"); print("> Task :jib-cli:compileJava FROM-CACHE")',
                ],
                expected_test_ids=["jib-core:test", "jib-cli:compileJava"],
                network_mode="none",
                result_parser="GRADLE_TASKS",
            )

            self.assertEqual("PASS", result.verdict)
            bundle = Path(temp_dir) / "bundle"
            bundle.mkdir()
            with self.assertRaisesRegex(OracleContractError, "exactly one"):
                run_oracle(
                    verifier_repository=repository,
                    patch_path=candidate,
                    protected_bundle=bundle,
                    protected_target="withheld",
                    protected_test_patch=protected_test_patch,
                    command=[sys.executable, "-c", "print('unused')"],
                    expected_test_ids=["case::regression"],
                    network_mode="none",
                )

    @staticmethod
    def _git(directory: Path, *arguments: str) -> None:
        subprocess.run(["git", "-C", str(directory), *arguments], check=True)


if __name__ == "__main__":
    unittest.main()
