import json
import os
import tempfile
import time
import unittest
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

    def test_ragas_thresholds_match_ragenteval_reference(self):
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

    def test_openai_compatible_provider_requires_env_without_exposing_value(self):
        with self.assertRaises(ValueError) as error:
            lib.OpenAICompatibleJudgeProvider.from_env({})

        message = str(error.exception)
        self.assertIn("RD_EVAL_JUDGE_API_KEY", message)
        self.assertNotIn("sk-", message)

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

    def test_rag_http_record_contains_ragenteval_core_fields(self):
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

    def test_rag_metrics_include_ragenteval_style_k_family(self):
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

    def assertMetric(self, result, name, expected):
        matches = [metric for metric in result["metrics"] if metric["name"] == name]
        self.assertTrue(matches, f"metric {name} was not produced")
        self.assertAlmostEqual(matches[0]["value"], expected, places=6)

    def assertFailureMetric(self, result, name):
        metrics = {failure["metric"] for failure in result["failures"]}
        self.assertIn(name, metrics)


if __name__ == "__main__":
    unittest.main()
