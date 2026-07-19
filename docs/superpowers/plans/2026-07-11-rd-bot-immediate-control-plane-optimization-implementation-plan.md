# RD-Bot Immediate Control-Plane Optimization Implementation Plan

> **For agentic workers:** Execute inline with strict red-green-refactor cycles. Do not commit, push, or create a PR unless the user explicitly requests it.

**Goal:** Implement all immediate REQUIREMENT control-plane optimizations identified in the 2026-07-11 lifecycle audit and prove them with unit, PostgreSQL, HTTP, concurrency, and recovery evidence.

**Architecture:** Move transition legality, atomic task/event writes, role-context versioning, execution control, and durable dispatch into focused components. Keep `RequirementDeliveryEngine` as the four-role orchestrator, preserve ports/adapters, and use PostgreSQL transactions and lease-based job claiming for production coordination.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, MyBatis-Plus, PostgreSQL, Redisson, JUnit 5, MockMvc/real HTTP, shell verification.

**Execution Status (2026-07-11):** COMPLETED. The code, focused/full tests, real PostgreSQL rollback, idempotent SQL, real HTTP control flow, task concurrency, and expired-lease recovery evidence are recorded in `docs/qa/2026-07-11-rd-bot-immediate-control-plane-optimization-qa.md`.

---

## Task 1: Task-Type Transition Policy

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/runtime/RdTaskTransitionPolicy.java`
- Create: `rag/src/test/java/com/wish/rd/rag/runtime/RdTaskTransitionPolicyTest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`

- [ ] Write failing tests proving:
  - REQUIREMENT allows the full material/context/plan/policy/delivery chain.
  - REQUIREMENT rejects `CREATED -> SEARCHING`.
  - BUG_FIX allows `CREATED -> SEARCHING` and rejects `CREATED -> MATERIAL_COLLECTING`.
  - Failure states enter task-level `RECOVERING` before resuming.
  - Active states can enter `CANCELLED`.
  - Dispatch failures can enter `DEAD_LETTERED`.

- [ ] Run:

```bash
./mvnw -q -pl rag -Dtest=RdTaskTransitionPolicyTest test
```

Expected: FAIL because `RdTaskTransitionPolicy` does not exist.

- [ ] Implement `RdTaskTransitionPolicy.ensureTransition(RdTaskType, RdTaskStatus, RdTaskStatus)` with immutable per-type edge maps.

- [ ] Replace `RagStreamTaskRegistry.ensureTransition(...)` with the policy and pass the concrete task type from each transition method.

- [ ] Re-run the focused test and existing rag runtime tests.

```bash
./mvnw -q -pl rag -Dtest=RdTaskTransitionPolicyTest,RagStreamTaskRegistryTest,RagStreamTaskRegistryRequirementTest test
```

Expected: PASS.

## Task 2: Task-Scoped Locking

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`
- Modify: `rag/src/test/java/com/wish/rd/rag/runtime/RagStreamTaskRegistryDistributedLockTest.java`

- [ ] Add a failing concurrency test with a recording `DistributedLockExecutor` proving two different task IDs receive different lock names.

- [ ] Add a failing concurrency test proving two updates to the same task use the same lock key.

- [ ] Run:

```bash
./mvnw -q -pl rag -Dtest=RagStreamTaskRegistryDistributedLockTest test
```

Expected: FAIL because all calls use `rd-bot:lock:rag-stream-task-registry`.

- [ ] Introduce:

```java
private <T> T withTaskLock(String taskId, Supplier<T> action)
private <T> T withTicketLock(String ticketId, Supplier<T> action)
```

with keys `rd-bot:lock:rd-task:<id>` and `rd-bot:lock:rd-ticket:<id>`.

- [ ] Remove distributed write locking from `get*`, `list*`, `query*`, and `timeline` reads.

- [ ] Ensure write methods do not call lock-taking public reads from inside a task lock; use private unlocked store lookups.

- [ ] Re-run focused tests and a two-task parallel timing test.

## Task 3: Atomic Task Snapshot and Event Persistence

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/runtime/RdTaskStatePersistence.java`
- Create: `rag/src/main/java/com/wish/rd/rag/runtime/impl/CoordinatedRdTaskStatePersistence.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdTaskStatePersistence.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRdTaskStatePersistenceTest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`

- [ ] Write a failing bootstrap test that uses a transaction-aware test database, forces the event insert to fail, and asserts the task status remains unchanged.

- [ ] Write a rag unit test asserting one registry transition calls `saveWithEvent` exactly once instead of independently saving task and event.

- [ ] Implement the port:

```java
RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event);
```

- [ ] Implement the PostgreSQL adapter with `@Transactional` and the existing task/event stores.

- [ ] Change registry create, transition, pause, and resume flows to build the event first and call the atomic port.

- [ ] Keep pure approval events append-only and deletion behavior explicit.

- [ ] Run:

```bash
./mvnw -q -pl rag test
./mvnw -q -pl bootstrap -Dtest=PostgresRdTaskStatePersistenceTest test
```

Expected: PASS, including rollback assertion.

## Task 4: Unified Stop, Cancel, Recovery, and Dead Letter

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/control/RdTaskExecutionControlEngine.java`
- Create: `engine/src/main/java/com/wish/rd/engine/control/RdTaskExecutionControlPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/control/model/RdTaskExecutionControlResult.java`
- Create: `engine/src/test/java/com/wish/rd/engine/control/RdTaskExecutionControlEngineTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRdTaskExecutionControlAdapter.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/AgentStageTransitions.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskControllerTest.java`

- [ ] Write failing engine tests for:
  - stopping a REQUIREMENT in EXECUTING;
  - cancelling its latest nonterminal AgentStageRun;
  - leaving historical attempts unchanged;
  - repeated stop being idempotent;
  - failed requirement resume entering task-level RECOVERING.

- [ ] Implement generic `cancelTask`, `markRecovering`, and `markDeadLettered` registry methods for both task models.

- [ ] Allow every nonterminal Agent stage to enter `CANCELLED`.

- [ ] Move controller stop orchestration into `RdTaskExecutionControlEngine`; keep HTTP adaptation only in the controller.

- [ ] Update resume handling so REQUIREMENT failures enter RECOVERING and are submitted through the durable dispatcher.

- [ ] Run focused engine and controller tests.

## Task 5: Single New-Attempt Recovery Semantics

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/model/AgentStageStatus.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/AgentStageTransitions.java`
- Create: `engine/src/test/java/com/wish/rd/engine/agent/AgentStageTransitionsTest.java`
- Modify: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] Write failing tests proving `FAILED_RETRYABLE` is terminal and cannot transition to `RECOVERING`.

- [ ] Preserve the existing regression test proving a failed role receives `attemptNo + 1` on resubmit.

- [ ] Add a test proving the old attempt status and artifact IDs remain unchanged.

- [ ] Update enum terminal semantics and transition policy; keep RECOVERING enum readable for historical rows but stop writing it.

- [ ] Run all engine tests.

## Task 6: Versioned Role Context Refresh

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/RoleContextVersionManager.java`
- Create: `engine/src/test/java/com/wish/rd/engine/requirement/RoleContextVersionManagerTest.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/context/RoleContextBuilder.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`

- [ ] Write failing tests proving:
  - first context has version 1;
  - identical semantic input reuses version 1;
  - one new material creates version 2;
  - only timestamp changes do not create a new version;
  - a new stage attempt binds the newest package while the old attempt retains the old package ID.

- [ ] Add a builder overload accepting `packageVersion` while keeping the old signature compatible.

- [ ] Implement semantic fingerprinting over role, evidence ID/hash/title/summary, acceptance criteria, risk hints, budget, and omitted IDs.

- [ ] Move `ensureRoleContexts` logic from `RequirementDeliveryEngine` into the manager.

- [ ] Run focused and full engine tests.

## Task 7: Durable Requirement Delivery Jobs

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementDeliveryJobStatus.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementDeliveryJob.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementDeliveryJobStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/InMemoryRequirementDeliveryJobStore.java`
- Create: `engine/src/test/java/com/wish/rd/engine/requirement/job/InMemoryRequirementDeliveryJobStoreTest.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RequirementDeliveryJobRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementDeliveryJobMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementDeliveryJobStore.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementDeliveryJobStoreTest.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchServiceTest.java`

- [ ] Write store tests for create-or-reuse, atomic claim, heartbeat, completion, retry, expired lease takeover, and max-attempt dead letter.

- [ ] Add the idempotent SQL table and indexes; ensure the script succeeds twice.

- [ ] Implement PostgreSQL claim as one conditional update so only one worker obtains a lease.

- [ ] Change dispatcher submission to persist PENDING before scheduling.

- [ ] On execution, claim the job, run `RequirementDeliveryEngine.submit`, and persist completion/failure.

- [ ] Add scheduled recovery of PENDING, FAILED_RETRYABLE, and expired RUNNING jobs.

- [ ] When attempts exceed the configured maximum, mark both job and task DEAD_LETTERED.

- [ ] Add configuration defaults for max attempts, lease duration, and recovery interval.

- [ ] Run engine, bootstrap, SQL policy, and dispatch tests.

## Task 8: Documentation Alignment and Architecture Guardrails

**Files:**
- Modify: `RULE.md`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/RuleModuleAlignmentPolicyTest.java`
- Modify: `AGENTS.md` only if new operational commands or durable-job recovery rules need repository entry guidance.

- [ ] Write a failing policy test that reads root `pom.xml` and asserts every module is documented in RULE.md while removed modules are not presented as current Maven modules.

- [ ] Update RULE.md dependency direction and exec/skill responsibilities to match current code.

- [ ] Add durable dispatcher and task-type state policy guidance without credential values.

- [ ] Run the policy test.

## Task 9: Focused and Full Automated Verification

**Files:**
- Verify all changed modules.

- [ ] Run formatting and focused tests:

```bash
./mvnw -q -pl rag test
./mvnw -q -pl engine test
./mvnw -q -pl bootstrap -Dtest=RdTaskControllerTest,PostgresRdTaskStatePersistenceTest,PostgresRequirementDeliveryJobStoreTest,RequirementDeliveryDispatchServiceTest,RuleModuleAlignmentPolicyTest test
```

- [ ] Install changed dependency modules before bootstrap tests:

```bash
./mvnw -q -pl rag,engine install -DskipTests
```

- [ ] Run full suite:

```bash
./mvnw -q test
git diff --check
```

- [ ] Record every failure and fix it before real acceptance.

## Task 10: Real PostgreSQL, HTTP, Concurrency, and Recovery Acceptance

**Files:**
- Create: `docs/qa/2026-07-11-rd-bot-immediate-control-plane-optimization-qa.md`

- [ ] Verify PostgreSQL availability and run `p1_multi_agent_orchestration.sql` twice.

- [ ] Run real transaction rollback verification and query `rd_tasks` plus `rd_task_status_events`.

- [ ] Start bootstrap with mock executor and external side effects disabled.

- [ ] Create and submit a REQUIREMENT through real HTTP; query detail, timeline, execution overview, context packages, and delivery job.

- [ ] Start a controllable long-running requirement execution, call `/admin/rd-tasks/{taskId}/stop`, and verify task plus latest stage are CANCELLED in HTTP and PostgreSQL.

- [ ] Insert or create an expired RUNNING delivery job, trigger recovery, and prove another lease owner claims it.

- [ ] Run two different task transitions concurrently and record timings showing they are not globally serialized.

- [ ] Write exact commands, response codes, IDs, database rows, pass/fail decisions, and blocked external side effects into the QA report.

## Completion Gate

The work is complete only when all 10 audit findings have a corresponding code or documentation change, every focused and full test passes, real PostgreSQL and HTTP evidence is recorded, and no Provider, Docker Agent, PR, Feishu, or credential side effect occurs without explicit authorization.
