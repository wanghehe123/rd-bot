import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from unittest import mock
from pathlib import Path

from scripts.evaluation import rd_eval_lib as lib


class RdEvalLibTest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tmpdir.name)

    def tearDown(self):
        self.tmpdir.cleanup()

    def write_jsonl(self, rows, relative="dataset.jsonl"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )
        return path

    def touch(self, relative, content, delay=0.01):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        time.sleep(delay)
        return path

    @staticmethod
    def task_run_sample(expected_roles=None):
        return {
            "sample_id": "TASK-XML-1",
            "suite": "task-run",
            "scenario": "商品管理交付",
            "input": {
                "taskId": "task-xml-1",
                "taskType": "REQUIREMENT",
                "title": "商品管理",
                "priority": "P1",
                "repositoryUrl": "https://github.com/example/waimai",
                "baseBranch": "main",
                "expectedResult": "管理员可以维护商品信息",
                "acceptanceCriteria": ["前端构建通过", "商品保存接口返回 200"],
            },
            "task_run_gold": {
                "expectedRoles": expected_roles or [
                    "REQUIREMENT_REVIEWER",
                    "SOLUTION_ARCHITECT",
                    "CODING_AGENT",
                    "QA_AGENT",
                ],
            },
        }

    @staticmethod
    def task_run_record(stage_results=None, response=""):
        return {
            "sample_id": "TASK-XML-1",
            "task_id": "task-xml-1",
            "status": "RECORDED",
            "final_status": "success",
            "stages": stage_results or {},
            "task_run": {
                "taskStatus": "MERGED",
                "taskType": "REQUIREMENT",
                "contexts": [],
                "retrievalRuns": [],
                "pullRequestUrl": "https://github.com/example/waimai/pull/7",
                "testEvidenceCount": 1,
            },
            "response": response,
        }

    @staticmethod
    def local_task_metrics():
        return [
            {
                "name": "retrieval_run_coverage_rate",
                "label": "检索运行覆盖率",
                "value": 0.0,
                "status": "FAILED",
                "direction": ">=",
                "threshold": 1.0,
                "purpose": "验证每个要求的 Deep RAG 检索消费者都有成功运行记录",
                "calculation": "成功 RetrievalRun 数除以要求的消费者数",
                "failureReason": "缺少 QA_AGENT 的 RetrievalRun",
            },
            {
                "name": "stage_success_rate",
                "label": "角色阶段成功率",
                "value": 1.0,
                "status": "PASS",
                "direction": ">=",
                "threshold": 1.0,
                "purpose": "验证要求的角色阶段都已成功",
                "calculation": "成功阶段数除以要求阶段数",
            },
        ]

    def test_load_jsonl_rejects_missing_sample_id(self):
        path = self.write_jsonl([{"suite": "rag"}])

        with self.assertRaises(ValueError) as error:
            lib.load_dataset(path)

        self.assertIn("sample_id", str(error.exception))

    def test_rd_eval_dataclasses_validate_schema_boundary(self):
        sample = lib.RdEvalSample.from_dict(
            {
                "sample_id": "S-SCHEMA",
                "suite": "rag",
                "input": {"title": "标题"},
                "rag_gold": {"mustEvidenceUris": ["knowledge://a"]},
            }
        )
        record = lib.RdEvalRecord.from_dict(
            {
                "run_id": "run-1",
                "sample_id": "S-SCHEMA",
                "status": "COMPLETED",
                "rag": {"retrievedEvidenceUris": ["knowledge://a"]},
            }
        )
        metric = lib.RdMetricResult.from_dict(
            {"name": "hit@5", "value": 1.0, "sampleCount": 1, "status": "PASS"}
        )

        self.assertEqual(sample.sample_id, "S-SCHEMA")
        self.assertEqual(record.run_id, "run-1")
        self.assertEqual(metric.name, "hit@5")
        with self.assertRaises(ValueError):
            lib.RdEvalSample.from_dict({"suite": "rag"})

    def test_fixture_runner_copies_fixture_record_and_adds_run_metadata(self):
        sample = {
            "sample_id": "S1",
            "suite": "rag",
            "fixture_record": {
                "status": "COMPLETED",
                "rag": {"retrievedEvidenceUris": ["knowledge://a"]},
            },
        }

        records = lib.records_from_fixtures([sample], "run-1", "local", "dataset.jsonl")

        self.assertEqual(records[0]["sample_id"], "S1")
        self.assertEqual(records[0]["run_id"], "run-1")
        self.assertEqual(records[0]["environment_id"], "local")
        self.assertEqual(records[0]["dataset_path"], "dataset.jsonl")
        self.assertEqual(records[0]["rag"]["retrievedEvidenceUris"], ["knowledge://a"])

    def test_fixture_runner_can_load_records_from_a_physically_separate_file(self):
        sample = {"sample_id": "QUALITY-1", "suite": "rag", "input": {"question": "q"}}
        fixture_records = [
            {
                "sample_id": "QUALITY-1",
                "status": "COMPLETED",
                "rag": {"retrievedEvidenceUris": ["knowledge://kb/evidence"]},
            }
        ]

        records = lib.records_from_fixtures(
            [sample], "quality-run", "local", "quality.jsonl", fixture_records
        )

        self.assertNotIn("fixture_record", sample)
        self.assertEqual(records[0]["sample_id"], "QUALITY-1")
        self.assertEqual(records[0]["run_id"], "quality-run")
        self.assertEqual(records[0]["rag"]["retrievedEvidenceUris"], ["knowledge://kb/evidence"])

    def test_quality_benchmark_has_48_physically_separated_curated_rows(self):
        from scripts.evaluation import build_quality_benchmark as benchmark

        samples, records = benchmark.build()

        self.assertEqual(48, len(samples))
        self.assertEqual(48, len(records))
        self.assertEqual(
            {"rag": 20, "requirement": 4, "solution": 4, "coding": 4, "qa": 4, "e2e": 8, "task-run": 4},
            {
                suite: sum(1 for sample in samples if sample["suite"] == suite)
                for suite in {sample["suite"] for sample in samples}
            },
        )
        self.assertTrue(all("fixture_record" not in sample for sample in samples))
        self.assertEqual(
            {sample["sample_id"] for sample in samples},
            {record["sample_id"] for record in records},
        )
        role_samples = [sample for sample in samples if "role" in sample.get("tags", [])]
        for role in lib.ALL_ROLES:
            matching = [sample for sample in role_samples if role in sample.get("tags", [])]
            self.assertEqual(4, len(matching))
            self.assertEqual(2, sum("blocked" in sample.get("tags", []) for sample in matching))

    def test_role_block_gold_requires_target_absence_expected_task_and_gate_status(self):
        sample = {
            "sample_id": "ROLE-BLOCK",
            "suite": "coding",
            "role_block_gold": {
                "blockedRole": "CODING_AGENT",
                "expectedTaskStatuses": ["FAILED_NEEDS_HUMAN"],
                "requiredGateRole": "SOLUTION_ARCHITECT",
                "expectedGateStatuses": ["FAILED_NEEDS_HUMAN"],
            },
        }
        valid_record = {
            "sample_id": "ROLE-BLOCK",
            "status": "FAILED_NEEDS_HUMAN",
            "stages": {"SOLUTION_ARCHITECT": {"status": "FAILED_NEEDS_HUMAN"}},
        }
        leaked_record = {
            **valid_record,
            "stages": {
                "SOLUTION_ARCHITECT": {"status": "FAILED_NEEDS_HUMAN"},
                "CODING_AGENT": {"status": "RUNNING"},
            },
        }

        valid = lib.score_records([sample], [valid_record], judge_provider=lib.NoopJudgeProvider())
        leaked = lib.score_records([sample], [leaked_record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(valid, "role_blocking_accuracy", 1.0)
        self.assertMetric(leaked, "role_blocking_accuracy", 0.0)
        self.assertFailureMetric(leaked, "role_blocking_accuracy")

    def test_score_rag_evidence_and_requirement_blocking(self):
        samples = [
            {
                "sample_id": "S1",
                "suite": "requirement",
                "rag_gold": {
                    "expectedIntentSystemId": "payment",
                    "mustEvidenceUris": ["knowledge://payment/callback"],
                    "niceEvidenceUris": ["code://OrderService#markPaid"],
                },
                "stage_gold": {
                    "REQUIREMENT_REVIEWER": {
                        "expectedDecision": "NEED_INFO",
                        "mustMentionRisks": ["缺少回调日志"],
                        "mustBlockDownstream": True,
                    }
                },
            }
        ]
        records = [
            {
                "sample_id": "S1",
                "run_id": "run-1",
                "status": "FAILED_NEEDS_HUMAN",
                "rag": {
                    "primaryIntentSystemId": "payment",
                    "retrievedEvidenceUris": [
                        "knowledge://payment/callback",
                        "code://OrderService#markPaid",
                    ],
                },
                "stages": {
                    "REQUIREMENT_REVIEWER": {
                        "status": "FAILED_NEEDS_HUMAN",
                        "resultJson": {
                            "decision": "NEED_INFO",
                            "risks": ["缺少回调日志"],
                        },
                    }
                },
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "intent_top1", 1.0)
        self.assertMetric(result, "evidence_hit@5", 1.0)
        self.assertMetric(result, "evidence_recall@5", 1.0)
        self.assertMetric(result, "mrr@10", 1.0)
        self.assertMetric(result, "requirement_decision_accuracy", 1.0)
        self.assertMetric(result, "downstream_leak_rate", 0.0)

    def test_forbidden_file_and_skipped_qa_create_failures(self):
        samples = [
            {
                "sample_id": "S2",
                "suite": "qa",
                "stage_gold": {
                    "CODING_AGENT": {
                        "expectedChangedFiles": ["src/PaymentCallbackService.java"],
                        "forbiddenChangedFiles": [".env"],
                        "requiredTestCommands": ["./mvnw test"],
                    },
                    "QA_AGENT": {
                        "mustRunRealCommands": True,
                        "expectedAcceptanceStatuses": ["PASSED"],
                        "maxSkippedAcceptanceCount": 0,
                    },
                },
            }
        ]
        records = [
            {
                "sample_id": "S2",
                "run_id": "run-1",
                "status": "COMPLETED",
                "stages": {
                    "CODING_AGENT": {
                        "status": "SUCCEEDED",
                        "resultJson": {
                            "changedFiles": ["src/PaymentCallbackService.java", ".env"],
                            "testCommands": [{"command": "./mvnw test", "status": "PASSED"}],
                        },
                    },
                    "QA_AGENT": {
                        "status": "SUCCEEDED",
                        "resultJson": {
                            "acceptanceResults": [
                                {
                                    "id": "AC-1",
                                    "status": "SKIPPED",
                                    "command": "",
                                    "logArtifactUri": "",
                                }
                            ]
                        },
                    },
                },
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "forbidden_file_touch_rate", 1.0)
        self.assertMetric(result, "skipped_acceptance_rate", 1.0)
        self.assertTrue(result["failures"])

    def test_latest_file_picks_newest_jsonl(self):
        self.touch("runs/old.jsonl", "old")
        newer = self.touch("runs/new.jsonl", "new")

        self.assertEqual(lib.latest_file(self.root / "runs", ".jsonl"), newer)

    def test_render_report_includes_failed_metric_and_next_action(self):
        score = {
            "run_id": "run-1",
            "metrics": [
                {
                    "name": "evidence_hit@5",
                    "value": 0.0,
                    "threshold": 0.9,
                    "direction": ">=",
                    "status": "FAILED",
                }
            ],
            "failures": [
                {
                    "sampleId": "S1",
                    "metric": "evidence_hit@5",
                    "score": 0.0,
                    "threshold": 0.9,
                    "nextAction": "检查知识库",
                }
            ],
            "per_sample": [],
        }

        rendered = lib.render_markdown_report(score)

        self.assertIn("evidence_hit@5", rendered)
        self.assertIn("nextAction", rendered)
        self.assertIn("检查知识库", rendered)

    def test_score_records_emits_metric_metadata_and_human_summary(self):
        samples = [
            {
                "sample_id": "TASK-1",
                "suite": "task-run",
                "scenario": "商品管理交付",
                "task_run_gold": {
                    "expectedRetrievalConsumers": ["REQUIREMENT_BASE"],
                },
            }
        ]
        records = [
            {
                "sample_id": "TASK-1",
                "run_id": "run-1",
                "task_run": {
                    "taskStatus": "MERGED",
                    "timeline": [{"status": "MERGED"}],
                    "retrievalRuns": [],
                    "testEvidenceCount": 1,
                    "pullRequestUrl": "https://github.com/example/repo/pull/1",
                },
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        metric = next(item for item in result["metrics"] if item["name"] == "retrieval_run_coverage_rate")
        self.assertEqual(metric["label"], "检索运行覆盖率")
        self.assertIn("Deep RAG", metric["purpose"])
        self.assertIn("成功", metric["calculation"])
        self.assertEqual(result["summary"]["overallStatus"], "NOT_OK")
        self.assertEqual(result["summary"]["gateStatus"], "NOT_PASSED")
        self.assertEqual(result["summary"]["failedMetricCount"], 3)
        self.assertEqual(result["summary"]["failedMetrics"][0]["name"], "retrieval_run_coverage_rate")
        self.assertIn("检索", result["summary"]["headline"])
        unknown = lib.metric_definition("future_metric")
        self.assertEqual(unknown["label"], "future_metric")
        self.assertTrue(unknown["purpose"])

    def test_markdown_report_renders_human_readable_summary(self):
        score = {
            "run_id": "run-summary",
            "summary": {
                "overallStatus": "NOT_OK",
                "headline": "发现 1 项未达标指标，优先处理：检索运行覆盖率。",
                "localMetricCount": 3,
                "passedMetricCount": 2,
                "failedMetricCount": 1,
                "skippedMetricCount": 0,
                "failedMetrics": [
                    {
                        "name": "retrieval_run_coverage_rate",
                        "label": "检索运行覆盖率",
                        "reason": "required Deep RAG retrieval run is missing",
                        "nextAction": "补齐 RetrievalRun",
                    }
                ],
                "judge": {
                    "provider": "openai-compatible",
                    "status": "AVAILABLE",
                    "evaluatedSampleCount": 1,
                    "headline": "Judge 已确认回答与证据一致。",
                    "strengths": ["回答与任务结果一致"],
                    "risks": ["缺少检索运行审计"],
                    "nextActions": ["补齐 RetrievalRun"],
                },
            },
            "metrics": [],
            "failures": [],
            "per_sample": [],
        }

        rendered = lib.render_markdown_report(score)

        self.assertIn("## 简要评测报告", rendered)
        self.assertIn("发现 1 项未达标指标", rendered)
        self.assertIn("Judge: `AVAILABLE`", rendered)
        self.assertIn("检索运行覆盖率", rendered)
        self.assertIn("补齐 RetrievalRun", rendered)

    def test_noop_judge_marks_metrics_skipped(self):
        samples = [
            {
                "sample_id": "S3",
                "suite": "rag",
                "rag_gold": {"mustEvidenceUris": ["knowledge://a"]},
            }
        ]
        records = [
            {
                "sample_id": "S3",
                "run_id": "run-1",
                "rag": {"retrievedEvidenceUris": ["knowledge://a"]},
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        judge_metrics = [m for m in result["metrics"] if m["name"] == "faithfulness"]
        self.assertEqual(judge_metrics[0]["status"], "SKIPPED")

    def test_ragas_thresholds_match_rag_eval_reference(self):
        class LowJudge(lib.JudgeProvider):
            name = "low-judge"
            enabled = True

            def evaluate(self, sample, record):
                return {
                    "faithfulness": 0.89,
                    "answer_relevancy": 0.84,
                    "answer_correctness": 0.79,
                    "context_precision": 0.74,
                    "context_recall": 0.79,
                }

        result = lib.score_records(
            [{"sample_id": "S-JUDGE", "suite": "rag"}],
            [{"sample_id": "S-JUDGE", "run_id": "run-1"}],
            judge_provider=LowJudge(),
        )

        failed = {failure["metric"] for failure in result["failures"]}
        self.assertTrue(set(lib.JUDGE_METRIC_NAMES).issubset(failed))

    def test_openai_compatible_provider_reads_non_secret_settings_from_yaml(self):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )

        provider = lib.OpenAICompatibleJudgeProvider.from_env(
            {"RD_EVAL_JUDGE_API_KEY": "test-only-value"},
            config_path=config_path,
        )

        self.assertEqual(provider.base_url, "https://judge.example.test/v1/chat/completions")
        self.assertEqual(provider.model, "judge-model")

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
    def test_openai_compatible_provider_reads_key_from_launchctl_when_process_environment_is_empty(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )
        run.return_value = subprocess.CompletedProcess(
            ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
            0,
            "launchctl-test-key\n",
            "",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            provider = lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

        self.assertEqual(provider.api_key, "launchctl-test-key")
        run.assert_called_once_with(
            ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
            capture_output=True,
            check=False,
            text=True,
            timeout=1.0,
        )

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
    def test_openai_compatible_provider_does_not_expose_failed_launchctl_output(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )
        run.return_value = subprocess.CompletedProcess(
            ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
            1,
            "test-only-key-must-not-leak\n",
            "launchctl failure",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError) as error:
                lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

        self.assertIn("RD_EVAL_JUDGE_API_KEY", str(error.exception))
        self.assertNotIn("test-only-key-must-not-leak", str(error.exception))

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
    def test_openai_compatible_provider_treats_blank_launchctl_output_as_missing_key(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )
        run.return_value = subprocess.CompletedProcess(
            ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
            0,
            "\n",
            "",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "RD_EVAL_JUDGE_API_KEY"):
                lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
    def test_openai_compatible_provider_treats_launchctl_timeout_as_missing_key(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )
        run.side_effect = subprocess.TimeoutExpired(
            ["/bin/launchctl", "getenv", "RD_EVAL_JUDGE_API_KEY"],
            1.0,
            output="test-only-key-must-not-leak",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError) as error:
                lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

        self.assertIn("RD_EVAL_JUDGE_API_KEY", str(error.exception))
        self.assertNotIn("test-only-key-must-not-leak", str(error.exception))

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run", side_effect=OSError("unavailable"))
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Darwin")
    def test_openai_compatible_provider_treats_launchctl_os_error_as_missing_key(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "RD_EVAL_JUDGE_API_KEY") as error:
                lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

        self.assertNotIn("unavailable", str(error.exception))

    @mock.patch("scripts.evaluation.rd_eval_lib.subprocess.run")
    @mock.patch("scripts.evaluation.rd_eval_lib.platform.system", return_value="Linux")
    def test_openai_compatible_provider_does_not_invoke_launchctl_off_macos(
            self, platform_system, run
    ):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )

        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "RD_EVAL_JUDGE_API_KEY"):
                lib.OpenAICompatibleJudgeProvider.from_env(config_path=config_path)

        run.assert_not_called()

    def test_openai_compatible_provider_requires_api_key_without_exposing_value(self):
        config_path = self.root / "evaluation-judge.yaml"
        config_path.write_text(
            "RD_EVAL_JUDGE_BASE_URL: https://judge.example.test/v1\n"
            "RD_EVAL_JUDGE_MODEL: judge-model\n",
            encoding="utf-8",
        )

        with self.assertRaises(ValueError) as error:
            lib.OpenAICompatibleJudgeProvider.from_env({}, config_path=config_path)

        message = str(error.exception)
        self.assertIn("RD_EVAL_JUDGE_API_KEY", message)
        self.assertNotIn("RD_EVAL_JUDGE_BASE_URL", message)
        self.assertNotIn("RD_EVAL_JUDGE_MODEL", message)
        self.assertNotIn("s" + "k-", message)

    @mock.patch("scripts.evaluation.rd_eval_lib.urllib.request.urlopen")
    def test_openai_compatible_provider_accepts_standard_openai_base_url(self, urlopen):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self):
                return json.dumps(
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "faithfulness": 1,
                                            "answer_relevancy": 1,
                                            "answer_correctness": 1,
                                            "context_precision": 1,
                                            "context_recall": 1,
                                        }
                                    )
                                }
                            }
                        ]
                    }
                ).encode("utf-8")

        urlopen.return_value = FakeResponse()
        provider = lib.OpenAICompatibleJudgeProvider(
            base_url="https://aihubmix.com/v1",
            api_key="test-key",
            model="test-model",
        )

        result = provider.evaluate({"sample_id": "S1"}, {"sample_id": "S1"})

        self.assertEqual(
            urlopen.call_args.args[0].full_url,
            "https://aihubmix.com/v1/chat/completions",
        )
        self.assertEqual(result["faithfulness"], 1.0)

    @mock.patch("scripts.evaluation.rd_eval_lib.urllib.request.urlopen")
    def test_openai_judge_receives_local_metrics_and_returns_bounded_narrative(self, urlopen):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self):
                return json.dumps(
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "scores": {
                                                "faithfulness": 0.95,
                                                "answer_relevancy": 0.96,
                                                "answer_correctness": 0.97,
                                                "context_precision": 0.94,
                                                "context_recall": 0.93,
                                            },
                                            "headline": "证据引用完整，优先补齐 Deep RAG 运行记录。",
                                            "strengths": ["回答引用与任务结果一致"],
                                            "risks": {"unexpected": "object"},
                                            "nextActions": ["补齐 retrieval run 审计记录"],
                                        }
                                    )
                                }
                            }
                        ]
                    }
                ).encode("utf-8")

        urlopen.return_value = FakeResponse()
        provider = lib.OpenAICompatibleJudgeProvider(
            base_url="https://aihubmix.com/v1",
            api_key="test-key",
            model="test-model",
        )
        os.environ["RD_BOT_SECRET_SCAN_NEEDLES"] = "SENSITIVEVALUE"
        try:
            assessment = provider.evaluate_with_context(
                {"sample_id": "S1", "token": "SENSITIVEVALUE"},
                {"sample_id": "S1", "status": "COMPLETED"},
                [
                    {
                        "name": "retrieval_run_coverage_rate",
                        "value": 0.0,
                        "status": "FAILED",
                        "failureReason": "SENSITIVEVALUE",
                        "purpose": "验证 Deep RAG 检索运行完整性",
                    }
                ],
            )
        finally:
            os.environ.pop("RD_BOT_SECRET_SCAN_NEEDLES", None)

        content = json.loads(urlopen.call_args.args[0].data.decode("utf-8"))["messages"][1]["content"]
        self.assertIn("local_metrics", content)
        self.assertIn("retrieval_run_coverage_rate", content)
        self.assertNotIn("SENSITIVEVALUE", content)
        self.assertEqual(assessment.scores["faithfulness"], 0.95)
        self.assertEqual(assessment.headline, "证据引用完整，优先补齐 Deep RAG 运行记录。")
        self.assertEqual(assessment.strengths, ["回答引用与任务结果一致"])
        self.assertEqual(assessment.risks, [])
        self.assertEqual(assessment.next_actions, ["补齐 retrieval run 审计记录"])

    def test_task_run_judge_xml_has_three_ordered_sections_and_explains_metrics(self):
        prompt = lib.build_task_run_judge_xml_prompt(
            self.task_run_sample(), self.task_run_record(), self.local_task_metrics()
        )

        root = ET.fromstring(prompt)
        self.assertEqual(root.tag, "rd_bot_task_evaluation")
        self.assertEqual(root.attrib["version"], "2")
        self.assertEqual(root.attrib["input_budget_bytes"], "13000")
        self.assertEqual(root.attrib["output_budget_tokens"], "1500")
        self.assertEqual(
            [child.tag for child in root],
            ["task_input", "rag_evaluation_parameters", "role_execution_results"],
        )
        metric = root.find(
            "./rag_evaluation_parameters/metric[@name='retrieval_run_coverage_rate']"
        )
        self.assertIsNotNone(metric)
        self.assertEqual(metric.attrib["status"], "FAILED")
        self.assertIn("验证每个要求", metric.findtext("purpose"))
        self.assertIn("成功 RetrievalRun", metric.findtext("calculation"))
        self.assertEqual(metric.findtext("failure_reason"), "缺少 QA_AGENT 的 RetrievalRun")

    def test_task_run_judge_xml_contains_selected_evidence_not_just_counts(self):
        record = self.task_run_record()
        record["retrieved_contexts"] = [
            {
                "consumer": "CODING_AGENT",
                "runId": "retrieval-code-1",
                "status": "SUCCEEDED",
                "evidence": [
                    {
                        "evidenceId": "code-product-save",
                        "sourceType": "CODE",
                        "sourceUri": "code://server/src/product/ProductController.java#save",
                        "contentHash": "sha256:code-product-save",
                        "contentPreview": "ProductController.save persists validated product data",
                        "requiredEvidenceType": "CODE_SYMBOL",
                        "selectionReason": "matched coding role and target symbol",
                    }
                ],
            }
        ]

        prompt = lib.build_task_run_judge_xml_prompt(
            self.task_run_sample(), record, self.local_task_metrics()
        )

        root = ET.fromstring(prompt)
        retrieval = root.find(
            "./rag_evaluation_parameters/retrieval_evidence[@consumer='CODING_AGENT']"
        )
        self.assertIsNotNone(retrieval)
        self.assertEqual(retrieval.attrib["run_id"], "retrieval-code-1")
        evidence = retrieval.find("./evidence[@id='code-product-save']")
        self.assertEqual(
            evidence.attrib["uri"],
            "code://server/src/product/ProductController.java#save",
        )
        self.assertEqual(evidence.attrib["hash"], "sha256:code-product-save")
        self.assertIn("ProductController.save", evidence.findtext("preview"))
        self.assertIn("matched coding role", evidence.findtext("selection_reason"))

    def test_task_run_judge_xml_keeps_expected_roles_in_order_when_stage_is_missing(self):
        stages = {
            "CODING_AGENT": {
                "status": "SUCCEEDED",
                "attemptNo": 2,
                "providerName": "long-cat",
                "providerAttempts": [{"status": "SUCCESS"}],
                "contextPackageId": "ctx-code",
                "result": {"summary": "已实现商品保存接口"},
            }
        }
        prompt = lib.build_task_run_judge_xml_prompt(
            self.task_run_sample(), self.task_run_record(stages), self.local_task_metrics()
        )

        root = ET.fromstring(prompt)
        roles = root.findall("./role_execution_results/role")
        self.assertEqual(
            [role.attrib["name"] for role in roles],
            ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"],
        )
        self.assertEqual(roles[0].attrib["status"], "MISSING")
        self.assertEqual(roles[2].attrib["status"], "SUCCEEDED")
        self.assertEqual(roles[2].attrib["attempt_no"], "2")
        self.assertIn("已实现商品保存接口", "".join(roles[2].itertext()))

    def test_task_run_judge_xml_stays_within_budget_and_marks_truncated_role_results(self):
        oversized = "编码结果-" * 10_000
        stages = {
            role: {
                "status": "SUCCEEDED",
                "attemptNo": 1,
                "providerName": "provider",
                "providerAttempts": [{"status": "SUCCESS"}],
                "contextPackageId": f"ctx-{role}",
                "result": {"contentPreview": oversized},
            }
            for role in self.task_run_sample()["task_run_gold"]["expectedRoles"]
        }
        prompt = lib.build_task_run_judge_xml_prompt(
            self.task_run_sample(),
            self.task_run_record(stages, oversized),
            self.local_task_metrics(),
        )

        root = ET.fromstring(prompt)
        self.assertLessEqual(len(prompt.encode("utf-8")), 13_000)
        self.assertEqual(
            [role.attrib["name"] for role in root.findall("./role_execution_results/role")],
            self.task_run_sample()["task_run_gold"]["expectedRoles"],
        )
        omissions = root.findall(".//omitted[@reason='input_budget']")
        self.assertTrue(omissions)
        self.assertTrue(
            all(
                int(node.attrib["original_bytes"]) > int(node.attrib["included_bytes"])
                for node in omissions
            )
        )

    def test_task_run_judge_xml_escapes_and_redacts_before_serializing(self):
        previous_needles = os.environ.get("RD_BOT_SECRET_SCAN_NEEDLES")
        os.environ["RD_BOT_SECRET_SCAN_NEEDLES"] = "SENTINEL_SECRET"
        try:
            sample = self.task_run_sample()
            sample["input"]["title"] = "A < B & C > D SENTINEL_SECRET"
            prompt = lib.build_task_run_judge_xml_prompt(
                sample, self.task_run_record(), self.local_task_metrics()
            )
        finally:
            if previous_needles is None:
                os.environ.pop("RD_BOT_SECRET_SCAN_NEEDLES", None)
            else:
                os.environ["RD_BOT_SECRET_SCAN_NEEDLES"] = previous_needles

        root = ET.fromstring(prompt)
        self.assertEqual(root.findtext("./task_input/title"), "A < B & C > D <redacted>")
        self.assertIn("&lt;", prompt)
        self.assertIn("&amp;", prompt)
        self.assertNotIn("SENTINEL_SECRET", prompt)

    @mock.patch("scripts.evaluation.rd_eval_lib.urllib.request.urlopen")
    def test_openai_judge_sends_bounded_xml_and_completion_reserve(self, urlopen):
        class FakeOpenAiResponse:
            def __init__(self, assessment):
                self.assessment = assessment

            @classmethod
            def with_assessment(cls, scores):
                return cls(
                    {
                        "choices": [
                            {
                                "message": {
                                    "content": json.dumps(
                                        {
                                            "scores": scores,
                                            "headline": "评测完成",
                                            "strengths": [],
                                            "risks": [],
                                            "nextActions": [],
                                        }
                                    )
                                }
                            }
                        ]
                    }
                )

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self):
                return json.dumps(self.assessment).encode("utf-8")

        urlopen.return_value = FakeOpenAiResponse.with_assessment(
            {
                "faithfulness": 1.0,
                "answer_relevancy": 1.0,
                "answer_correctness": 1.0,
                "context_precision": 1.0,
                "context_recall": 1.0,
            }
        )
        provider = lib.OpenAICompatibleJudgeProvider(
            "https://judge.example/v1", "test-key", "test-model"
        )

        assessment = provider.evaluate_with_context(
            self.task_run_sample(), self.task_run_record(), self.local_task_metrics()
        )

        body = json.loads(urlopen.call_args.args[0].data.decode("utf-8"))
        content = body["messages"][1]["content"]
        root = ET.fromstring(content)
        self.assertEqual(root.tag, "rd_bot_task_evaluation")
        self.assertLessEqual(len(content.encode("utf-8")), 13_000)
        self.assertEqual(body["max_tokens"], 1500)
        self.assertIn('"scores"', body["messages"][0]["content"])
        self.assertIn('"nextActions"', body["messages"][0]["content"])
        self.assertNotIn("Sample:\n", content)
        self.assertNotIn("Record:\n", content)
        self.assertEqual(assessment.prompt_metadata["promptSchemaVersion"], "2")
        self.assertEqual(assessment.prompt_metadata["promptBytes"], len(content.encode("utf-8")))
        self.assertTrue(assessment.prompt_metadata["promptHash"].startswith("sha256:"))
        self.assertEqual(assessment.prompt_metadata["model"], "test-model")
        self.assertEqual(assessment.prompt_metadata["baseUrlHost"], "judge.example")
        self.assertNotIn("test-key", json.dumps(assessment.prompt_metadata))

    def test_task_run_judge_xml_rejects_impossibly_small_budget_without_looping(self):
        script = """
from scripts.evaluation import rd_eval_lib as lib

sample = {
    "sample_id": "TASK-XML-TINY",
    "suite": "task-run",
    "input": {"title": "商品管理"},
    "task_run_gold": {"expectedRoles": ["QA_AGENT"]},
}
record = {
    "sample_id": "TASK-XML-TINY",
    "stages": {"QA_AGENT": {"status": "SUCCEEDED", "result": {"summary": "ok"}}},
    "task_run": {"taskStatus": "MERGED"},
}
try:
    lib.build_task_run_judge_xml_prompt(sample, record, [], input_budget_bytes=1)
except ValueError:
    raise SystemExit(0)
raise SystemExit("expected ValueError for impossible budget")
"""
        try:
            result = subprocess.run(
                [sys.executable, "-c", script],
                cwd=Path(__file__).resolve().parents[3],
                capture_output=True,
                text=True,
                timeout=0.75,
            )
        except subprocess.TimeoutExpired as error:
            self.fail(
                "build_task_run_judge_xml_prompt must reject an impossible budget promptly; "
                f"subprocess timed out after {error.timeout}s"
            )

        self.assertEqual(
            result.returncode,
            0,
            f"expected a fast ValueError, stderr={result.stderr!r}, stdout={result.stdout!r}",
        )

    def test_task_run_judge_xml_preserves_xml_validity_when_special_result_is_truncated(self):
        budget = 2_500
        role = "QA_AGENT"
        stages = {
            role: {
                "status": "SUCCEEDED",
                "attemptNo": 1,
                "providerName": "provider",
                "providerAttempts": [{"status": "SUCCESS"}],
                "contextPackageId": "ctx-qa",
                "result": {"contentPreview": "<&>" * 10_000},
            }
        }

        prompt = lib.build_task_run_judge_xml_prompt(
            self.task_run_sample(expected_roles=[role]),
            self.task_run_record(stages),
            self.local_task_metrics(),
            input_budget_bytes=budget,
        )

        self.assertLessEqual(len(prompt.encode("utf-8")), budget)
        self.assertIn('<omitted reason="input_budget"', prompt)
        root = ET.fromstring(prompt)
        self.assertEqual(root.tag, "rd_bot_task_evaluation")

    def test_score_records_passes_deterministic_metrics_to_judge_and_summarizes_narrative(self):
        captured = []

        class CapturingJudge(lib.JudgeProvider):
            name = "capturing"
            enabled = True

            def evaluate_with_context(self, sample, record, local_metrics):
                captured.extend(local_metrics)
                return lib.JudgeAssessment(
                    scores={name: 1.0 for name in lib.JUDGE_METRIC_NAMES},
                    headline="交付结果完整，但检索审计仍需补齐。",
                    strengths=["任务阶段产物齐全"],
                    risks=["检索运行缺失"],
                    next_actions=["补齐 RetrievalRun"],
                )

        result = lib.score_records(
            [
                {
                    "sample_id": "TASK-JUDGE",
                    "suite": "task-run",
                    "task_run_gold": {"expectedRetrievalConsumers": ["REQUIREMENT_BASE"]},
                }
            ],
            [
                {
                    "sample_id": "TASK-JUDGE",
                    "run_id": "run-judge",
                    "task_run": {
                        "taskStatus": "MERGED",
                        "timeline": [{"status": "MERGED"}],
                        "testEvidenceCount": 1,
                        "pullRequestUrl": "https://github.com/example/repo/pull/1",
                    },
                }
            ],
            judge_provider=CapturingJudge(),
        )

        retrieval = next(metric for metric in captured if metric["name"] == "retrieval_run_coverage_rate")
        self.assertEqual(retrieval["value"], 0.0)
        self.assertEqual(retrieval["status"], "FAILED")
        self.assertEqual(retrieval["threshold"], 1.0)
        self.assertEqual(retrieval["label"], "检索运行覆盖率")
        self.assertIn("missing", retrieval["failureReason"])
        self.assertEqual(result["summary"]["judge"]["status"], "AVAILABLE")
        self.assertEqual(result["summary"]["judge"]["strengths"], ["任务阶段产物齐全"])
        self.assertEqual(result["summary"]["judge"]["risks"], ["检索运行缺失"])
        self.assertEqual(result["summary"]["judge"]["nextActions"], ["补齐 RetrievalRun"])

    def test_task_run_without_selected_evidence_cannot_receive_passing_context_judge_scores(self):
        class OptimisticJudge(lib.JudgeProvider):
            name = "optimistic"
            enabled = True

            def evaluate_with_context(self, sample, record, local_metrics):
                return lib.JudgeAssessment(
                    scores={name: 1.0 for name in lib.JUDGE_METRIC_NAMES},
                    headline="looks good",
                )

        result = lib.score_records(
            [self.task_run_sample()],
            [self.task_run_record()],
            judge_provider=OptimisticJudge(),
        )

        metrics = {metric["name"]: metric for metric in result["metrics"]}
        for name in ["faithfulness", "context_precision", "context_recall"]:
            self.assertEqual(metrics[name]["status"], "SKIPPED")
            self.assertIn("missing evaluation context", metrics[name]["reason"])

    def test_requested_judge_failure_marks_gate_incomplete(self):
        class BrokenJudge(lib.JudgeProvider):
            name = "broken"
            enabled = True

            def evaluate_with_context(self, sample, record, local_metrics):
                raise RuntimeError("provider unavailable")

        result = lib.score_records(
            [self.task_run_sample()],
            [self.task_run_record()],
            judge_provider=BrokenJudge(),
        )

        self.assertEqual(result["summary"]["judgeStatus"], "FAILED")
        self.assertEqual(result["summary"]["gateStatus"], "INCOMPLETE")
        self.assertFalse(result["summary"]["overallPassed"])

    def test_redacted_copy_recurses_lists_and_env_needles(self):
        os.environ["RD_BOT_SECRET_SCAN_NEEDLES"] = "SENSITIVEVALUE"
        try:
            payload = {
                "logs": [
                    "Authorization: Bearer abc.def.ghi",
                    "token=SENSITIVEVALUE",
                    {"url": "https://example.com/cb?access_token=abc123456789"},
                ],
                "nested": {"key": "sk-abcdefghijklmnopqrstuvwxyz"},
            }

            redacted = lib.redacted_copy(payload)
            blob = json.dumps(redacted, ensure_ascii=False)

            self.assertNotIn("abc.def.ghi", blob)
            self.assertNotIn("SENSITIVEVALUE", blob)
            self.assertNotIn("abc123456789", blob)
            self.assertNotIn("sk-abcdefghijklmnopqrstuvwxyz", blob)
            self.assertIn("[REDACTED]", blob)
        finally:
            os.environ.pop("RD_BOT_SECRET_SCAN_NEEDLES", None)

    def test_write_report_files_redacts_failure_outputs(self):
        score = {
            "run_id": "run-redact",
            "metrics": [],
            "failures": [
                {
                    "sampleId": "S1",
                    "metric": "secret_leak_rate",
                    "score": 1.0,
                    "threshold": 0.0,
                    "direction": "<=",
                    "reason": "found sk-abcdefghijklmnopqrstuvwxyz",
                    "artifactUri": "artifact://x?token=abc123456789",
                    "evidenceUris": ["https://example.com/cb?access_token=abc123456789"],
                    "nextAction": "remove Bearer abc.def.ghi",
                }
            ],
            "per_sample": [
                {
                    "sample_id": "S1",
                    "suite": "rag",
                    "scenario": "secret",
                    "metrics": {"secret_leak_rate": 1.0},
                }
            ],
        }

        out = self.root / "report"
        lib.write_report_files(out, score)
        blob = "\n".join(path.read_text(encoding="utf-8") for path in out.iterdir() if path.is_file())

        self.assertNotIn("sk-abcdefghijklmnopqrstuvwxyz", blob)
        self.assertNotIn("abc123456789", blob)
        self.assertNotIn("abc.def.ghi", blob)

    def test_run_id_rejects_path_escape_and_write_jsonl_refuses_overwrite(self):
        with self.assertRaises(ValueError):
            lib.run_path(self.root, "../../escape")

        path = self.root / "runs" / "safe.jsonl"
        lib.write_jsonl(path, [{"a": 1}])
        with self.assertRaises(FileExistsError):
            lib.write_jsonl(path, [{"a": 2}], overwrite=False)

    def test_default_paths_resolve_from_repo_root(self):
        dataset = lib.resolve_repo_path("scripts/evaluation/datasets/rd_eval_smoke.jsonl")
        output = lib.output_root("qa-runs/evaluation")

        self.assertTrue(dataset.is_absolute())
        self.assertTrue(dataset.exists())
        self.assertEqual(output, lib.REPO_ROOT / "qa-runs/evaluation")

    def test_qa_expected_statuses_are_scored(self):
        samples = [
            {
                "sample_id": "S-QA",
                "suite": "qa",
                "stage_gold": {
                    "QA_AGENT": {
                        "mustRunRealCommands": True,
                        "expectedAcceptanceStatuses": ["PASSED"],
                        "maxSkippedAcceptanceCount": 0,
                    }
                },
            }
        ]
        records = [
            {
                "sample_id": "S-QA",
                "run_id": "run-1",
                "stages": {
                    "QA_AGENT": {
                        "resultJson": {
                            "acceptanceResults": [
                                {
                                    "id": "AC-1",
                                    "status": "FAILED",
                                    "command": "./mvnw test",
                                    "logArtifactUri": "artifact://qa.log",
                                }
                            ]
                        }
                    }
                },
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "acceptance_status_match_rate", 0.0)
        self.assertFailureMetric(result, "acceptance_status_match_rate")

    def test_diff_respects_metric_direction(self):
        baseline = {
            "run_id": "base",
            "metrics": [
                {"name": "secret_leak_rate", "value": 0.5, "status": "FAILED"},
                {"name": "evidence_hit@5", "value": 0.8, "status": "FAILED"},
            ],
        }
        candidate = {
            "run_id": "candidate",
            "metrics": [
                {"name": "secret_leak_rate", "value": 0.0, "status": "PASS"},
                {"name": "evidence_hit@5", "value": 0.7, "status": "FAILED"},
            ],
        }

        diff = lib.diff_scores(baseline, candidate)

        regression_names = {row["name"] for row in diff["regressions"]}
        self.assertNotIn("secret_leak_rate", regression_names)
        self.assertIn("evidence_hit@5", regression_names)

    def test_pr_policy_violation_is_failed(self):
        samples = [
            {
                "sample_id": "S-CODE",
                "suite": "coding",
                "stage_gold": {"CODING_AGENT": {"expectedChangedFiles": ["src/A.java"]}},
            }
        ]
        records = [
            {
                "sample_id": "S-CODE",
                "run_id": "run-1",
                "stages": {
                    "CODING_AGENT": {
                        "resultJson": {
                            "changedFiles": ["src/A.java"],
                            "pullRequestUrl": "https://github.com/example/repo/pull/1",
                        }
                    }
                },
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "pr_policy_violation_rate", 1.0)
        self.assertFailureMetric(result, "pr_policy_violation_rate")

    def test_judge_exception_is_sample_level_skip(self):
        class BrokenJudge(lib.JudgeProvider):
            name = "broken"
            enabled = True

            def evaluate(self, sample, record):
                raise RuntimeError("provider failed with sk-abcdefghijklmnopqrstuvwxyz")

        result = lib.score_records(
            [{"sample_id": "S1", "suite": "rag"}],
            [{"sample_id": "S1", "run_id": "run-1"}],
            judge_provider=BrokenJudge(),
        )

        self.assertEqual(result["per_sample"][0]["judgeError"], "provider failed with [REDACTED]")
        faithfulness = [m for m in result["metrics"] if m["name"] == "faithfulness"][0]
        self.assertEqual(faithfulness["status"], "SKIPPED")

    def test_report_csv_has_manual_columns_and_applies_manual_overrides(self):
        score = {
            "run_id": "run-manual",
            "metrics": [
                {
                    "name": "faithfulness",
                    "value": 0.2,
                    "sampleCount": 1,
                    "status": "FAILED",
                    "perSample": {"S1": 0.2},
                }
            ],
            "failures": [],
            "per_sample": [
                {
                    "sample_id": "S1",
                    "suite": "rag",
                    "scenario": "manual",
                    "metrics": {"faithfulness": 0.2},
                }
            ],
        }
        report_dir = self.root / "report"
        lib.write_report_files(report_dir, score)
        csv_text = (report_dir / "per_sample.csv").read_text(encoding="utf-8")
        self.assertIn("faithfulness_manual", csv_text.splitlines()[0])

        csv_text = csv_text.replace("0.2,", "0.2,0.9,")
        (report_dir / "per_sample.csv").write_text(csv_text, encoding="utf-8")
        updated = lib.apply_manual_overrides_to_score(score, report_dir / "per_sample.csv")

        self.assertEqual(updated["metrics"][0]["value"], 0.9)

    def test_rag_http_record_contains_rag_eval_core_fields(self):
        sample = {
            "sample_id": "S-RAG",
            "suite": "rag",
            "input": {"groundTruth": "应回答状态推进原因"},
            "rag_gold": {"mustEvidenceUris": ["knowledge://a"]},
        }
        events = [
            {"event": "meta", "data": json.dumps({"taskId": "t1", "traceId": "tr1"})},
            {"event": "delta", "data": "第一段"},
            {"event": "delta", "data": "第二段"},
            {"event": "done", "data": json.dumps({"status": "DONE"})},
        ]
        record = lib.record_from_rag_http_response(
            sample=sample,
            run_id="run-1",
            environment_id="local",
            dataset_path="dataset.jsonl",
            task_id="t1",
            meta={"traceId": "tr1"},
            rag_log={
                "retrievedChunks": [
                    {
                        "evidenceUri": "knowledge://a",
                        "content": "上下文 A",
                    }
                ]
            },
            events=events,
            latency_ms=123,
            first_token_ms=45,
        )

        self.assertEqual(record["response"], "第一段第二段")
        self.assertEqual(record["final_status"], "success")
        self.assertEqual(record["latency_ms"], 123)
        self.assertEqual(record["first_token_ms"], 45)
        self.assertEqual(record["retrieved_contexts"], ["上下文 A"])
        self.assertEqual(record["reference"], "应回答状态推进原因")
        self.assertEqual(record["trace_id"], "tr1")

    def test_rag_metrics_include_rag_eval_style_k_family(self):
        samples = [
            {
                "sample_id": "S-RAG",
                "suite": "rag",
                "rag_gold": {
                    "mustEvidenceUris": ["doc://a", "doc://b"],
                    "niceEvidenceUris": ["doc://c"],
                },
            }
        ]
        records = [
            {
                "sample_id": "S-RAG",
                "run_id": "run-1",
                "rag": {"retrievedEvidenceUris": ["doc://x", "doc://a", "doc://c"]},
                "requires_rag": True,
                "first_token_ms": 120,
                "latency_ms": 300,
            }
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "hit@1", 0.0)
        self.assertMetric(result, "hit@3", 1.0)
        self.assertMetric(result, "recall@5", 0.5)
        self.assertMetric(result, "recall_inclusive@5", 2 / 3)
        self.assertMetric(result, "ttft_mean_ms", 120.0)

    def test_evidence_uris_include_canonical_code_and_knowledge_ids(self):
        chunks = [
            {
                "chunkId": "waimai#server/src/services/PaymentCallbackService.java#handleSuccess",
                "sourceName": "server/src/services/PaymentCallbackService.java",
                "knowledgeBaseId": "waimai",
                "knowledgeType": "code-snippet",
            },
            {
                "chunkId": "waimai-payment-callback.md#0",
                "sourceName": "waimai-payment-callback.md",
                "knowledgeBaseId": "waimai",
                "knowledgeType": "runbook",
            },
        ]

        values = lib.evidence_uris_from_chunks(chunks)

        self.assertIn("code://server/src/services/PaymentCallbackService.java#handleSuccess", values)
        self.assertIn("knowledge://waimai/waimai-payment-callback.md#0", values)

    def test_partial_run_scores_only_recorded_samples_by_default(self):
        samples = [
            {"sample_id": "S-RAG", "suite": "rag"},
            {"sample_id": "S-REQ", "suite": "requirement"},
        ]
        records = [{"sample_id": "S-RAG", "run_id": "run-1"}]

        selected = lib.scored_samples_for_records(samples, records)
        strict = lib.scored_samples_for_records(samples, records, include_missing_records=True)

        self.assertEqual([sample["sample_id"] for sample in selected], ["S-RAG"])
        self.assertEqual([sample["sample_id"] for sample in strict], ["S-RAG", "S-REQ"])

    def test_task_run_metrics_score_complete_delivery_evidence(self):
        sample = {
            "sample_id": "TASK-1",
            "suite": "task-run",
            "task_run_gold": {
                "expectedRoles": [
                    "REQUIREMENT_REVIEWER",
                    "SOLUTION_ARCHITECT",
                    "CODING_AGENT",
                    "QA_AGENT",
                ],
                "expectedRetrievalConsumers": [
                    "REQUIREMENT_BASE",
                    "REQUIREMENT_REVIEWER",
                    "SOLUTION_ARCHITECT",
                    "CODING_AGENT",
                    "QA_AGENT",
                ],
            },
        }
        stages = {
            role: {
                "status": "SUCCEEDED",
                "contextPackageId": f"ctx-{role}",
                "resultArtifactId": f"result-{role}",
                "resultArtifactPresent": True,
                "providerAttempts": [{"status": "SUCCESS"}],
            }
            for role in sample["task_run_gold"]["expectedRoles"]
        }
        record = {
            "run_id": "run-task-1",
            "sample_id": "TASK-1",
            "task_id": "1",
            "stages": stages,
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "CREATED"}, {"status": "MERGED"}],
                "retrievalRuns": [
                    {"consumerKey": consumer, "status": "SUCCEEDED"}
                    for consumer in sample["task_run_gold"]["expectedRetrievalConsumers"]
                ],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/1",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        for metric in [
            "task_terminal_success_rate",
            "role_stage_coverage_rate",
            "stage_success_rate",
            "context_package_coverage_rate",
            "provider_attempt_coverage_rate",
            "result_artifact_coverage_rate",
            "task_timeline_terminal_rate",
            "retrieval_run_coverage_rate",
            "test_evidence_coverage_rate",
            "pull_request_present_rate",
        ]:
            self.assertMetric(result, metric, 1.0)

    def test_task_run_metrics_expose_missing_deep_rag_without_hiding_other_successes(self):
        sample = {
            "sample_id": "TASK-LEGACY",
            "suite": "task-run",
            "task_run_gold": {
                "expectedRoles": ["REQUIREMENT_REVIEWER"],
                "expectedRetrievalConsumers": ["REQUIREMENT_BASE", "REQUIREMENT_REVIEWER"],
            },
        }
        record = {
            "run_id": "run-task-legacy",
            "sample_id": "TASK-LEGACY",
            "stages": {
                "REQUIREMENT_REVIEWER": {
                    "status": "SUCCEEDED",
                    "contextPackageId": "ctx-1",
                    "resultArtifactId": "result-1",
                    "resultArtifactPresent": True,
                    "providerAttempts": [{"status": "SUCCESS"}],
                }
            },
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "CREATED"}, {"status": "MERGED"}],
                "retrievalRuns": [],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/2",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "stage_success_rate", 1.0)
        self.assertMetric(result, "retrieval_run_coverage_rate", 0.0)
        self.assertFailureMetric(result, "retrieval_run_coverage_rate")

    def test_task_run_integrity_and_role_specific_metrics_reject_shared_or_unbound_evidence(self):
        roles = ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
        sample = {
            "sample_id": "TASK-INTEGRITY",
            "suite": "task-run",
            "task_run_gold": {
                "expectedRoles": roles,
                "expectedRetrievalConsumers": ["REQUIREMENT_BASE", *roles],
            },
        }
        shared = {
            "evidenceId": "requirement-root",
            "sourceType": "TASK_MATERIAL",
            "sourceUri": "rd-task://TASK-INTEGRITY/material/1",
            "contentHash": "sha256:root",
            "sharedRoot": True,
        }
        record = {
            "sample_id": "TASK-INTEGRITY",
            "stages": {
                role: {
                    "status": "SUCCEEDED",
                    "stageRunId": f"stage-{role}",
                    "contextPackageId": f"ctx-{role}",
                    "resultArtifactId": f"result-{role}",
                    "resultArtifactPresent": True,
                    "providerAttempts": [{"provider": "p", "status": "SUCCESS", "attempt": 1}],
                }
                for role in roles
            },
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "MERGED"}],
                "contexts": [
                    {
                        "packageId": f"ctx-{role}",
                        "role": role,
                        "retrievalRunId": "",
                        "evidence": [shared],
                    }
                    for role in roles
                ],
                "retrievalRuns": [
                    {
                        "runId": f"run-{consumer}",
                        "consumerKey": consumer,
                        "role": "" if consumer == "REQUIREMENT_BASE" else consumer,
                        "stageRunId": "" if consumer == "REQUIREMENT_BASE" else f"stage-{consumer}",
                        "status": "SUCCEEDED",
                        "selectedEvidenceCount": 1,
                        "selectedEvidenceArtifacts": [shared],
                    }
                    for consumer in ["REQUIREMENT_BASE", *roles]
                ],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/1",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "retrieval_run_integrity_rate", 0.0)
        self.assertMetric(result, "role_specific_evidence_coverage_rate", 0.0)
        self.assertFailureMetric(result, "retrieval_run_integrity_rate")
        self.assertFailureMetric(result, "role_specific_evidence_coverage_rate")

    def test_task_run_integrity_and_role_specific_metrics_accept_bound_scoped_evidence(self):
        roles = ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
        evidence_types = {
            "REQUIREMENT_REVIEWER": "REQUIREMENT_MATERIAL",
            "SOLUTION_ARCHITECT": "ARCHITECTURE",
            "CODING_AGENT": "CODE_SYMBOL",
            "QA_AGENT": "TEST_CASE",
        }
        sample = {
            "sample_id": "TASK-INTEGRITY-PASS",
            "suite": "task-run",
            "input": {
                "repositoryUrl": "https://github.com/example/repo",
                "baseBranch": "main",
                "acceptanceCriteria": ["QA command succeeds"],
            },
            "task_run_gold": {
                "expectedRoles": roles,
                "expectedRetrievalConsumers": ["REQUIREMENT_BASE", *roles],
            },
        }
        stages = {
            role: {
                "status": "SUCCEEDED",
                "stageRunId": f"stage-{role}",
                "contextPackageId": f"ctx-{role}",
                "resultArtifactId": f"result-{role}",
                "resultArtifactPresent": True,
                "providerName": "p",
                "providerAttempts": [
                    {
                        "provider": "p",
                        "status": "SUCCESS",
                        "attempt": 1,
                        "startedAtEpochMillis": 10,
                        "finishedAtEpochMillis": 20,
                    }
                ],
            }
            for role in roles
        }
        stages["QA_AGENT"]["resultJson"] = {
            "acceptanceResults": [
                {
                    "id": "AC-1",
                    "status": "PASSED",
                    "command": "./mvnw test",
                    "exitCode": 0,
                    "logArtifactUri": "artifact://qa-log",
                    "logArtifactHash": "sha256:qa-log",
                }
            ]
        }
        role_evidence = {
            role: {
                "evidenceId": f"evidence-{role}",
                "sourceType": "KNOWLEDGE",
                "sourceUri": f"knowledge://waimai-kb/{role}",
                "contentHash": f"sha256:{role}",
                "requiredEvidenceType": evidence_types[role],
                "sharedRoot": False,
                "relevanceScore": 0.9,
                "selectionReason": "role-specific evidence",
            }
            for role in roles
        }
        shared_roots = [
            {
                "evidenceId": "requirement-root",
                "sourceType": "TASK_MATERIAL",
                "sourceUri": "task-material://TASK-INTEGRITY-PASS/requirement",
                "contentHash": "sha256:requirement-root",
                "requiredEvidenceType": "REQUIREMENT_ROOT",
                "sharedRoot": True,
                "relevanceScore": 1.0,
            },
            {
                "evidenceId": "repository-root",
                "sourceType": "REPOSITORY",
                "sourceUri": "repo://https://github.com/example/repo",
                "contentHash": "sha256:repository-root",
                "requiredEvidenceType": "REPOSITORY_SCOPE",
                "sharedRoot": True,
                "relevanceScore": 1.0,
            },
            {
                "evidenceId": "acceptance-root",
                "sourceType": "ACCEPTANCE_CRITERIA",
                "sourceUri": "task://TASK-INTEGRITY-PASS/acceptance",
                "contentHash": "sha256:acceptance-root",
                "requiredEvidenceType": "ACCEPTANCE_CRITERIA",
                "sharedRoot": True,
                "relevanceScore": 1.0,
            },
        ]
        base_evidence = {
            "artifactId": "base-evidence",
            "sourceUri": "knowledge://waimai-kb/requirement-root",
            "contentHash": "sha256:base",
            "requiredEvidenceType": "REQUIREMENT_MATERIAL",
            "relevanceScore": 1.0,
        }
        retrieval_runs = [
            {
                "runId": "run-REQUIREMENT_BASE",
                "consumerKey": "REQUIREMENT_BASE",
                "status": "SUCCEEDED",
                "attemptNo": 1,
                "knowledgeBaseIds": ["waimai-kb"],
                "candidateCount": 2,
                "selectedEvidenceCount": 1,
                "selectedEvidenceArtifacts": [base_evidence],
                "qualityReportHash": "sha256:quality-base",
                "scopeViolationCount": 0,
            }
        ]
        retrieval_runs.extend(
            {
                "runId": f"run-{role}",
                "consumerKey": role,
                "role": role,
                "stageRunId": f"stage-{role}",
                "status": "SUCCEEDED",
                "attemptNo": 1,
                "knowledgeBaseIds": ["waimai-kb"],
                "candidateCount": 5,
                "selectedEvidenceCount": 4,
                "selectedEvidenceArtifacts": [*shared_roots, role_evidence[role]],
                "qualityReportHash": f"sha256:quality-{role}",
                "scopeViolationCount": 0,
            }
            for role in roles
        )
        record = {
            "sample_id": "TASK-INTEGRITY-PASS",
            "stages": stages,
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "MERGED"}],
                "contexts": [
                    {
                        "packageId": f"ctx-{role}",
                        "role": role,
                        "retrievalRunId": f"run-{role}",
                        "evidence": [*shared_roots, role_evidence[role]],
                    }
                    for role in roles
                ],
                "retrievalRuns": retrieval_runs,
                "artifacts": [
                    {
                        "artifactId": "qa-log",
                        "artifactUri": "artifact://qa-log",
                        "contentHash": "sha256:qa-log",
                    }
                ],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/1",
                "baseBranch": "main",
                "workBranch": "rd/task-integrity-pass",
                "commitSha": "0123456789abcdef0123456789abcdef01234567",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "retrieval_run_integrity_rate", 1.0)
        self.assertMetric(result, "role_specific_evidence_coverage_rate", 1.0)
        self.assertMetric(result, "citation_integrity_rate", 1.0)
        self.assertMetric(result, "scope_leak_rate", 0.0)
        self.assertMetric(result, "critical_evidence_coverage_rate", 1.0)
        self.assertMetric(result, "context_noise_rate", 0.0)
        self.assertMetric(result, "context_duplicate_rate", 0.0)
        self.assertMetric(result, "provider_attempt_integrity_rate", 1.0)
        self.assertMetric(result, "test_artifact_integrity_rate", 1.0)
        self.assertMetric(result, "pr_integrity_rate", 1.0)

    def test_task_run_integrity_metrics_reject_scope_duplicate_attempt_artifact_and_pr_mismatch(self):
        roles = ["CODING_AGENT", "QA_AGENT"]
        duplicated = {
            "evidenceId": "duplicate",
            "sourceType": "KNOWLEDGE",
            "sourceUri": "knowledge://foreign-kb/shared",
            "contentHash": "sha256:duplicate",
            "requiredEvidenceType": "SOURCE_CODE",
            "sharedRoot": False,
            "relevanceScore": 0.0,
        }
        sample = {
            "sample_id": "TASK-INTEGRITY-FAIL",
            "suite": "task-run",
            "input": {
                "repositoryUrl": "https://github.com/example/repo",
                "baseBranch": "main",
                "acceptanceCriteria": ["QA command succeeds"],
            },
            "task_run_gold": {
                "expectedRoles": roles,
                "expectedRetrievalConsumers": roles,
                "forbiddenEvidenceUris": ["knowledge://foreign-kb/shared"],
            },
        }
        stages = {
            role: {
                "status": "SUCCEEDED",
                "stageRunId": f"stage-{role}",
                "contextPackageId": f"ctx-{role}",
                "resultArtifactId": f"result-{role}",
                "resultArtifactPresent": True,
                "providerName": "winner",
                "providerAttempts": [{"provider": "other", "status": "SUCCESS", "attempt": 1}],
            }
            for role in roles
        }
        stages["QA_AGENT"]["resultJson"] = {
            "acceptanceResults": [
                {
                    "id": "AC-1",
                    "status": "PASSED",
                    "command": "./mvnw test",
                    "exitCode": 0,
                    "logArtifactUri": "artifact://missing",
                    "logArtifactHash": "sha256:missing",
                }
            ]
        }
        retrieval_runs = [
            {
                "runId": f"run-{role}",
                "consumerKey": role,
                "role": role,
                "stageRunId": f"stage-{role}",
                "status": "SUCCEEDED",
                "attemptNo": 1,
                "knowledgeBaseIds": ["waimai-kb"],
                "candidateCount": 1,
                "selectedEvidenceCount": 1,
                "selectedEvidenceArtifacts": [duplicated],
                "qualityReportHash": f"sha256:quality-{role}",
                "scopeViolationCount": 0,
            }
            for role in roles
        ]
        record = {
            "sample_id": "TASK-INTEGRITY-FAIL",
            "stages": stages,
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "MERGED"}],
                "contexts": [
                    {
                        "packageId": f"ctx-{role}",
                        "role": role,
                        "retrievalRunId": f"run-{role}",
                        "evidence": [duplicated],
                    }
                    for role in roles
                ],
                "retrievalRuns": retrieval_runs,
                "artifacts": [],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/other/repository/pull/99",
                "baseBranch": "",
                "workBranch": "",
                "commitSha": "",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "citation_integrity_rate", 1.0)
        self.assertMetric(result, "scope_leak_rate", 1.0)
        self.assertMetric(result, "context_noise_rate", 1.0)
        self.assertMetric(result, "context_duplicate_rate", 0.5)
        self.assertMetric(result, "provider_attempt_integrity_rate", 0.0)
        self.assertMetric(result, "test_artifact_integrity_rate", 0.0)
        self.assertMetric(result, "pr_integrity_rate", 0.0)

    def test_task_run_result_artifact_requires_persisted_artifact_presence(self):
        sample = {
            "sample_id": "TASK-DANGLING",
            "suite": "task-run",
            "task_run_gold": {"expectedRoles": ["QA_AGENT"], "expectedRetrievalConsumers": []},
        }
        record = {
            "sample_id": "TASK-DANGLING",
            "stages": {
                "QA_AGENT": {
                    "status": "SUCCEEDED",
                    "contextPackageId": "ctx-qa",
                    "resultArtifactId": "missing-artifact",
                    "resultArtifactPresent": False,
                    "providerAttempts": [{"status": "SUCCESS"}],
                }
            },
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "MERGED"}],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/1",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "result_artifact_coverage_rate", 0.0)
        self.assertFailureMetric(result, "result_artifact_coverage_rate")

    def test_expected_non_delivery_terminal_does_not_require_pr_or_test_artifacts(self):
        sample = {
            "sample_id": "TASK-WAITING-EXPECTED",
            "suite": "task-run",
            "task_run_gold": {
                "expectedTaskStatuses": ["FAILED_NEEDS_HUMAN"],
                "expectedRoles": [],
                "expectedRetrievalConsumers": [],
            },
        }
        record = {
            "sample_id": "TASK-WAITING-EXPECTED",
            "task_run": {
                "taskStatus": "FAILED_NEEDS_HUMAN",
                "timeline": [{"status": "FAILED_NEEDS_HUMAN"}],
                "testEvidenceCount": 0,
                "pullRequestUrl": "",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "task_terminal_success_rate", 1.0)
        self.assertFalse(any(
            metric["name"] in {"test_evidence_coverage_rate", "test_artifact_integrity_rate", "pull_request_present_rate", "pr_integrity_rate"}
            for metric in result["metrics"]
        ))

    def test_qa_real_command_metric_rejects_unresolved_artifact_uri(self):
        sample = {
            "sample_id": "QA-DANGLING-LOG",
            "suite": "qa",
            "stage_gold": {"QA_AGENT": {"mustRunRealCommands": True}},
        }
        record = {
            "sample_id": "QA-DANGLING-LOG",
            "stages": {
                "QA_AGENT": {
                    "status": "SUCCEEDED",
                    "resultJson": {
                        "acceptanceResults": [
                            {
                                "id": "AC-1",
                                "status": "PASSED",
                                "command": "./mvnw test",
                                "logArtifactUri": "artifact://not-found",
                                "logArtifactHash": "sha256:missing",
                            }
                        ]
                    },
                }
            },
            "artifactManifest": [],
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "real_command_execution_rate", 0.0)
        self.assertFailureMetric(result, "real_command_execution_rate")

    def test_secret_metric_scans_dataset_and_url_userinfo(self):
        sample = {
            "sample_id": "TASK-SECRET",
            "suite": "task-run",
            "input": {"repositoryUrl": "https://user:super-secret-token@example.com/repo.git"},
        }
        record = {"sample_id": "TASK-SECRET", "run_id": "run-secret"}

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "secret_leak_rate", 1.0)
        self.assertFailureMetric(result, "secret_leak_rate")

    def test_secret_metric_does_not_treat_task_run_identifiers_as_api_keys(self):
        sample = {
            "sample_id": "TASK-RUN-SAFE",
            "suite": "task-run",
            "scenario": "task-run-success-waimai-project",
        }
        record = {
            "sample_id": "TASK-RUN-SAFE",
            "run_id": "task-run-20260714-quality-benchmark",
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "secret_leak_rate", 0.0)

    def test_score_provenance_hashes_dataset_records_scorer_and_thresholds(self):
        dataset = self.touch("quality-v1.jsonl", '{"sample_id":"S1","suite":"rag"}\n')
        records = self.touch("runs/run-1.jsonl", '{"run_id":"run-1","sample_id":"S1"}\n')

        provenance = lib.score_provenance(dataset, records)

        self.assertEqual("rd-eval-score-v2", provenance["scorerSchemaVersion"])
        self.assertEqual("quality-v1.jsonl", provenance["datasetId"])
        self.assertTrue(provenance["datasetSha256"].startswith("sha256:"))
        self.assertTrue(provenance["recordSnapshotSha256"].startswith("sha256:"))
        self.assertTrue(provenance["scorerSourceSha256"].startswith("sha256:"))
        self.assertTrue(provenance["thresholdConfigSha256"].startswith("sha256:"))
        self.assertNotIn("API_KEY", json.dumps(provenance))

    def test_metric_aggregation_macro_averages_repeated_scenario_or_task(self):
        samples = [
            {
                "sample_id": "REQ-A1",
                "suite": "requirement",
                "scenario": "same-scenario",
                "stage_gold": {"REQUIREMENT_REVIEWER": {"expectedDecision": "APPROVED"}},
            },
            {
                "sample_id": "REQ-A2",
                "suite": "requirement",
                "scenario": "same-scenario",
                "stage_gold": {"REQUIREMENT_REVIEWER": {"expectedDecision": "APPROVED"}},
            },
            {
                "sample_id": "REQ-B",
                "suite": "requirement",
                "scenario": "other-scenario",
                "stage_gold": {"REQUIREMENT_REVIEWER": {"expectedDecision": "APPROVED"}},
            },
        ]
        records = [
            {"sample_id": "REQ-A1", "stages": {"REQUIREMENT_REVIEWER": {"resultJson": {"decision": "APPROVED"}}}},
            {"sample_id": "REQ-A2", "stages": {"REQUIREMENT_REVIEWER": {"resultJson": {"decision": "NEED_INFO"}}}},
            {"sample_id": "REQ-B", "stages": {"REQUIREMENT_REVIEWER": {"resultJson": {"decision": "APPROVED"}}}},
        ]

        result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())
        metric = next(item for item in result["metrics"] if item["name"] == "requirement_decision_accuracy")

        self.assertEqual(2, metric["sampleCount"])
        self.assertEqual(0.75, metric["value"])

    def test_task_run_metrics_fail_when_timeline_does_not_reach_current_status(self):
        sample = {
            "sample_id": "TASK-TIMELINE",
            "suite": "task-run",
            "task_run_gold": {"expectedRoles": [], "expectedRetrievalConsumers": []},
        }
        record = {
            "run_id": "run-task-timeline",
            "sample_id": "TASK-TIMELINE",
            "stages": {},
            "task_run": {
                "taskStatus": "MERGED",
                "timeline": [{"status": "CREATED"}, {"status": "COMPLETED"}],
                "testEvidenceCount": 1,
                "pullRequestUrl": "https://github.com/example/repo/pull/3",
            },
        }

        result = lib.score_records([sample], [record], judge_provider=lib.NoopJudgeProvider())

        self.assertMetric(result, "task_timeline_terminal_rate", 0.0)
        self.assertFailureMetric(result, "task_timeline_terminal_rate")

    def assertMetric(self, result, name, expected):
        matches = [metric for metric in result["metrics"] if metric["name"] == name]
        self.assertTrue(matches, f"metric {name} was not produced")
        self.assertAlmostEqual(matches[0]["value"], expected, places=6)

    def assertFailureMetric(self, result, name):
        metrics = {failure["metric"] for failure in result["failures"]}
        self.assertIn(name, metrics)


if __name__ == "__main__":
    unittest.main()
