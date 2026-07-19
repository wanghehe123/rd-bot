# Web RAG Evaluation Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven-development and execute this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Web management console that configures and runs the existing RD-Bot Python evaluation pipeline locally with persistent state, logs, reports, cancellation, retry, and diff.

**Architecture:** `engine` owns the evaluation state machine and orchestration ports. `bootstrap` implements PostgreSQL persistence, a strictly allowlisted local Python process adapter, managed execution threads, and HTTP controllers. `frontend` adds an operational `/admin/evaluations` page that polls active runs and renders real score/report artifacts.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL/MyBatis-Plus, Python 3 standard library evaluation scripts, React 18, TypeScript, Vite, lucide-react.

---

### Task 1: Domain state machine and orchestration

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/**`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/EvaluationRunEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/EvaluationTransitionPolicyTest.java`

- [x] Write failing tests for valid transitions, illegal terminal mutation, structured configuration validation, asynchronous execution, cancellation, and parent-linked retry.
- [x] Run `./mvnw -q -pl engine -Dtest='Evaluation*Test' test` and verify the tests fail because the domain does not exist.
- [x] Add immutable models, Store/Execution/Scheduler ports, transition policy, in-memory test Store, and `EvaluationRunEngine`.
- [x] Re-run the focused engine tests and verify they pass.

### Task 2: Restricted local Python adapter

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/LocalPythonEvaluationExecutor.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/EvaluationProperties.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/EvaluationExecutionConfiguration.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation/LocalPythonEvaluationExecutorTest.java`

- [x] Write failing tests proving command arguments are fixed, paths stay under allowlisted roots, loopback URLs are accepted, external URLs/path traversal are rejected, output is logged, score JSON is parsed, and cancellation terminates the current process.
- [x] Run the focused bootstrap test and verify RED.
- [x] Implement `ProcessBuilder(List<String>)` execution for record, score, report, and optional diff without shell interpolation.
- [x] Persist process output under `qa-runs/evaluation/web-runs/<runId>` and return typed artifacts/metrics.
- [x] Re-run the focused test and verify GREEN.

### Task 3: PostgreSQL persistence

**Files:**
- Create: `bootstrap/src/main/resources/sql/postgres/p4_web_evaluation_console.sql`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/Evaluation*Row.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/Evaluation*Mapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresEvaluationRunStore.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresEvaluationRunStoreTest.java`

- [x] Write failing Store tests for atomic transition + event, list ordering, artifact append, optimistic state checking, and terminal immutability.
- [x] Run the focused Store test and verify RED.
- [x] Add idempotent SQL and the MyBatis Store implementation.
- [x] Re-run the focused Store test and verify GREEN.
- [x] Apply the SQL twice to the real local PostgreSQL database and confirm all three tables with `to_regclass`.

### Task 4: Management API

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/evaluation/EvaluationController.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/evaluation/EvaluationControllerTest.java`

- [x] Write failing MockMvc/controller tests for capabilities, create, list, detail, timeline, artifacts, log/content reads, cancel, retry, 400, 404, and 409 behavior.
- [x] Run the focused controller test and verify RED.
- [x] Add DTO mapping and exception translation; never expose absolute paths or process environment.
- [x] Re-run the focused controller test and verify GREEN.

### Task 5: Evaluation management page

**Files:**
- Create: `frontend/src/services/evaluationService.ts`
- Create: `frontend/src/pages/admin/evaluation/EvaluationPage.tsx`
- Create: `frontend/src/pages/admin/evaluation/evaluationPresentation.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/AdminLayout.tsx`
- Modify: `frontend/src/index.css`
- Modify: `frontend/vite.config.ts`
- Test: `frontend/test/evaluationConsole.test.ts`
- Test: `frontend/test/viteProxy.test.ts`

- [x] Write failing source-level tests for the `/admin/evaluations` route, sidebar entry, proxy, configuration controls, active polling, logs, metrics, failures, artifacts, cancel, retry, and baseline diff.
- [x] Run `node --test frontend/test/evaluationConsole.test.ts frontend/test/viteProxy.test.ts` and verify RED.
- [x] Implement the typed service and responsive operational page using existing UI primitives and lucide icons.
- [x] Add the route, navigation label, breadcrumb, and Vite proxy.
- [x] Re-run the frontend tests, typecheck, and production build; verify the Spring static bundle contains the route and API strings.

### Task 6: Real acceptance and durable evidence

**Files:**
- Create: `docs/qa/2026-07-13-web-rag-evaluation-console-acceptance.md`
- Create during validation: `qa-runs/web-evaluation-console/<timestamp>/**`

- [x] Run Python unit tests and an API-triggered fixture evaluation.
- [x] Query the same `runId` through HTTP, PostgreSQL, logs, score JSON, report, failures, and artifact endpoints.
- [x] Run one cancellation and one retry chain; prove the retry has a new `runId`, higher `attemptNo`, and `parentRunId`.
- [x] Run one baseline diff and verify its artifact is readable from the Web API.
- [x] Use the built frontend in desktop and mobile browser widths; capture screenshots and check console/network errors and text overlap.
- [x] Run focused Maven tests, `npm run typecheck`, `npm run build`, and `git diff --check`.
- [x] Record exact commands, status codes, IDs, DB rows, evidence paths, failures, and skipped external checks in the QA report.
- [x] Stop only the backend/frontend sessions started for this acceptance and confirm their ports no longer listen.
