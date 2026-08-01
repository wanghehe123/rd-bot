from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
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
    VALID_DIGEST = "sha256:" + "b" * 64

    def _minimal_case(self, **overrides) -> dict:
        base = {
            "caseId": "case-01",
            "slice": "FRESH_PRIMARY",
            "baseCommit": self.BASE_COMMIT,
            "freshnessEvidence": {"kind": "PRIVATE_TASK", "reference": "task-01"},
            "repositoryMirrorId": "private/rd-bot",
            "platform": "linux/amd64",
            "agentImageDigest": self.VALID_DIGEST,
            "verifierImageDigest": self.VALID_DIGEST,
            "dependencyBundleSha256": self.VALID_DIGEST,
            "oracleTestBundleSha256": self.VALID_DIGEST,
            "toolchainSha256": self.VALID_DIGEST,
            "privateRepoTreeSha256": self.VALID_DIGEST,
            "agentTestCommands": ["true"],
            "oracleTestCommands": ["true"],
            "agentNetworkPolicy": "relay-only",
            "oracleNetworkMode": "offline",
            "browserQaRequired": False,
            "cpuLimit": "4",
            "memoryLimit": "8Gi",
            "pidsLimit": 512,
            "storageLimit": "20Gi",
            "shmSize": "256Mi",
            "language": "JAVA",
            "difficulty": "EASY",
            "leakageAuditVerdict": "CLEAN",
        }
        base.update(overrides)
        return base

    def test_config_requires_ten_fresh_and_ten_public_cases(self) -> None:
        cases = [
            {
                "caseId": f"fresh-{index:02d}",
                "slice": "FRESH_PRIMARY",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PRIVATE_TASK",
                    "reference": f"task-{index}",
                },
            }
            for index in range(1, 10)
        ] + [
            {
                "caseId": f"public-{index:02d}",
                "slice": "PUBLIC_ANCHOR",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PUBLIC_ANCHOR",
                    "dataset": "ByteDance-Seed/Multi-SWE-bench",
                    "datasetRevision": "56ff018c04a38e27ada1e9d0a6d5839a51f88f0d",
                },
            }
            for index in range(1, 12)
        ]

        with self.assertRaisesRegex(
            ReadinessError, "10 FRESH_PRIMARY and 10 PUBLIC_ANCHOR"
        ):
            validate_benchmark_config({"cases": cases})

    def test_fresh_case_requires_auditable_private_or_post_cutoff_evidence(
        self,
    ) -> None:
        cases = [
            {
                "caseId": f"fresh-{index:02d}",
                "slice": "FRESH_PRIMARY",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PRIVATE_TASK",
                    "reference": f"task-{index}",
                },
            }
            for index in range(1, 11)
        ] + [
            {
                "caseId": f"public-{index:02d}",
                "slice": "PUBLIC_ANCHOR",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PUBLIC_ANCHOR",
                    "dataset": "ByteDance-Seed/Multi-SWE-bench",
                    "datasetRevision": "56ff018c04a38e27ada1e9d0a6d5839a51f88f0d",
                },
            }
            for index in range(1, 11)
        ]

        # Remove freshnessEvidence from a FRESH_PRIMARY case
        cases[5].pop("freshnessEvidence")

        with self.assertRaisesRegex(ReadinessError, "freshnessEvidence"):
            validate_benchmark_config({"cases": cases})

    def test_formal_case_requires_an_immutable_base_commit(self) -> None:
        cases = [
            {
                "caseId": f"fresh-{index:02d}",
                "slice": "FRESH_PRIMARY",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PRIVATE_TASK",
                    "reference": f"task-{index}",
                },
            }
            for index in range(1, 11)
        ] + [
            {
                "caseId": f"public-{index:02d}",
                "slice": "PUBLIC_ANCHOR",
                "baseCommit": self.BASE_COMMIT,
                "freshnessEvidence": {
                    "kind": "PUBLIC_ANCHOR",
                    "dataset": "ByteDance-Seed/Multi-SWE-bench",
                    "datasetRevision": "56ff018c04a38e27ada1e9d0a6d5839a51f88f0d",
                },
            }
            for index in range(1, 11)
        ]
        cases[-1].pop("baseCommit")

        with self.assertRaisesRegex(ReadinessError, "baseCommit"):
            validate_benchmark_config({"cases": cases})

    def test_base_history_requires_the_declared_commit_and_ancestors(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repository = Path(temp_dir)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.com"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test User"], cwd=repository, check=True
            )
            (repository / "README.md").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=repository, check=True)
            base_commit = (
                subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], cwd=repository, text=True
                )
                .strip()
            )

            verify_base_history(
                {
                    "caseId": "case-01",
                    "baseCommit": base_commit,
                    "repositoryMirrorId": ".",
                },
                repository,
            )
            with self.assertRaisesRegex(ReadinessError, "baseCommit object"):
                verify_base_history(
                    {
                        "caseId": "case-02",
                        "baseCommit": "b" * 40,
                        "repositoryMirrorId": ".",
                    },
                    repository,
                )

    def test_readiness_contract_requires_agent_test_commands(self) -> None:
        """Spec §7.2: agentTestCommands is required and must be a non-empty list."""
        case = self._minimal_case()
        del case["agentTestCommands"]
        with self.assertRaisesRegex(ReadinessError, "agentTestCommands"):
            validate_case_contract(case)

    def test_readiness_contract_requires_oracle_test_commands(self) -> None:
        """Spec §7.2: oracleTestCommands is required and must be a non-empty list."""
        case = self._minimal_case()
        del case["oracleTestCommands"]
        with self.assertRaisesRegex(ReadinessError, "oracleTestCommands"):
            validate_case_contract(case)

    def test_readiness_contract_rejects_floating_agent_image_digest(self) -> None:
        """Spec §7.2: agentImageDigest must be sha256: prefix + 64 hex chars."""
        case = self._minimal_case(
            agentImageDigest="registry.example/agent:latest"
        )
        with self.assertRaisesRegex(ReadinessError, "agentImageDigest.*sha256"):
            validate_case_contract(case)

    def test_readiness_contract_rejects_floating_verifier_image_digest(self) -> None:
        """Spec §7.2: verifierImageDigest must be sha256: prefix + 64 hex chars."""
        case = self._minimal_case(
            verifierImageDigest="registry.example/oracle:latest"
        )
        with self.assertRaisesRegex(ReadinessError, "verifierImageDigest.*sha256"):
            validate_case_contract(case)

    def test_readiness_contract_rejects_missing_sha256_prefix_on_dependency_bundle(
        self,
    ) -> None:
        """Spec §7.2: dependencyBundleSha256 must have sha256: prefix."""
        case = self._minimal_case(
            dependencyBundleSha256="sha256:" + "a" * 64
        )
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(
            dependencyBundleSha256="sha256:REPLACE_WITH_ACTUAL_..."
        )
        with self.assertRaisesRegex(
            ReadinessError, "dependencyBundleSha256.*sha256"
        ):
            validate_case_contract(case_bad)

    def test_readiness_contract_enforces_network_policy_enum(self) -> None:
        """Spec §7.2: agentNetworkPolicy must be an allowed value."""
        case = self._minimal_case(agentNetworkPolicy="relay-only")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(agentNetworkPolicy="PUBLIC_INTERNET")
        with self.assertRaisesRegex(
            ReadinessError, "agentNetworkPolicy.*relay-only.*MODEL_RELAY_ONLY"
        ):
            validate_case_contract(case_bad)

    def test_readiness_contract_enforces_oracle_network_mode_enum(self) -> None:
        """Spec §7.2: oracleNetworkMode must be 'offline' or 'none'."""
        case = self._minimal_case(oracleNetworkMode="offline")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(oracleNetworkMode="internet")
        with self.assertRaisesRegex(ReadinessError, "oracleNetworkMode.*offline.*none"):
            validate_case_contract(case_bad)

    def test_readiness_contract_enforces_language_enum(self) -> None:
        """Spec §7.2: language must be JAVA or TSJS."""
        case = self._minimal_case(language="JAVA")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(language="PYTHON")
        with self.assertRaisesRegex(ReadinessError, "language.*JAVA.*TSJS"):
            validate_case_contract(case_bad)

    def test_readiness_contract_enforces_difficulty_enum(self) -> None:
        """Spec §7.2: difficulty must be EASY, MEDIUM, or HARD."""
        case = self._minimal_case(difficulty="MEDIUM")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(difficulty="EXPERT")
        with self.assertRaisesRegex(ReadinessError, "difficulty.*EASY.*MEDIUM.*HARD"):
            validate_case_contract(case_bad)

    def test_readiness_contract_enforces_leakage_audit_verdict_enum(self) -> None:
        """Spec §7.2: leakageAuditVerdict must be CLEAN, LEAK, or UNAUDITED."""
        case = self._minimal_case(leakageAuditVerdict="CLEAN")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(leakageAuditVerdict="UNKNOWN")
        with self.assertRaisesRegex(
            ReadinessError, "leakageAuditVerdict.*CLEAN.*LEAK.*UNAUDITED"
        ):
            validate_case_contract(case_bad)

    def test_readiness_contract_requires_pids_limit_positive_integer(self) -> None:
        """Spec §7.2: pidsLimit must be a positive integer."""
        case = self._minimal_case(pidsLimit=512)
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(pidsLimit=-1)
        with self.assertRaisesRegex(ReadinessError, "pidsLimit.*positive integer"):
            validate_case_contract(case_bad)

    def test_readiness_contract_requires_all_sha256_bundle_fields(self) -> None:
        """Spec §7.2: oracleTestBundleSha256, toolchainSha256, privateRepoTreeSha256."""
        required_fields = [
            "oracleTestBundleSha256",
            "toolchainSha256",
            "privateRepoTreeSha256",
        ]
        for field in required_fields:
            case = self._minimal_case()
            del case[field]
            with self.assertRaisesRegex(ReadinessError, field):
                validate_case_contract(case)

    def test_readiness_contract_requires_repository_mirror_id(self) -> None:
        """Spec §7.2: repositoryMirrorId is required."""
        case = self._minimal_case()
        del case["repositoryMirrorId"]
        with self.assertRaisesRegex(ReadinessError, "repositoryMirrorId"):
            validate_case_contract(case)

    def test_readiness_contract_requires_platform(self) -> None:
        """Spec §7.2: platform is required and must be linux/amd64 or linux/arm64."""
        case = self._minimal_case(platform="linux/amd64")
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(platform="darwin/arm64")
        with self.assertRaisesRegex(ReadinessError, "platform.*linux"):
            validate_case_contract(case_bad)

    def test_readiness_contract_public_anchor_requires_dataset_and_revision(
        self,
    ) -> None:
        """Spec §7.2: PUBLIC_ANCHOR freshnessEvidence requires dataset + datasetRevision."""
        case = self._minimal_case(
            slice="PUBLIC_ANCHOR",
            freshnessEvidence={
                "kind": "PUBLIC_ANCHOR",
                "dataset": "ByteDance-Seed/Multi-SWE-bench",
                "datasetRevision": "56ff018c04a38e27ada1e9d0a6d5839a51f88f0d",
            },
        )
        validate_case_contract(case)  # valid

        case_bad = self._minimal_case(
            slice="PUBLIC_ANCHOR",
            freshnessEvidence={"kind": "PUBLIC_ANCHOR"},
        )
        with self.assertRaisesRegex(
            ReadinessError, "PUBLIC_ANCHOR.*dataset.*datasetRevision"
        ):
            validate_case_contract(case_bad)

    def test_readiness_runs_agent_and_oracle_test_commands_offline(self) -> None:
        """Spec §7.2: verify_case runs agentTestCommands and oracleTestCommands with offline guards."""
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            # repositoryMirrorId is a relative path within the root
            mirror_dir = repo_root / "private" / "rd-bot"
            mirror_dir.mkdir(parents=True)
            (mirror_dir / "README.md").write_text("test\n", encoding="utf-8")

            case = self._minimal_case(
                repositoryMirrorId="private/rd-bot",
                agentTestCommands=[sys.executable, "-c", "pass"],
                oracleTestCommands=[sys.executable, "-c", "pass"],
            )

            result = verify_case(
                case, repository_root=repo_root, timeout_seconds=5
            )

            self.assertTrue(result.passed)
            # 3 agent rounds + 1 oracle = 4 runs
            self.assertEqual(4, len(result.runs))
            self.assertEqual(
                ["AGENT_TEST"] * 3 + ["ORACLE_TEST"],
                [run["phase"] for run in result.runs],
            )


if __name__ == "__main__":
    unittest.main()
