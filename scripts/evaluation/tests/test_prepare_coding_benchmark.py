from __future__ import annotations

import unittest
import sys
import subprocess
import tempfile
from pathlib import Path

from scripts.evaluation.rd_eval_prepare_coding_benchmark import (
    ReadinessError,
    validate_benchmark_config,
    validate_case_contract,
    verify_base_history,
    verify_case,
)


class PrepareCodingBenchmarkTest(unittest.TestCase):
    BASE_COMMIT = "a" * 40

    def test_config_requires_ten_fresh_and_ten_public_cases(self) -> None:
        cases = [
            {"caseId": f"fresh-{index:02d}", "slice": "FRESH_PRIMARY", "baseCommit": self.BASE_COMMIT}
            for index in range(1, 10)
        ] + [
            {"caseId": f"public-{index:02d}", "slice": "PUBLIC_ANCHOR", "baseCommit": self.BASE_COMMIT}
            for index in range(1, 12)
        ]

        with self.assertRaisesRegex(ReadinessError, "10 FRESH_PRIMARY and 10 PUBLIC_ANCHOR"):
            validate_benchmark_config({"cases": cases})

    def test_fresh_case_requires_auditable_private_or_post_cutoff_evidence(self) -> None:
        cases = [
            {"caseId": f"fresh-{index:02d}", "slice": "FRESH_PRIMARY", "baseCommit": self.BASE_COMMIT}
            for index in range(1, 11)
        ] + [
            {"caseId": f"public-{index:02d}", "slice": "PUBLIC_ANCHOR", "baseCommit": self.BASE_COMMIT}
            for index in range(1, 11)
        ]

        with self.assertRaisesRegex(ReadinessError, "freshnessEvidence"):
            validate_benchmark_config({"cases": cases})

    def test_formal_case_requires_an_immutable_base_commit(self) -> None:
        cases = [
            {
                "caseId": f"fresh-{index:02d}",
                "slice": "FRESH_PRIMARY",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {"kind": "PRIVATE_TASK", "reference": f"task-{index}"},
            }
            for index in range(1, 11)
        ] + [
            {"caseId": f"public-{index:02d}", "slice": "PUBLIC_ANCHOR", "baseCommit": self.BASE_COMMIT}
            for index in range(1, 11)
        ]
        cases[-1].pop("baseCommit")

        with self.assertRaisesRegex(ReadinessError, "baseCommit"):
            validate_benchmark_config({"cases": cases})

    def test_base_history_requires_the_declared_commit_and_ancestors(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repository, check=True)
            subprocess.run(["git", "config", "user.name", "Test User"], cwd=repository, check=True)
            (repository / "README.md").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=repository, check=True)
            base_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()

            verify_base_history({"baseCommit": base_commit}, repository)
            with self.assertRaisesRegex(ReadinessError, "baseCommit object"):
                verify_base_history({"baseCommit": "b" * 40}, repository)

    def test_readiness_contract_requires_all_three_fixed_commands(self) -> None:
        with self.assertRaisesRegex(ReadinessError, "fixCommand"):
            validate_case_contract({
                "caseId": "case-01",
                "baseCommand": ["true"],
                "testCommand": ["true"],
            })

    def test_readiness_contract_rejects_floating_image(self) -> None:
        with self.assertRaisesRegex(ReadinessError, "immutable image"):
            validate_case_contract({
                "caseId": "case-01",
                "agentImage": "registry.example/agent:latest",
                "baseCommand": ["true"],
                "testCommand": ["true"],
                "fixCommand": ["true"],
            })

    def test_readiness_runs_base_test_and_fix_three_times_without_package_network_access(self) -> None:
        case = {
            "caseId": "case-01",
            "agentImage": "registry.example/agent@sha256:" + "a" * 64,
            "oracleImage": "registry.example/oracle@sha256:" + "b" * 64,
            "baseCommand": [sys.executable, "-c", "pass"],
            "testCommand": [sys.executable, "-c", "pass"],
            "fixCommand": [sys.executable, "-c", "pass"],
        }

        result = verify_case(case, repository_root=Path.cwd(), timeout_seconds=5)

        self.assertTrue(result.passed)
        self.assertEqual(9, len(result.runs))
        self.assertEqual(["BASE", "TEST", "FIX"] * 3, [run["phase"] for run in result.runs])


if __name__ == "__main__":
    unittest.main()
