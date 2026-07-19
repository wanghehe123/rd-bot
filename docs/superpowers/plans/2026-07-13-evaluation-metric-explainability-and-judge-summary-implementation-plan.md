# Evaluation Metric Explainability And Judge Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline task-by-task execution with TDD. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make evaluation metrics explainable in the console, include deterministic metric evidence in OpenAI-compatible Judge requests, and emit a concise human-readable evaluation summary.

**Architecture:** `rd_eval_lib.py` remains the single source of truth: it enriches aggregate metrics, builds a bounded deterministic summary, and accepts a structured Judge assessment. The bootstrap executor persists a backward-compatible metrics envelope, and the React console reads that envelope to render tooltips and the summary band.

**Tech Stack:** Python 3 standard library, Spring Boot/Jackson, React/TypeScript, existing local evaluation scripts and admin components.

---

### Task 1: Define Metric Metadata And Summary Contract

**Files:**
- Modify: `scripts/evaluation/rd_eval_lib.py`
- Test: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [x] **Step 1: Write failing scorer tests**

Add tests asserting `score_records()` emits metadata (`label`, `purpose`, `calculation`) for `retrieval_run_coverage_rate`, returns a bounded `summary` with failed-metric next action, and leaves unknown metric metadata safe.

- [x] **Step 2: Verify the tests fail**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib.RdEvalLibTest.test_score_records_emits_metric_metadata_and_human_summary`

Expected: FAIL because metric metadata and `summary` do not exist.

- [x] **Step 3: Implement the metric catalog and deterministic summary**

Create a pure `metric_definition(name)` lookup and use it from `aggregate_metrics()`. Add `build_evaluation_summary(metrics, failures, judge_assessment)` that returns only redacted/bounded text, real counts, failed metric label/reason/next action, and deterministic overall status.

- [x] **Step 4: Verify green**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib`

Expected: all scorer tests pass.

### Task 2: Pass Deterministic Metrics To OpenAI Judge

**Files:**
- Modify: `scripts/evaluation/rd_eval_lib.py`
- Test: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [x] **Step 1: Write failing Judge request/response tests**

Mock `urllib.request.urlopen` and assert the request body has redacted `local_metrics`, including local values/status/threshold/meaning, but does not include a test secret. Assert a valid Judge JSON with five scores plus concise narrative is parsed, while malformed narrative safely degrades.

- [x] **Step 2: Verify the tests fail**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib.RdEvalLibTest.test_openai_judge_receives_local_metrics_and_returns_bounded_narrative`

Expected: FAIL because `JudgeProvider.evaluate` has no deterministic metric context or narrative contract.

- [x] **Step 3: Implement structured Judge assessment**

Introduce a `JudgeRequest`/`JudgeAssessment` boundary. Preserve existing RAGAS and NONE behavior, but make OpenAI-compatible prompts request strict JSON with `scores`, `headline`, `strengths`, `risks`, and `nextActions`. Validate score ranges, redact and bound every narrative field, and never persist reasoning text.

- [x] **Step 4: Verify green**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib`

Expected: all scorer tests pass and the outgoing request contains local metrics without a credential leak.

### Task 3: Render The Durable Markdown Summary

**Files:**
- Modify: `scripts/evaluation/rd_eval_lib.py`
- Test: `scripts/evaluation/tests/test_rd_eval_lib.py`

- [x] **Step 1: Write failing report test**

Add an assertion that `render_markdown_report()` starts with `## 简要评测报告`, shows a readable headline, a failed metric/action, and an explicit Judge status.

- [x] **Step 2: Verify the test fails**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib.RdEvalLibTest.test_markdown_report_renders_human_readable_summary`

Expected: FAIL because current Markdown begins directly with the metadata and metric table.

- [x] **Step 3: Implement report section**

Render summary before the metric table. Use the same score payload that is saved to `_scores.json`; do not generate a second model call or separate assessment.

- [x] **Step 4: Verify green**

Run: `python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib`

Expected: all scorer tests pass.

### Task 4: Persist A Backward-Compatible Metrics Envelope

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/LocalPythonEvaluationExecutor.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation/LocalPythonEvaluationExecutorTest.java`

- [x] **Step 1: Write failing executor test**

Create a score file with `metrics` and `summary`; assert the execution result stores a JSON object containing both. Also assert a legacy score file without `summary` still exposes its metrics.

- [x] **Step 2: Verify the test fails**

Run: `./mvnw -q -pl bootstrap -am -Dtest=LocalPythonEvaluationExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `parseResult()` serializes only the metrics array.

- [x] **Step 3: Implement envelope persistence**

Change `parseResult()` to serialize `{ "metrics": ..., "summary": ... }` into `metricsJson`, preserving the existing metrics list and defaulting an absent summary to an empty object. Do not alter table schema.

- [x] **Step 4: Verify green**

Run: `./mvnw -q -pl bootstrap -am -Dtest=LocalPythonEvaluationExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

### Task 5: Render Tooltips And Summary In The Console

**Files:**
- Modify: `frontend/src/services/evaluationService.ts`
- Modify: `frontend/src/pages/admin/evaluation/EvaluationPage.tsx`
- Modify: `frontend/src/styles.css`
- Test: `frontend/test/evaluationConsole.test.ts`

- [x] **Step 1: Write failing frontend source tests**

Assert the evaluation page supports both legacy arrays and the envelope, includes an accessible metric help button/tooltip, and renders `简要评测报告` from the persisted summary.

- [x] **Step 2: Verify the tests fail**

Run: `node --test frontend/test/evaluationConsole.test.ts`

Expected: FAIL because there is no tooltip or summary projection.

- [x] **Step 3: Implement UI projection**

Extend TypeScript models with metric metadata and summary types. Add a safe parser accepting either historical arrays or the new envelope. Render a fixed-size metric-card `?` button with native accessible tooltip content; add an unframed summary band containing conclusion, Judge availability/narrative, failed metrics, and next actions. Add responsive CSS that wraps text without changing card dimensions or causing viewport overflow.

- [x] **Step 4: Verify green**

Run: `node --test frontend/test/evaluationConsole.test.ts`

Expected: PASS.

### Task 6: Build And Real Acceptance

**Files:**
- Modify: `docs/qa/2026-07-13-task-run-evaluation-acceptance-report.md`

- [x] **Step 1: Run targeted regression commands**

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib
./mvnw -q -pl bootstrap -am -Dtest=EvaluationControllerTest,LocalPythonEvaluationExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix frontend run typecheck
node --test frontend/test/evaluationConsole.test.ts
npm --prefix frontend run build
```

- [ ] **Step 2: Run a real one-sample OpenAI-compatible task evaluation (BLOCKED by execution environment)**

Use `POST /admin/rd-tasks/7480495920010891264/evaluations` with `judgeProvider=OPENAI_COMPATIBLE` and `judgeLimit=1`. Confirm the new run has an envelope summary, every metric has metadata, the OpenAI Judge receives local metric context, and `REPORT` starts with the concise summary.

- [ ] **Step 3: Verify console and security (browser portion BLOCKED; security completed)**

Inspect the current management console at desktop and narrow viewport. Confirm metric `?` controls are accessible, tooltip text does not overflow, and the summary matches report data. Scan new score/report/log artifacts for secret patterns. Close any backend started solely for this test after evidence capture; do not stop the pre-existing user-facing `5173` service.

- [x] **Step 4: Record outcome**

Append commands, run ID, HTTP status, selected metric evidence, browser observations, secret-scan result, and any skipped external dependency to the QA acceptance report.

## Execution Record (2026-07-13)

- Automated verifier suite and production frontend build passed; exact commands and results are in the QA report.
- The local `TASK_RUN` acceptance Run `7482309650394779648` completed with the new `{ metrics, summary }` persistence envelope and report summary. It intentionally used `judgeProvider=NONE` so no task data was exported.
- A user-authorized live OpenAI-compatible Judge attempt was blocked before network transmission by the execution environment's external-data policy. The mock HTTP scorer test verifies the outgoing structured context and the narrative response parser; a real Judge recheck must run in an environment that permits that outbound request.
- Browser automation was blocked by an unrelated global website safety policy after the existing management page was located. Type checking, UI source tests and production build passed; browser visual acceptance remains explicitly open.
- Follow-up incident `7482316500813090816`: the existing `18080` backend accepted `OPENAI_COMPATIBLE` without its three required process variables, so scoring failed before a Judge request. Per operator direction, the resolution is repository-owned `scripts/evaluation-judge.yaml` for the non-secret base URL/model and process environment only for `RD_EVAL_JUDGE_API_KEY`; there are no backend capability, Controller, or UI availability guards. An isolated `18082` HTTP start was blocked by an unrelated missing `TicketProviderPort` and was closed immediately.

## Follow-up: YAML Judge Runtime Config

**Goal:** Store the OpenAI-compatible Judge base URL and model in `scripts/evaluation-judge.yaml`; keep only `RD_EVAL_JUDGE_API_KEY` in the backend process environment.

**Architecture:** `rd_eval_score.py` continues to create the provider immediately before scoring. `OpenAICompatibleJudgeProvider.from_env(...)` reads a strict, repository-owned YAML subset from the fixed `scripts/evaluation-judge.yaml` path for non-secret settings, while it reads the API key solely from its supplied environment mapping. No Java controller, capability catalog, or frontend behavior changes.

- [x] Add failing Python tests proving YAML supplies base URL/model and the API key remains environment-only.
- [x] Add `scripts/evaluation-judge.yaml` with `RD_EVAL_JUDGE_BASE_URL` and `RD_EVAL_JUDGE_MODEL`; delete the incorrect shell `.env` file.
- [x] Implement the strict YAML loader and wire it into `OpenAICompatibleJudgeProvider.from_env(...)` without adding a third-party Python dependency.
- [x] Run the focused Python suite, full evaluation suite, bootstrap focused tests, frontend tests/typecheck/build, config syntax/secret scan, and verify no temporary backend listener remains.
