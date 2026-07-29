from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_author_fresh_case import (
    FreshCaseError,
    author_fresh_case,
)


class AuthorFreshCaseTest(unittest.TestCase):
    def test_a_fix_commit_splits_into_a_gold_patch_and_a_withheld_test_patch(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)

            result = author_fresh_case(
                source_repository=repository,
                fix_commit=fix_commit,
                prepared_repository=prepared,
                destination=root / "assets",
                case_id="rd-bot__retry-lease-1",
                freshness_reference="private repository wanghehe123/rd-bot commit " + fix_commit,
            )

            gold = (root / "assets" / "gold-fix.patch").read_text(encoding="utf-8")
            withheld = (root / "assets" / "runtime-withheld.patch").read_text(encoding="utf-8")
            self.assertIn("engine/src/main/java/com/example/Lease.java", gold)
            self.assertIn("engine/src/main/java/com/example/Renewal.java", gold)
            self.assertNotIn("src/test/java", gold)
            self.assertIn("engine/src/test/java/com/example/LeaseTest.java", withheld)
            self.assertNotIn("src/main/java", withheld)
            self.assertEqual(base_commit, result["baseCommit"])
            self.assertEqual(fix_commit, result["fixCommit"])

    def test_the_contract_freezes_freshness_evidence_the_readiness_validator_accepts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)

            author_fresh_case(
                source_repository=repository,
                fix_commit=fix_commit,
                prepared_repository=prepared,
                destination=root / "assets",
                case_id="rd-bot__retry-lease-1",
                freshness_reference="private repository wanghehe123/rd-bot commit " + fix_commit,
            )

            contract = json.loads((root / "assets" / "oracle-contract.json").read_text(encoding="utf-8"))
            self.assertEqual("FRESH_PRIMARY", contract["slice"])
            self.assertEqual("PRIVATE_TASK", contract["freshnessEvidence"]["kind"])
            self.assertIn(fix_commit, contract["freshnessEvidence"]["reference"])
            self.assertEqual("sha256:", contract["goldPatchSha256"][:7])
            self.assertEqual("sha256:", contract["runtimeWithheldPatchSha256"][:7])

    def test_expected_fail_to_pass_ids_are_derived_from_added_java_test_methods(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)

            author_fresh_case(
                source_repository=repository,
                fix_commit=fix_commit,
                prepared_repository=prepared,
                destination=root / "assets",
                case_id="rd-bot__retry-lease-1",
                freshness_reference="private task reference",
            )

            contract = json.loads((root / "assets" / "oracle-contract.json").read_text(encoding="utf-8"))
            self.assertEqual(
                ["com.example.LeaseTest#should_expire_a_stale_lease"],
                contract["expectedTests"]["failToPass"],
            )

    def test_assets_stay_host_only_and_unreadable_to_other_accounts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)

            author_fresh_case(
                source_repository=repository,
                fix_commit=fix_commit,
                prepared_repository=prepared,
                destination=root / "assets",
                case_id="rd-bot__retry-lease-1",
                freshness_reference="private task reference",
            )

            for name in ("gold-fix.patch", "runtime-withheld.patch", "oracle-contract.json"):
                self.assertEqual(0, os.stat(root / "assets" / name).st_mode & 0o077, name)
                self.assertFalse((prepared / name).exists(), name)

    def test_the_prepared_repository_must_sit_at_the_fix_commit_parent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, _ = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, fix_commit)

            with self.assertRaisesRegex(FreshCaseError, "base commit"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=fix_commit,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_a_merge_commit_cannot_become_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, _, base_commit = self._repository_with_fix(root / "repository")
            merge_commit = self._merge_commit(repository)
            prepared = self._prepared(root / "prepared", repository, base_commit)

            with self.assertRaisesRegex(FreshCaseError, "merge commit"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=merge_commit,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_a_commit_without_a_test_change_cannot_become_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, _, _ = self._repository_with_fix(root / "repository")
            production_only = self._commit(
                repository,
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 9; } }\n",
                    "engine/src/main/java/com/example/Renewal.java": "class Renewal { int ttl() { return 9; } }\n",
                },
                "fix: change ttl without a test",
            )
            parent = self._git(repository, "rev-parse", production_only + "^").strip()
            prepared = self._prepared(root / "prepared", repository, parent)

            with self.assertRaisesRegex(FreshCaseError, "runtime-withheld test"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=production_only,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_a_single_production_file_change_cannot_become_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, _, _ = self._repository_with_fix(root / "repository")
            narrow = self._commit(
                repository,
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 11; } }\n",
                    "engine/src/test/java/com/example/LeaseTest.java": self._test_source(
                        "should_reject_a_negative_ttl"
                    ),
                },
                "fix: narrow single-file change",
            )
            parent = self._git(repository, "rev-parse", narrow + "^").strip()
            prepared = self._prepared(root / "prepared", repository, parent)

            with self.assertRaisesRegex(FreshCaseError, "production files"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=narrow,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_a_commit_touching_a_build_or_dependency_manifest_cannot_become_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, _, _ = self._repository_with_fix(root / "repository")
            with_manifest = self._commit(
                repository,
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 12; } }\n",
                    "engine/src/main/java/com/example/Renewal.java": "class Renewal { int ttl() { return 12; } }\n",
                    "engine/src/test/java/com/example/LeaseTest.java": self._test_source("should_use_new_library"),
                    "engine/pom.xml": "<project><dependencies/></project>\n",
                },
                "fix: pull in a new dependency",
            )
            parent = self._git(repository, "rev-parse", with_manifest + "^").strip()
            prepared = self._prepared(root / "prepared", repository, parent)

            with self.assertRaisesRegex(FreshCaseError, "build or dependency manifest"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=with_manifest,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_a_commit_whose_tests_add_no_new_case_cannot_become_a_case(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, _, _ = self._repository_with_fix(root / "repository")
            comment_only_test = self._commit(
                repository,
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 13; } }\n",
                    "engine/src/main/java/com/example/Renewal.java": "class Renewal { int ttl() { return 13; } }\n",
                    "engine/src/test/java/com/example/LeaseTest.java": self._test_source(
                        "should_expire_a_stale_lease", trailing_comment="// unrelated note\n"
                    ),
                },
                "fix: adjust a test comment only",
            )
            parent = self._git(repository, "rev-parse", comment_only_test + "^").strip()
            prepared = self._prepared(root / "prepared", repository, parent)

            with self.assertRaisesRegex(FreshCaseError, "expected test"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=comment_only_test,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_an_existing_destination_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)
            (root / "assets").mkdir()

            with self.assertRaisesRegex(FreshCaseError, "must not already exist"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=fix_commit,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="private task reference",
                )

    def test_freshness_evidence_must_carry_a_bounded_auditable_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)

            with self.assertRaisesRegex(FreshCaseError, "freshness"):
                author_fresh_case(
                    source_repository=repository,
                    fix_commit=fix_commit,
                    prepared_repository=prepared,
                    destination=root / "assets",
                    case_id="rd-bot__retry-lease-1",
                    freshness_reference="   ",
                )

    def test_cli_runs_directly_from_the_repository_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository, fix_commit, base_commit = self._repository_with_fix(root / "repository")
            prepared = self._prepared(root / "prepared", repository, base_commit)
            script = Path(__file__).parents[1] / "rd_eval_author_fresh_case.py"

            completed = subprocess.run(
                [
                    sys.executable, str(script),
                    "--source-repository", str(repository),
                    "--fix-commit", fix_commit,
                    "--prepared-repository", str(prepared),
                    "--destination", str(root / "assets"),
                    "--case-id", "rd-bot__retry-lease-1",
                    "--freshness-reference", "private task reference",
                ],
                cwd=Path(__file__).parents[3],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

            self.assertEqual(0, completed.returncode, completed.stdout)
            self.assertTrue((root / "assets" / "gold-fix.patch").is_file())

    @staticmethod
    def _test_source(method: str, trailing_comment: str = "") -> str:
        addition = f"    {trailing_comment}" if trailing_comment else (
            "    @Test\n"
            f"    void {method}() {{\n"
            "    }\n"
        )
        return (
            "package com.example;\n"
            "\n"
            "class LeaseTest {\n"
            "    @Test\n"
            "    void should_keep_a_live_lease() {\n"
            "    }\n"
            "\n"
            + addition
            + "}\n"
        )

    @classmethod
    def _repository_with_fix(cls, path: Path) -> tuple[Path, str, str]:
        path.mkdir(parents=True)
        cls._git(path, "init", "-q", "-b", "main")
        cls._git(path, "config", "user.email", "test@example.com")
        cls._git(path, "config", "user.name", "Test User")
        cls._write(path, "engine/src/main/java/com/example/Lease.java", "class Lease { int ttl() { return 0; } }\n")
        cls._write(path, "engine/src/main/java/com/example/Renewal.java", "class Renewal { int ttl() { return 0; } }\n")
        cls._write(
            path,
            "engine/src/test/java/com/example/LeaseTest.java",
            "package com.example;\n"
            "\n"
            "class LeaseTest {\n"
            "    @Test\n"
            "    void should_keep_a_live_lease() {\n"
            "    }\n"
            "}\n",
        )
        cls._git(path, "add", "-A")
        cls._git(path, "commit", "-qm", "base")
        base_commit = cls._git(path, "rev-parse", "HEAD").strip()
        fix_commit = cls._commit(
            path,
            {
                "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 30; } }\n",
                "engine/src/main/java/com/example/Renewal.java": "class Renewal { int ttl() { return 30; } }\n",
                "engine/src/test/java/com/example/LeaseTest.java": cls._test_source("should_expire_a_stale_lease"),
            },
            "fix: expire a stale retry lease",
        )
        return path, fix_commit, base_commit

    @classmethod
    def _merge_commit(cls, repository: Path) -> str:
        cls._git(repository, "checkout", "-q", "-b", "side", "HEAD~1")
        cls._write(repository, "engine/src/main/java/com/example/Side.java", "class Side {}\n")
        cls._git(repository, "add", "-A")
        cls._git(repository, "commit", "-qm", "feat: side branch")
        cls._git(repository, "checkout", "-q", "main")
        cls._git(repository, "merge", "-q", "--no-ff", "-m", "merge: side into main", "side")
        return cls._git(repository, "rev-parse", "HEAD").strip()

    @classmethod
    def _commit(cls, repository: Path, files: dict[str, str], subject: str) -> str:
        for relative, content in files.items():
            cls._write(repository, relative, content)
        cls._git(repository, "add", "-A")
        cls._git(repository, "commit", "-qm", subject)
        return cls._git(repository, "rev-parse", "HEAD").strip()

    @classmethod
    def _prepared(cls, path: Path, repository: Path, commit: str) -> Path:
        subprocess.check_output(
            ["git", "clone", "--no-hardlinks", "--quiet", str(repository), str(path)], text=True
        )
        cls._git(path, "checkout", "-q", commit)
        cls._git(path, "remote", "remove", "origin")
        return path

    @staticmethod
    def _write(repository: Path, relative: str, content: str) -> None:
        target = repository / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    @staticmethod
    def _git(directory: Path, *arguments: str) -> str:
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True)


if __name__ == "__main__":
    unittest.main()
