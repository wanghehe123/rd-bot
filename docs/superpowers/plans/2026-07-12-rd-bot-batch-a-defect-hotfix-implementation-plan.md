# RD-Bot Batch A Defect Hotfix Implementation Plan

> **For agentic workers:** Execute inline with strict red-green-refactor cycles. Do not commit, push, or create a PR unless the user explicitly requests it.

**Goal:** Fix the highest-severity control-plane, dispatch, Vite proxy, knowledge-map leakage, task-detail coupling, and Prometheus isolation defects identified in the 2026-07-12 audit.

**Architecture:** Keep ports/adapters and existing engines. Correct lease/claim/dead-letter semantics in job store + dispatch; make stop cancel delivery jobs; seal knowledge-map summaries; isolate optional RAG failures from primary admin surfaces.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, Vite/React admin, JUnit 5.

**Source of truth:** `docs/superpowers/specs/2026-07-12-rd-bot-defect-audit-solutions-and-acceptance.md` Batch A.

---

## File Map

| Area | Files |
|------|-------|
| Job claim/dead-letter | `RequirementDeliveryJobMapper.java`, `InMemoryRequirementDeliveryJobStore.java`, `RequirementDeliveryJob.java`, `PostgresRequirementDeliveryJobStore.java` |
| Dispatch result routing | `RequirementDeliveryDispatchService.java`, `RequirementDeliveryDispatchServiceTest.java` |
| Transition edges | `RdTaskTransitionPolicy.java`, `RdTaskTransitionPolicyTest.java` |
| Stop ↔ job | `RdTaskExecutionControlEngine.java`, `RdTaskExecutionControlEngineTest.java`, `RdTaskController.java` (if needed) |
| Vite proxy | `frontend/vite.config.ts`, `frontend/test/viteProxy.test.ts` |
| Knowledge map | `KnowledgeMapController.java` (+ test) |
| Task detail | `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` then `npm run build` sync static |
| Metrics | `PrometheusMetricsController.java`, `PrometheusMetricsControllerTest.java` |

---

## Task 1: Dead-letter and last-attempt lease recovery (CP-01 / CP-05)

- [ ] Write failing tests:
  - `maxAttempts=2`, claim attempt=2, expire lease → recoverable sees it OR auto dead-letters; final job+task `DEAD_LETTERED`
  - `markDeadLettered` from `REJECTED` / `FAILED_NEEDS_HUMAN` / `CANCELLED` succeeds
- [ ] Fix claim/recoverable SQL and in-memory predicates; add transition edges to `DEAD_LETTERED`
- [ ] Run:

```bash
./mvnw -q -pl rag -Dtest=RdTaskTransitionPolicyTest test
./mvnw -q -pl engine -Dtest=InMemoryRequirementDeliveryJobStoreTest test
./mvnw -q -pl bootstrap -Dtest=RequirementDeliveryDispatchServiceTest,PostgresRequirementDeliveryJobStoreTest test
```

Expected: PASS.

---

## Task 2: Dispatch result routing and Future completion (CP-04 / CP-10)

- [ ] Write failing tests:
  - engine `Result.failure(retryable)` → job `FAILED_RETRYABLE`, not `SUCCEEDED`
  - lease steal during run → future completes (exceptional or explicit lease-lost)
- [ ] Change `RequirementDeliveryDispatchService.runClaimed` to branch on Result; wrap lease update failures
- [ ] Re-run dispatch tests

Expected: PASS.

---

## Task 3: Stop cancels delivery job; heartbeat = lease lost (CP-02 / CP-03 / CP-09)

- [ ] Write failing tests in `RdTaskExecutionControlEngineTest` / dispatch tests:
  - stop on EXECUTING cancels/fails running job
  - COMPLETED stop does not mutate SUCCEEDED stages
  - heartbeat failure prevents successful `complete`
- [ ] Implement control-engine job cancellation port/call; harden heartbeat path
- [ ] Run control + dispatch tests

Expected: PASS.

---

## Task 4: Vite proxies and tests (RAG-01 / RAG-04 / FE-01 / FE-07)

- [ ] Add proxies for `/admin/rag-retrieval-runs` and `/admin/knowledge-base`
- [ ] Extend `frontend/test/viteProxy.test.ts`
- [ ] Run:

```bash
cd frontend && node --test test/viteProxy.test.ts
```

Expected: PASS.

---

## Task 5: Knowledge map no raw content (RAG-02)

- [ ] Write failing controller/unit test: rebuild/GET must not contain fixture raw text
- [ ] Change summary generation to non-content descriptors
- [ ] Run new test

Expected: PASS.

---

## Task 6: Task detail decoupling (FE-02)

- [ ] Isolate `getRetrievalRuns` from main `Promise.all`
- [ ] Show panel error instead of whole-page missing task
- [ ] `npm run typecheck` and `npm run build`; confirm static admin bundle updated

Expected: typecheck + build PASS.

---

## Task 7: Prometheus RAG isolation (FE-03)

- [ ] Write failing test: RAG query throws → legacy metrics still populated
- [ ] Split try/catch for retrieval metrics
- [ ] Run `PrometheusMetricsControllerTest`

Expected: PASS.

---

## Task 8: Batch A verification gate

```bash
./mvnw -q -pl rag -Dtest=RdTaskTransitionPolicyTest test
./mvnw -q -pl engine -Dtest=RdTaskExecutionControlEngineTest,InMemoryRequirementDeliveryJobStoreTest test
./mvnw -q -pl bootstrap -Dtest=RequirementDeliveryDispatchServiceTest,PostgresRequirementDeliveryJobStoreTest,PrometheusMetricsControllerTest,KnowledgeMapControllerTest test
cd frontend && node --test test/viteProxy.test.ts && npm run typecheck && npm run build
git diff --check
```

Record evidence in a short QA note under `docs/qa/` only if user asks for a formal report.

---

## Out of scope (Batch B/C)

Retrieval run idempotent re-entry, operator retry execution, FAILED_RETRYABLE lifecycle writes, sequence guards, dispatch engine move to `engine`, deleteTask atomic audit, delivery-job UI.
