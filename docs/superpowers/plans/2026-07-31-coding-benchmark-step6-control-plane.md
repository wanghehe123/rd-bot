# Coding Benchmark Step 6 Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Commits:** Do **not** create git commits unless the user explicitly asks. Skip every "Commit" step below unless instructed.

**Goal:** Unblock Step 6 locally with a Fake executor, independent `CodingBenchmarkDispatcher` enforcing §11 limits, pause/resume/cancel APIs + UI, run-status driving, and restart recovery.

**Architecture:** `CodingBenchmarkCampaignService` owns create + control APIs + `EvaluationRun` transitions; new `CodingBenchmarkDispatcher` owns the claim/execute loop and §11 gates; `FakeCodingBenchmarkExecutionPort` replaces Docker under `rd.evaluation.coding-benchmark.executor=fake`; `CodingBenchmarkExecutionHooks.beforeOracle()` makes oracle≤3 enforceable; `dispatch_paused` is a real column on `rd_evaluation_runs`.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus, PostgreSQL, JUnit 5, React/TypeScript admin UI, node:test.

**Spec:** `docs/superpowers/specs/2026-07-31-coding-benchmark-step6-control-plane-design.md`

---

## File map

| File | Role |
| --- | --- |
| `engine/.../CodingBenchmarkExecutionHooks.java` | **Create** — `beforeOracle()` hook |
| `engine/.../CodingBenchmarkExecutionPort.java` | **Modify** — add `execute(request, hooks)` default/overload |
| `engine/.../model/EvaluationRun.java` | **Modify** — add `dispatchPaused` + helpers |
| `bootstrap/.../entity/EvaluationRunRow.java` | **Modify** — `dispatchPaused` field |
| `bootstrap/.../sql/postgres/p4_web_evaluation_console.sql` | **Modify** — column + ALTER note |
| `bootstrap/.../persistence/impl/PostgresEvaluationRunStore.java` (+ mapper XML/annotations) | **Modify** — read/write `dispatch_paused` |
| `bootstrap/.../EvaluationProperties.java` | **Modify** — `codingBenchmarkExecutor` (`fake`/`docker`) |
| `bootstrap/.../impl/FakeCodingBenchmarkExecutionPort.java` | **Create** — stub executor |
| `bootstrap/.../impl/DockerCodingBenchmarkExecutor.java` | **Modify** — call `hooks.beforeOracle()` before oracle phase; keep no-arg `execute` delegating |
| `bootstrap/.../CodingBenchmarkExecutionConfiguration.java` | **Create** — conditional beans for fake vs docker |
| `bootstrap/.../impl/CodingBenchmarkDispatcher.java` | **Create** — §11 dispatch loop |
| `bootstrap/.../impl/CodingBenchmarkCampaignRecovery.java` | **Create** — `ApplicationReadyEvent` kick |
| `bootstrap/.../impl/CodingBenchmarkCampaignService.java` | **Modify** — remove N×submit; drive run status; pause/resume/cancel; kick dispatcher; fix terminal version |
| `bootstrap/.../CodingBenchmarkController.java` | **Modify** — 3 control endpoints |
| `frontend/src/services/evaluationService.ts` | **Modify** — 3 API helpers + `dispatchPaused` on type |
| `frontend/src/pages/admin/evaluation/CodingBenchmarkPage.tsx` | **Modify** — pause/resume/cancel UI |
| `frontend/test/evaluationConsole.test.ts` | **Modify** — assertions |
| Tests under `bootstrap/src/test/.../evaluation/` | **Create** — Fake / Dispatcher / Campaign service tests |

---

### Task 1: `dispatch_paused` on EvaluationRun + DDL

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/model/EvaluationRun.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/EvaluationRunRow.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p4_web_evaluation_console.sql`
- Modify: `PostgresEvaluationRunStore` + mapper (whatever currently maps `EvaluationRunRow` ↔ `EvaluationRun`)
- Test: extend or add a focused unit test that round-trips `dispatchPaused` if one exists; otherwise cover via Task 5 service test

- [ ] **Step 1: Add column to checked-in DDL**

In `p4_web_evaluation_console.sql`, add to `CREATE TABLE rd_evaluation_runs`:

```sql
dispatch_paused BOOLEAN NOT NULL DEFAULT FALSE,
```

Also append a migration-friendly statement at the bottom of the file (for existing DBs):

```sql
ALTER TABLE rd_evaluation_runs
  ADD COLUMN IF NOT EXISTS dispatch_paused BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2: Extend `EvaluationRun` record**

Add `boolean dispatchPaused` as the last field before timestamps **or** after `errorMessage` (keep consistency with how other flags are ordered — place after `failedSampleCount` / before `overallPassed` is fine; preferred: after `errorMessage`, before `version`).

Update every constructor call site (`created`, `withStatus`, `withResult`, and any `new EvaluationRun(...)`) to pass `false` by default.

Add:

```java
public EvaluationRun withDispatchPaused(boolean paused, long now) {
    return new EvaluationRun(
            runId, name, attemptNo, parentRunId, status, phaseMessage, progressPercent,
            config, sampleCount, passedSampleCount, failedSampleCount, overallPassed,
            metricsJson, errorCategory, errorMessage, paused, version + 1L,
            createdAtEpochMillis, startedAtEpochMillis, finishedAtEpochMillis, now);
}
```

Also update `withStatus` so `RUNNING_TRIALS` (not only `RECORDING`) sets `startedAt` when previously 0.

- [ ] **Step 3: Extend row + store mapping**

```java
// EvaluationRunRow
public Boolean dispatchPaused;
```

Map column `dispatch_paused` in insert/update/select. Store `setDispatchPaused(runId, boolean)` or fold into existing CAS update used by transition.

- [ ] **Step 4: Apply DDL to local Postgres used for smoke**

```bash
docker exec -i <pg-container> psql -U <user> -d <db> \
  -c "ALTER TABLE rd_evaluation_runs ADD COLUMN IF NOT EXISTS dispatch_paused BOOLEAN NOT NULL DEFAULT FALSE;"
```

- [ ] **Step 5: Commit** — skip unless user asks

---

### Task 2: Execution hooks + port overload

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkExecutionHooks.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkExecutionPort.java`
- Modify: `bootstrap/.../DockerCodingBenchmarkExecutor.java`
- Modify: `bootstrap/src/test/.../DockerCodingBenchmarkExecutorTest.java`

- [ ] **Step 1: Write failing test expectation**

In `DockerCodingBenchmarkExecutorTest`, add a capturing hooks test that fails until hooks are called:

```java
AtomicBoolean beforeOracle = new AtomicBoolean(false);
CodingBenchmarkExecutionHooks hooks = () -> beforeOracle.set(true);
executor.execute(request(), hooks);
assertTrue(beforeOracle.get());
```

- [ ] **Step 2: Add hooks type + port methods**

```java
package com.wish.rd.engine.evaluation;

@FunctionalInterface
public interface CodingBenchmarkExecutionHooks {
    /** Called after agent work and before oracle work; may block for capacity. */
    void beforeOracle();

    static CodingBenchmarkExecutionHooks noop() {
        return () -> { };
    }
}
```

```java
public interface CodingBenchmarkExecutionPort {
    default CodingBenchmarkExecutionResult execute(CodingBenchmarkExecutionRequest request) {
        return execute(request, CodingBenchmarkExecutionHooks.noop());
    }

    CodingBenchmarkExecutionResult execute(
            CodingBenchmarkExecutionRequest request,
            CodingBenchmarkExecutionHooks hooks);
}
```

Remove `@FunctionalInterface` from the port if it no longer qualifies, or keep only the two-arg method as the SAM and provide the default — prefer **two-arg as the only abstract method**.

- [ ] **Step 3: Wire Docker executor**

In `DockerCodingBenchmarkExecutor.execute`, after agent container finishes successfully and **before** starting the oracle container, call `hooks.beforeOracle()`. On infrastructure failure before oracle, do not call the hook.

- [ ] **Step 4: Run Docker executor tests**

```bash
./mvnw -pl bootstrap -am -Dtest=DockerCodingBenchmarkExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS

- [ ] **Step 5: Commit** — skip unless user asks

---

### Task 3: Fake executor + property switch

**Files:**
- Modify: `bootstrap/.../EvaluationProperties.java`
- Create: `bootstrap/.../evaluation/CodingBenchmarkExecutionConfiguration.java`
- Create: `bootstrap/.../evaluation/impl/FakeCodingBenchmarkExecutionPort.java`
- Modify: `DockerCodingBenchmarkExecutor` — add `@ConditionalOnProperty` **or** remove `@Component` and register only via config
- Create: `bootstrap/src/test/.../FakeCodingBenchmarkExecutionPortTest.java`
- Modify: `application.yaml` — document default `rd.evaluation.coding-benchmark.executor: docker`

- [ ] **Step 1: Failing unit test for Fake**

```java
@Test
void fakeExecutorReturnsDeterministicPassWithoutDocker() {
    FakeCodingBenchmarkExecutionPort fake = new FakeCodingBenchmarkExecutionPort();
    AtomicBoolean oracle = new AtomicBoolean();
    CodingBenchmarkExecutionResult result = fake.execute(minimalRequest(), () -> oracle.set(true));
    assertTrue(oracle.get());
    assertFalse(result.infrastructureFailure());
    assertEquals(0, result.agentExitCode());
    assertEquals(0, result.oracleExitCode());
}
```

- [ ] **Step 2: Implement Fake**

Behavior:

- Sleep a short configurable delay (default 10–50ms) so pause/cancel races are testable.
- Call `hooks.beforeOracle()` between stub agent and stub oracle phases.
- Support cooperative cancel via `void cancelCampaign(String campaignId)` / `ConcurrentHashMap.newKeySet()` of cancelled campaign ids; if cancelled mid-execute, return infrastructureFailure or a cancelled-shaped result consistent with campaign cancel handling.
- No Docker, no filesystem requirement beyond what the request already carries (ignore workspace if unused).

- [ ] **Step 3: Conditional wiring**

```java
@Configuration
public class CodingBenchmarkExecutionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "rd.evaluation.coding-benchmark.executor", havingValue = "fake")
    CodingBenchmarkExecutionPort fakeCodingBenchmarkExecutionPort() {
        return new FakeCodingBenchmarkExecutionPort();
    }
}
```

Ensure `DockerCodingBenchmarkExecutor` is only active when property is `docker` (default). Prefer:

```java
@Component
@ConditionalOnProperty(
    name = "rd.evaluation.coding-benchmark.executor",
    havingValue = "docker",
    matchIfMissing = true)
public final class DockerCodingBenchmarkExecutor implements CodingBenchmarkExecutionPort { ... }
```

Add to `EvaluationProperties`:

```java
private String codingBenchmarkExecutor = "docker";
```

with getter/setter; bind `rd.evaluation.coding-benchmark.executor`.

- [ ] **Step 4: Run Fake test**

```bash
./mvnw -pl bootstrap -am -Dtest=FakeCodingBenchmarkExecutionPortTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS

- [ ] **Step 5: Commit** — skip unless user asks

---

### Task 4: `CodingBenchmarkDispatcher` (§11)

**Files:**
- Create: `bootstrap/.../impl/CodingBenchmarkDispatcher.java`
- Create: `bootstrap/src/test/.../CodingBenchmarkDispatcherTest.java`

- [ ] **Step 1: Write failing dispatcher tests (in-memory fakes of stores)**

Required cases:

1. When `dispatchPaused=true`, no `claimNext` / no execute.
2. Active cap: with 6 active, do not claim a 7th.
3. Per-case cap: if case A already active, do not claim another A.
4. Oracle cap: when 3 trials are in `RUNNING_ORACLE`, a 4th `beforeOracle()` blocks until one finishes (use latches + Fake).
5. Terminal transitions use non-zero / actual versions (spy on store).

Use injectable collaborators (constructor): `EvaluationRunStore`, `CodingBenchmarkTrialStore`, `CodingBenchmarkExecutionPort`, `CodingBenchmarkSnapshotToRequestAdapter` / catalog as needed — or a narrower `TrialWorker` interface if that keeps the test small. Prefer extracting the worker logic the dispatcher needs so tests do not require Spring.

- [ ] **Step 2: Implement dispatcher**

Public API sketch:

```java
@Component
public final class CodingBenchmarkDispatcher {
    public void kick(String campaignId, String snapshotId);
    // internal: ensure single loop per campaignId (ConcurrentHashMap of futures)
}
```

Loop pseudocode (must match design §7.1):

1. Load run; stop if terminal / CANCEL*.
2. If `dispatchPaused` → wait briefly and re-check (or exit loop and rely on resume kick).
3. Count active by status; if ≥ 6 wait.
4. Choose claimable work: either enhance `claimNext` SQL later, or claim then release if per-case violated (prefer **pre-check** via `listByCampaign` if store already has list API; if not, add `listTrials(campaignId)` read on store — check existing methods first).
5. Execute with hooks that enforce oracle≤3 and CAS to `RUNNING_ORACLE`.
6. Terminal with **loaded version**, never `0L`.
7. Update run progress (passed/failed sample counts).
8. When no QUEUED left and no active → signal campaign service completion callback **or** transition run to SCORING/REPORTING/SUCCEEDED inside dispatcher via a small `CampaignCompletionPort` / package-private method on campaign service to avoid circular deps. Prefer `CodingBenchmarkCampaignService.onTrialsDrained(runId)` called by dispatcher.

- [ ] **Step 3: Run dispatcher tests**

```bash
./mvnw -pl bootstrap -am -Dtest=CodingBenchmarkDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS

- [ ] **Step 4: Commit** — skip unless user asks

---

### Task 5: CampaignService control plane + run lifecycle

**Files:**
- Modify: `bootstrap/.../impl/CodingBenchmarkCampaignService.java`
- Create: `bootstrap/src/test/.../CodingBenchmarkCampaignServiceTest.java`

- [ ] **Step 1: Failing service tests**

```java
@Test
void createProbeAdvancesRunToRunningTrialsAndKicksDispatcher() { ... }

@Test
void pauseSetsDispatchPausedAndDispatcherStopsClaiming() { ... }

@Test
void resumeClearsFlagAndContinues() { ... }

@Test
void cancelMarksRunAndNonTerminalTrialsCancelled() { ... }

@Test
void terminalTransitionUsesRealVersion() { ... }
```

Use Fake executor + in-memory or Mockito stores where Postgres is unavailable; prefer dedicated test doubles already used in engine evaluation tests.

- [ ] **Step 2: Refactor create path**

Replace:

```java
for (CodingBenchmarkTrial trial : trials) {
    scheduler.submit(() -> executeTrial(...));
}
```

with:

```java
EvaluationRun advanced = driveToRunningTrials(run, now);
dispatcher.kick(advanced.runId(), snapshotId);
return advanced;
```

Implement `driveToRunningTrials` using `EvaluationTransitionPolicy` / `runStore.transition` for `CREATED→QUEUED→PREPARING→RUNNING_TRIALS`.

- [ ] **Step 3: Implement pause / resume / cancel**

```java
public EvaluationRun pause(String runId) { ... withDispatchPaused(true) ... }
public EvaluationRun resume(String runId) { ... clear flag; dispatcher.kick(...) ... }
public EvaluationRun cancel(String runId) {
    // transition run CANCEL_REQUESTED → CANCELLED
    // list trials; for each non-terminal → CANCELLED with real version
    // fakePort.cancelCampaign(runId) if Fake
}
```

Move `executeTrial` body into dispatcher (or package-visible collaborator). Fix `terminalTransition` to accept `long expectedVersion`.

- [ ] **Step 4: Run service tests**

```bash
./mvnw -pl bootstrap -am -Dtest=CodingBenchmarkCampaignServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS

- [ ] **Step 5: Commit** — skip unless user asks

---

### Task 6: Recovery + Controller API

**Files:**
- Create: `bootstrap/.../impl/CodingBenchmarkCampaignRecovery.java`
- Modify: `bootstrap/.../CodingBenchmarkController.java`
- Create/Modify controller test if pattern exists

- [ ] **Step 1: Recovery bean**

```java
@Component
public final class CodingBenchmarkCampaignRecovery {
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // find CODING_BENCHMARK runs in RUNNING_TRIALS with dispatch_paused=false
        // dispatcher.kick(runId, snapshotIdFromConfig)
    }
}
```

Snapshot id must be recoverable from `EvaluationRunConfig` — if not present, extend config when creating campaigns to store `snapshotId` / mode metadata (check `EvaluationRunConfig` fields first; add `codingBenchmarkSnapshotId` if missing).

- [ ] **Step 2: Controller endpoints**

```java
@PostMapping("/admin/evaluations/coding-benchmarks/campaigns/{runId}/pause")
public EvaluationRun pause(@PathVariable String runId) { return campaignService.pause(runId); }

@PostMapping("/admin/evaluations/coding-benchmarks/campaigns/{runId}/resume")
public EvaluationRun resume(@PathVariable String runId) { return campaignService.resume(runId); }

@PostMapping("/admin/evaluations/coding-benchmarks/campaigns/{runId}/cancel")
public EvaluationRun cancel(@PathVariable String runId) { return campaignService.cancel(runId); }
```

Ensure JSON serialization includes `dispatchPaused`.

- [ ] **Step 3: Compile / targeted tests**

```bash
./mvnw -pl bootstrap -am -Dtest='CodingBenchmarkCampaign*,CodingBenchmarkDispatcher*,FakeCodingBenchmark*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS

- [ ] **Step 4: Commit** — skip unless user asks

---

### Task 7: Frontend pause / resume / cancel

**Files:**
- Modify: `frontend/src/services/evaluationService.ts`
- Modify: `frontend/src/pages/admin/evaluation/CodingBenchmarkPage.tsx`
- Modify: `frontend/src/styles.css` (only if needed for button row)
- Modify: `frontend/test/evaluationConsole.test.ts`

- [ ] **Step 1: Extend service**

```ts
export const pauseCodingBenchmarkCampaign = (runId: string) =>
  api.post<EvaluationRun, EvaluationRun>(
    `/admin/evaluations/coding-benchmarks/campaigns/${runId}/pause`, {});

export const resumeCodingBenchmarkCampaign = (runId: string) =>
  api.post<EvaluationRun, EvaluationRun>(
    `/admin/evaluations/coding-benchmarks/campaigns/${runId}/resume`, {});

export const cancelCodingBenchmarkCampaign = (runId: string) =>
  api.post<EvaluationRun, EvaluationRun>(
    `/admin/evaluations/coding-benchmarks/campaigns/${runId}/cancel`, {});
```

Extend `EvaluationRun` / snapshot types with optional `dispatchPaused?: boolean` if not already present.

- [ ] **Step 2: UI controls on `CodingBenchmarkPage`**

When `activeRun` exists and is non-terminal:

- Button 暂停 → `pauseCodingBenchmarkCampaign`
- Button 恢复 → `resumeCodingBenchmarkCampaign` (enabled when `dispatchPaused`)
- Button 取消 → `cancelCodingBenchmarkCampaign`
- Show badge/text when paused

Do not expose digests, commands, or secrets (existing test constraints).

- [ ] **Step 3: Frontend tests**

Add assertions that page source / service export includes pause/resume/cancel paths and labels.

```bash
cd frontend && node --test test/evaluationConsole.test.ts
cd frontend && npm run typecheck
```

Expected: PASS

- [ ] **Step 4: Commit** — skip unless user asks

---

### Task 8: End-to-end local rehearsal test + gap-report note

**Files:**
- Create: `bootstrap/src/test/.../CodingBenchmarkStep6RehearsalTest.java` (unit/integration with Fake; `@SpringBootTest` only if needed and gated)
- Modify: `docs/superpowers/plans/2026-07-30-evaluation-v2-gap-report.md` §7 Step 6 row

- [ ] **Step 1: Rehearsal test sequence**

1. Fake executor.
2. createProbe → 8 trials.
3. pause → assert no further claims for N ms while work in flight drains.
4. resume → remaining complete.
5. Second campaign: cancel mid-flight → run CANCELLED; non-terminal trials CANCELLED.
6. Recovery: leave QUEUED trials + call `recover()` → dispatcher finishes without duplicating SUCCEEDED.

- [ ] **Step 2: Run full Step 6 Maven filter + frontend**

```bash
./mvnw -pl bootstrap -am \
  -Dtest='CodingBenchmarkCampaign*,CodingBenchmarkDispatcher*,FakeCodingBenchmark*,CodingBenchmarkStep6*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && node --test test/evaluationConsole.test.ts && npm run typecheck
```

Expected: all PASS

- [ ] **Step 3: Update gap-report §7** — mark Step 6 local Fake control-plane as PASS; note real LongCat still blocked on 1b/credentials

- [ ] **Step 4: Commit** — skip unless user asks

---

## Spec coverage checklist

| Spec section | Task |
| --- | --- |
| §3 Fake + property switch | Task 3 |
| §4 `dispatch_paused` column | Task 1 |
| §5 pause/resume/cancel API + UI | Tasks 5–7 |
| §6 EvaluationRun lifecycle | Task 5 |
| §7 Dispatcher + §11 limits + beforeOracle | Tasks 2, 4 |
| §7.2 Recovery | Task 6 |
| §8 Fake behavior | Task 3 |
| §9 terminal version fix | Task 5 |
| §10 acceptance commands | Task 8 |

## Placeholder / consistency self-review

- No TBD/TODO left in task steps.
- Port method: two-arg `execute(request, hooks)` is canonical; one-arg defaults to noop hooks.
- Property name fixed: `rd.evaluation.coding-benchmark.executor`.
- `withDispatchPaused` and JSON field `dispatchPaused` naming kept consistent across Java/TS.
