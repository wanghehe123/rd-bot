# RD Eval Python3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure Python3 evaluation toolchain for RD-Bot RAG, multi-agent stages, alerts, and report generation, with sample evaluation data and a self-service runbook.

**Architecture:** Keep the first version outside the Java runtime: Python scripts read JSONL datasets, record RD-Bot facts from fixtures or live HTTP endpoints, compute deterministic metrics, optionally call an OpenAI-compatible judge provider, and render reviewable Markdown/CSV/JSONL reports. Runtime output stays under `qa-runs/evaluation/`; versioned sample data and code stay under `scripts/evaluation/`.

**Tech Stack:** Python 3 standard library for the baseline toolchain (`argparse`, `csv`, `dataclasses`, `json`, `pathlib`, `statistics`, `time`, `urllib`), `unittest` for tests, JSONL for datasets/runs/failures, Markdown and CSV for human review. RAGAS-compatible judge is an optional dependency path using `ragas`, `langchain-openai`, and `datasets`, matching `/Users/wish233/PycharmProjects/ragenteval`.

---

## Repo Evidence Used

- `RULE.md` confirms RD-Bot is Java/Spring modular, so evaluation tooling should avoid changing Java production boundaries in this first slice.
- `docs/superpowers/plans/2026-07-06-rd-bot-rag-agent-evaluation-design.md` defines `EvalSample -> EvalRecord -> MetricResult -> report`.
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/rag/RagBugFixController.java` exposes `/rag/v3/chat` and `/rag/v3/tasks/{taskId}` for live RAG recording.
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/rag/RagTraceController.java` exposes trace reads for later extension.
- `engine/src/main/java/com/wish/rd/engine/rag/model/RagRetrievalLogEvent.java` defines the RAG retrieval JSONL facts used for evidence scoring.
- `bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql` defines stage run, context package, artifact, event, and experience tables used by multi-agent evaluation.
- `exec/src/main/java/com/wish/rd/exec/repair/alert/model/RepairAlertType.java` defines current alert categories; Python evaluation can score alerts without adding Java enum values in slice 1.

## Files

- Create: `scripts/__init__.py` so repo-local Python imports work.
- Create: `scripts/evaluation/__init__.py` for the evaluation package.
- Create: `scripts/evaluation/rd_eval_lib.py` with dataset/run loading, fixture recording, HTTP/SSE parsing, deterministic scoring, AI judge provider abstraction, report rendering helpers, and secret redaction.
- Create: `scripts/evaluation/rd_eval_run.py` CLI to produce `qa-runs/evaluation/runs/<run_id>.jsonl`.
- Create: `scripts/evaluation/rd_eval_score.py` CLI to produce `qa-runs/evaluation/reports/<run_id>/_scores.json`.
- Create: `scripts/evaluation/rd_eval_report.py` CLI to produce `report.md`, `per_sample.csv`, and `failures.jsonl`.
- Create: `scripts/evaluation/rd_eval_diff.py` CLI to compare baseline and candidate score files.
- Create: `scripts/evaluation/datasets/rd_eval_smoke.jsonl` with concrete RAG, requirement, solution, coding, QA, alert, and delivery examples.
- Create: `scripts/evaluation/tests/test_rd_eval_lib.py` with pure `unittest` coverage.
- Create: `docs/superpowers/plans/2026-07-06-rd-eval-python3-runbook.md` with self-service commands, data explanation, live RD-Bot mode, fixture mode, and AI provider configuration.

## Task 1: Test Dataset And Fixture Recording

**Files:**
- Create: `scripts/evaluation/tests/test_rd_eval_lib.py`
- Create: `scripts/evaluation/rd_eval_lib.py`
- Create: `scripts/evaluation/datasets/rd_eval_smoke.jsonl`

- [ ] **Step 1: Write failing tests**

```python
def test_load_jsonl_rejects_missing_sample_id(self):
    path = self.write_jsonl([{"suite": "rag"}])
    with self.assertRaises(ValueError):
        lib.load_dataset(path)

def test_fixture_runner_copies_fixture_record_and_adds_run_metadata(self):
    sample = {"sample_id": "S1", "suite": "rag", "fixture_record": {"status": "COMPLETED"}}
    records = lib.records_from_fixtures([sample], "run-1", "local", "dataset.jsonl")
    self.assertEqual(records[0]["sample_id"], "S1")
    self.assertEqual(records[0]["run_id"], "run-1")
    self.assertEqual(records[0]["environment_id"], "local")
```

- [ ] **Step 2: Run the test and confirm RED**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: import or attribute failure because `scripts.evaluation.rd_eval_lib` is not implemented yet.

- [ ] **Step 3: Implement minimal loader and fixture recorder**

Use `json.loads` line by line, require `sample_id`, normalize missing fixture fields, and avoid logging secrets.

- [ ] **Step 4: Run the test and confirm GREEN**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: the two tests pass.

## Task 2: Deterministic Metrics

**Files:**
- Modify: `scripts/evaluation/tests/test_rd_eval_lib.py`
- Modify: `scripts/evaluation/rd_eval_lib.py`

- [ ] **Step 1: Write failing tests**

```python
def test_score_rag_evidence_and_requirement_blocking(self):
    samples = [self.sample_with_rag_and_requirement_gold()]
    records = [self.record_with_matching_evidence_and_blocked_stage()]
    result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())
    self.assertMetric(result, "evidence_hit@5", 1.0)
    self.assertMetric(result, "requirement_decision_accuracy", 1.0)
    self.assertMetric(result, "downstream_leak_rate", 0.0)

def test_forbidden_file_and_skipped_qa_create_failures(self):
    samples = [self.sample_with_coding_and_qa_gold()]
    records = [self.record_with_forbidden_file_and_skipped_qa()]
    result = lib.score_records(samples, records, judge_provider=lib.NoopJudgeProvider())
    self.assertMetric(result, "forbidden_file_touch_rate", 1.0)
    self.assertMetric(result, "skipped_acceptance_rate", 1.0)
    self.assertTrue(result["failures"])
```

- [ ] **Step 2: Run the tests and confirm RED**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: missing `score_records` or metric failures.

- [ ] **Step 3: Implement deterministic scoring**

Implement metrics from the design spec: intent top1, evidence hit/recall/MRR, requirement decision accuracy, downstream leak rate, risk coverage, solution schema validity, acceptance mapping coverage, changed file precision, forbidden file touch rate, real command execution rate, skipped acceptance rate, alert expectation checks, and delivery experience completeness.

- [ ] **Step 4: Run tests and confirm GREEN**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: deterministic metric tests pass.

## Task 3: CLI Scripts

**Files:**
- Create: `scripts/evaluation/rd_eval_run.py`
- Create: `scripts/evaluation/rd_eval_score.py`
- Create: `scripts/evaluation/rd_eval_report.py`
- Create: `scripts/evaluation/rd_eval_diff.py`
- Modify: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [ ] **Step 1: Write failing tests for CLI-level helpers**

```python
def test_latest_file_picks_newest_jsonl(self):
    older = self.touch("runs/old.jsonl", 1)
    newer = self.touch("runs/new.jsonl", 2)
    self.assertEqual(lib.latest_file(older.parent, ".jsonl"), newer)

def test_render_report_includes_failed_metric_and_next_action(self):
    score = self.score_with_one_failure()
    rendered = lib.render_markdown_report(score)
    self.assertIn("evidence_hit@5", rendered)
    self.assertIn("nextAction", rendered)
```

- [ ] **Step 2: Run tests and confirm RED**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: missing helpers.

- [ ] **Step 3: Implement CLI scripts**

Expose commands:

```bash
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source fixture
python3 scripts/evaluation/rd_eval_score.py --latest --skip-judge
python3 scripts/evaluation/rd_eval_report.py --latest
python3 scripts/evaluation/rd_eval_diff.py --baseline qa-runs/evaluation/reports/<old>/_scores.json --candidate qa-runs/evaluation/reports/<new>/_scores.json
```

- [ ] **Step 4: Run CLI smoke**

```bash
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source fixture --run-id rd-eval-smoke-local
python3 scripts/evaluation/rd_eval_score.py --run-id rd-eval-smoke-local --skip-judge
python3 scripts/evaluation/rd_eval_report.py --run-id rd-eval-smoke-local
```

Expected: `qa-runs/evaluation/reports/rd-eval-smoke-local/report.md`, `per_sample.csv`, `failures.jsonl`, and `_scores.json` exist.

## Task 4: AI Judge Provider Reservation

**Files:**
- Modify: `scripts/evaluation/rd_eval_lib.py`
- Modify: `scripts/evaluation/rd_eval_score.py`
- Modify: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [ ] **Step 1: Write failing tests**

```python
def test_noop_judge_marks_metrics_skipped(self):
    result = lib.score_records([self.sample_with_rag_gold()], [self.record_with_matching_evidence()], judge_provider=lib.NoopJudgeProvider())
    judge_metrics = [m for m in result["metrics"] if m["name"] == "faithfulness"]
    self.assertEqual(judge_metrics[0]["status"], "SKIPPED")

def test_openai_compatible_provider_requires_env_without_exposing_value(self):
    with self.assertRaises(ValueError) as error:
        lib.OpenAICompatibleJudgeProvider.from_env({})
    self.assertIn("RD_EVAL_JUDGE_API_KEY", str(error.exception))
```

- [ ] **Step 2: Run tests and confirm RED**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: missing provider behavior.

- [ ] **Step 3: Implement provider abstraction**

Support `--judge-provider none` and `--judge-provider openai-compatible`. Read only environment variables `RD_EVAL_JUDGE_BASE_URL`, `RD_EVAL_JUDGE_API_KEY`, `RD_EVAL_JUDGE_MODEL`, and never print the key.

- [ ] **Step 4: Run tests and confirm GREEN**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
```

Expected: provider tests pass.

## Task 5: Runbook And Verification

**Files:**
- Create: `docs/superpowers/plans/2026-07-06-rd-eval-python3-runbook.md`

- [ ] **Step 1: Document fixture mode**

Include the exact commands for running without RD-Bot backend:

```bash
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source fixture --run-id rd-eval-smoke-local
python3 scripts/evaluation/rd_eval_score.py --run-id rd-eval-smoke-local --skip-judge
python3 scripts/evaluation/rd_eval_report.py --run-id rd-eval-smoke-local
```

- [ ] **Step 2: Document live RAG mode**

Include the backend prerequisite and command:

```bash
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source rag-http --base-url http://127.0.0.1:18080 --rag-log logs/rag-retrieval.jsonl
```

- [ ] **Step 3: Document AI provider**

Include variable names only:

```bash
export RD_EVAL_JUDGE_BASE_URL="https://example.com/v1/chat/completions"
export RD_EVAL_JUDGE_MODEL="judge-model-name"
export RD_EVAL_JUDGE_API_KEY
python3 scripts/evaluation/rd_eval_score.py --latest --judge-provider openai-compatible --judge-limit 5
```

- [ ] **Step 4: Run final verification**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib -v
python3 scripts/evaluation/rd_eval_run.py --dataset scripts/evaluation/datasets/rd_eval_smoke.jsonl --source fixture --run-id rd-eval-smoke-local
python3 scripts/evaluation/rd_eval_score.py --run-id rd-eval-smoke-local --skip-judge
python3 scripts/evaluation/rd_eval_report.py --run-id rd-eval-smoke-local
git diff --check -- scripts/evaluation docs/superpowers/plans/2026-07-06-rd-eval-python3-runbook.md
```

Expected: all commands complete successfully; the report directory contains `_scores.json`, `report.md`, `per_sample.csv`, and `failures.jsonl`.
