from __future__ import annotations

import unittest
import sys
from pathlib import Path

from scripts.evaluation.rd_eval_prepare_coding_benchmark import ReadinessError, validate_case_contract, verify_case


class PrepareCodingBenchmarkTest(unittest.TestCase):
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
