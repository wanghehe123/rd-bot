from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.evaluation.rd_eval_audit_case_leakage import (
    LeakageAuditError,
    audit_case_leakage,
)


class AuditCaseLeakageTest(unittest.TestCase):
    def test_a_case_is_clean_when_no_base_document_names_the_unwritten_symbol(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/design.md": "The retry engine keeps a lease per task.\n",
                },
            )
            gold = self._gold_patch(root, "+    long computeLeaseExpiry(long now) { return now; }\n")

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("CLEAN", report["verdict"])
            self.assertEqual([], report["documentationLeaks"])

    def test_a_base_document_naming_an_unimplemented_symbol_is_reported_as_a_leak(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/design.md": "A stale lease must be dropped by computeLeaseExpiry before renewal.\n",
                },
            )
            gold = self._gold_patch(root, "+    long computeLeaseExpiry(long now) { return now; }\n")

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("LEAK", report["verdict"])
            leak = report["documentationLeaks"][0]
            self.assertEqual("computeLeaseExpiry", leak["symbol"])
            self.assertIn("docs/design.md", leak["paths"])

    def test_a_symbol_that_already_exists_in_base_production_code_is_not_a_leak(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java":
                        "class Lease { long computeLeaseExpiry(long now) { return now; } }\n",
                    "docs/design.md": "See computeLeaseExpiry for the lease rule.\n",
                },
            )
            gold = self._gold_patch(root, "+    long computeLeaseExpiry(long now) { return now + 30; }\n")

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("CLEAN", report["verdict"])

    def test_a_base_test_naming_an_unimplemented_symbol_is_reported_separately(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "engine/src/test/java/com/example/LeaseTest.java":
                        "class LeaseTest { void probe() { new Lease().computeLeaseExpiry(1L); } }\n",
                },
            )
            gold = self._gold_patch(root, "+    long computeLeaseExpiry(long now) { return now; }\n")

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("LEAK", report["verdict"])
            self.assertEqual([], report["documentationLeaks"])
            self.assertEqual("computeLeaseExpiry", report["testLeaks"][0]["symbol"])

    def test_common_short_tokens_do_not_create_findings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/design.md": "The return value should be public and final.\n",
                },
            )
            gold = self._gold_patch(
                root,
                "+    public final int value() { return 1; }\n"
                "+    long renewedLeaseWindow() { return 1; }\n",
            )

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("CLEAN", report["verdict"])
            self.assertNotIn("value", report["auditedSymbols"])

    def test_only_added_gold_lines_contribute_symbols(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/design.md": "Legacy behaviour lived in deprecatedLeaseProbe.\n",
                },
            )
            gold = self._gold_patch(
                root,
                "-    long deprecatedLeaseProbe() { return 0; }\n"
                "+    long renewedLeaseWindow() { return 1; }\n",
            )

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("CLEAN", report["verdict"])

    def test_a_library_call_the_patch_does_not_declare_is_not_a_leak(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "engine/src/test/java/com/example/LeaseTest.java":
                        "class LeaseTest { void probe() { Files.readAllLines(path); } }\n",
                    "docs/design.md": "The loader uses readAllLines and destroyForcibly.\n",
                },
            )
            gold = self._gold_patch(
                root,
                "+    long renewedLeaseWindow() {\n"
                "+        return Files.readAllLines(path).size() + process.destroyForcibly().hashCode();\n",
            )

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("CLEAN", report["verdict"])
            self.assertEqual(["renewedLeaseWindow"], report["auditedSymbols"])

    def test_a_newly_created_production_file_declares_its_type_name(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/plan.md": "Add a LeaseExpiryAttestor to guard renewals.\n",
                },
            )
            gold = root / "gold-fix.patch"
            gold.write_text(
                "diff --git a/engine/src/main/java/com/example/LeaseExpiryAttestor.java"
                " b/engine/src/main/java/com/example/LeaseExpiryAttestor.java\n"
                "new file mode 100644\n"
                "--- /dev/null\n"
                "+++ b/engine/src/main/java/com/example/LeaseExpiryAttestor.java\n"
                "@@ -0,0 +1 @@\n"
                "+package com.example;\n",
                encoding="utf-8",
            )

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("LEAK", report["verdict"])
            self.assertEqual("LeaseExpiryAttestor", report["documentationLeaks"][0]["symbol"])

    def test_a_patch_without_a_distinctive_declaration_is_unauditable_not_clean(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {"engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n"},
            )
            gold = self._gold_patch(root, "+        return 30;\n")

            report = audit_case_leakage(prepared_repository=repository, gold_patch=gold)

            self.assertEqual("UNAUDITABLE", report["verdict"])
            self.assertEqual([], report["auditedSymbols"])

    def test_a_missing_gold_patch_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(root / "repository", {"a/src/main/java/A.java": "class A {}\n"})

            with self.assertRaisesRegex(LeakageAuditError, "Gold patch"):
                audit_case_leakage(prepared_repository=repository, gold_patch=root / "absent.patch")

    def test_cli_exits_non_zero_when_a_leak_is_found(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            repository = self._repository(
                root / "repository",
                {
                    "engine/src/main/java/com/example/Lease.java": "class Lease { int ttl() { return 0; } }\n",
                    "docs/design.md": "Drop it in computeLeaseExpiry.\n",
                },
            )
            gold = self._gold_patch(root, "+    long computeLeaseExpiry(long now) { return now; }\n")
            script = Path(__file__).parents[1] / "rd_eval_audit_case_leakage.py"

            completed = subprocess.run(
                [
                    sys.executable, str(script),
                    "--prepared-repository", str(repository),
                    "--gold-patch", str(gold),
                ],
                cwd=Path(__file__).parents[3],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )

            self.assertEqual(1, completed.returncode, completed.stdout)
            self.assertIn("computeLeaseExpiry", completed.stdout)

    @staticmethod
    def _gold_patch(root: Path, body: str) -> Path:
        patch = root / "gold-fix.patch"
        patch.write_text(
            "diff --git a/engine/src/main/java/com/example/Lease.java b/engine/src/main/java/com/example/Lease.java\n"
            "--- a/engine/src/main/java/com/example/Lease.java\n"
            "+++ b/engine/src/main/java/com/example/Lease.java\n"
            "@@ -1 +1,2 @@\n"
            + body,
            encoding="utf-8",
        )
        return patch

    @staticmethod
    def _repository(path: Path, files: dict[str, str]) -> Path:
        path.mkdir(parents=True)
        subprocess.check_output(["git", "-C", str(path), "init", "-q"], text=True)
        subprocess.check_output(["git", "-C", str(path), "config", "user.email", "t@example.com"], text=True)
        subprocess.check_output(["git", "-C", str(path), "config", "user.name", "T"], text=True)
        for relative, content in files.items():
            target = path / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        subprocess.check_output(["git", "-C", str(path), "add", "-A"], text=True)
        subprocess.check_output(["git", "-C", str(path), "commit", "-qm", "base"], text=True)
        return path


if __name__ == "__main__":
    unittest.main()
