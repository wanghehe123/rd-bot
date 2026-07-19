# Task Stage Retry and AI Delivery Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add auditable resume-from-failure controls and an AI gate that reviews the complete requirement-delivery evidence before PR publication.

**Architecture:** Keep task retry, role attempts, RetrievalRun, and AiReviewRun as separate state machines. `engine` owns retry-point resolution, evidence packaging, model response validation, and gating through Store/Port contracts; `bootstrap` owns PostgreSQL, HTTP, provider and worker adapters; `frontend` exposes the retry confirmation and AI review detail surface.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL/MyBatis-Plus, React/TypeScript/Vite, JUnit 5, MockMvc.

---

### Task 1: Retry Domain and Resolver

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailurePhase.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCheckpoint.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryCheckpointStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryPointResolver.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/impl/InMemoryTaskRetryCheckpointStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryPointResolverTest.java`

- [ ] Write failing tests proving the resolver selects the earliest latest failed role, selects AI review and PR publication checkpoints, rejects non-retryable task states, and reports ambiguity instead of restarting from the beginning.
- [ ] Run `./mvnw -q -pl engine -Dtest=TaskRetryPointResolverTest test`; verify failures are caused by missing domain types.
- [ ] Implement immutable checkpoint values, deterministic role ordering, and an in-memory store with immutable snapshots.
- [ ] Re-run the focused test and keep all old attempts unchanged.

### Task 2: Retry Execution

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryDispatcherPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/AgentStageRunStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] Write failing tests proving a coding failure creates CODING attempt 2 without recreating reviewer/architect, an AI recommendation can reopen a previously successful role and downstream roles, concurrent duplicate retry returns the existing checkpoint, and PR publication retry creates no role attempts.
- [ ] Run focused tests and confirm expected RED assertions.
- [ ] Implement `TaskRetryEngine.retry(taskId)` with task-version idempotency, `RECOVERING` transition, new stage attempts from the resolved role, and dispatcher invocation after durable checkpoint creation.
- [ ] Add a `RequirementDeliveryEngine` resume hint so normal submission begins at the requested role/checkpoint rather than regenerating successful upstream work.
- [ ] Re-run focused tests and existing `RequirementDeliveryEngineTest`.

### Task 3: AI Review Domain, Validation, and Store

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewRunStatus.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewDecision.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewSource.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewPackage.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewResult.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewRun.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewArtifact.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/model/AiReviewEvent.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiReviewRunStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiReviewModelPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiReviewResultValidator.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/impl/InMemoryAiReviewRunStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/review/AiReviewResultValidatorTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/review/AiReviewRunStoreTest.java`

- [ ] Write failing tests for strict JSON, trailing tokens, score bounds, required NOT_OK findings, legal retry role, source-ID integrity, immutable terminal attempts, and child retry linkage.
- [ ] Run the focused tests and verify RED.
- [ ] Implement the state policy, append-only events/artifacts, strict response parser, and model port unavailable response.
- [ ] Re-run focused tests.

### Task 4: Complete Evidence Package

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiReviewPackageBuilder.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/retrieval/run/RetrievalRunStore.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/context/RoleContextPackageStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/review/AiReviewPackageBuilderTest.java`

- [ ] Write failing tests with one task containing materials, plan/policy, RAG query/plan/channel/candidate/evidence/quality artifacts, all role contexts, every stage prompt/result, QA evidence, and deterministic review.
- [ ] Assert every expected source ID appears exactly once, cross-task data is absent, source hashes are stable, and oversized packages split into parts with `omittedSourceCount=0`.
- [ ] Run the focused test and verify RED.
- [ ] Implement deterministic source ordering, redaction, source-manifest validation, and lossless part assignment under `maxInputChars`.
- [ ] Re-run focused tests plus `./mvnw -q -pl engine -am test`.

### Task 5: AI Review Engine and Requirement Gate

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiDeliveryReviewEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/model/AgentWorkflowAlertType.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/review/AiDeliveryReviewEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] Write failing tests proving OK reaches PR publication, NOT_OK/NEEDS_HUMAN block PR and mark the task human-required, provider failure marks retryable without fabricating a decision, and a new review attempt never mutates the old run.
- [ ] Run focused tests and verify RED.
- [ ] Implement package, part review, final aggregation, state/events/artifacts, `[AI_REVIEW]` logs, failure classification, and the gate between deterministic review and PR publication.
- [ ] Re-run focused and full engine tests.

### Task 6: PostgreSQL Migration and Stores

**Files:**
- Create: `bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/TaskRetryCheckpointRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/AiReviewRunRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/AiReviewEventRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/AiReviewArtifactRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskRetryCheckpointMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/AiReviewRunMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/AiReviewEventMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/AiReviewArtifactMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresAiReviewRunStore.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStoreTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresAiReviewRunStoreTest.java`

- [ ] Write failing mapper/store tests for unique retry idempotency, CAS transition plus event transaction, append-only artifacts, child attempts, and task-scoped queries.
- [ ] Run focused tests and verify RED.
- [ ] Implement idempotent DDL, MyBatis rows/mappers, `@Transactional` stores, and PostgreSQL-only production conditions.
- [ ] Execute the SQL twice and verify all four tables with `to_regclass` during real acceptance.

### Task 7: Provider and Dispatch Adapters

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/AiReviewProperties.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/AnthropicAiReviewModelAdapter.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/AiReviewDispatchService.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/TaskRetryDispatcherAdapter.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/impl/AnthropicAiReviewModelAdapterTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/threading/AiReviewDispatchServiceTest.java`

- [ ] Write failing tests for environment-only credentials, Anthropic-compatible request shape, HTTP/provider failures, async dispatch, retry budget, and recovery.
- [ ] Run focused tests and verify RED.
- [ ] Implement the adapters using `RD_AI_REVIEW_*`, default disabled/unavailable behavior, bounded timeouts and no credential logging.
- [ ] Re-run focused tests.

### Task 8: Admin APIs and Metrics

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/review/AiReviewController.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/observability/PrometheusMetricsController.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryControllerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/review/AiReviewControllerTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/observability/PrometheusMetricsControllerTest.java`

- [ ] Write failing MockMvc tests for retry preview/action/history, AI review list/detail/timeline/artifacts/retry/cancel, 404/409 handling, and preview-only responses.
- [ ] Run focused tests and verify RED.
- [ ] Implement thin record-based Controllers and metrics projection.
- [ ] Re-run focused bootstrap tests.

### Task 9: Admin UI

**Files:**
- Create: `frontend/src/services/taskRetryService.ts`
- Create: `frontend/src/services/aiReviewService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/test/viteProxy.test.ts`
- Test: `frontend/test/taskRetryAiReview.test.tsx`

- [ ] Write failing tests proving retry icon visibility only in retryable states, confirmation contents, duplicate-submit prevention, AI review status/score/findings/source links, and terminal polling stop.
- [ ] Run frontend tests and verify RED.
- [ ] Implement the top-right icon button, confirmation dialog, full-width AI review panel and responsive detail dialog using existing Lucide/shadcn patterns.
- [ ] Add `/admin/ai-reviews` and task retry/review proxy coverage.
- [ ] Run `npm --prefix frontend run typecheck`, frontend tests, and `npm --prefix frontend run build` to sync Spring static resources.

### Task 10: Real Acceptance and Closeout

**Files:**
- Create: `docs/qa/2026-07-13-task-stage-retry-ai-delivery-review-acceptance.md`
- Create evidence under: `qa-runs/task-stage-retry-ai-review/<timestamp>/`

- [ ] Run focused module tests, `./mvnw -q test`, frontend typecheck/build, and `git diff --check` while separating unrelated pre-existing failures.
- [ ] Execute migration twice and run `PostgresRdTaskStateAtomicRealSmokeTest`.
- [ ] Start an isolated bootstrap port only after `./mvnw -q install -DskipTests`.
- [ ] Use real HTTP and PostgreSQL to verify coding-stage retry, PR-only retry, AI NOT_OK gate, retry-from-role, AI OK publication, provider retryable failure, API 404/409 and metrics.
- [ ] Verify desktop/mobile UI with the built static bundle and capture screenshots.
- [ ] Write exact taskId/runId/checkpointId/attempt/database rows/status codes/evidence paths into the QA report.
- [ ] Stop every service started for acceptance with `Ctrl-C`, verify ports have no listeners, and record the closure evidence.
