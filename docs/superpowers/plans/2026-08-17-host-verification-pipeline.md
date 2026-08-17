# Host Verification Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After `CODING_AGENT` succeeds, the host runs BUILD then STATIC on a clean replay of the candidate patch, and only then dispatches `QA_AGENT`. Code failures automatically create at most two new Coding attempts and never pre-create QA.

**Architecture:** Independent `HostVerificationRun` state machine (like `AiReviewRun`), not a fifth `AgentRole`. `RequirementAgentStageOrchestrator` calls `HostVerificationPort`; bootstrap replays the patch via `CleanHostVerifierWorkspaceFactory` and executes allowlisted commands. PostgreSQL is the truth for runs, steps, and artifacts.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL, existing Docker/workspace factories, JUnit 5.

**OpenSpec:** `openspec/changes/host-verification-pipeline/` (proposal, spec, design, tasks). Read those before coding. Do not edit `openspec/specs/` directly.

**Frontend:** Out of scope for this plan. Hand the other agent `docs/superpowers/plans/2026-08-17-host-verification-pipeline-frontend.md` and do not implement UI here. Backend MUST still ship the HTTP contract and Vite proxy test fixture so the frontend agent is not blocked.

**Locked decisions:**

- Cheap remediations = **2**. QA remediations stay **1**.
- `MAX_ROLE_ATTEMPTS = 3` still caps Coding.
- Environment / infrastructure / ambiguity / flaky → human, never cheap remediations.
- Docs-only skips BUILD and STATIC using the existing git change-set classifier.
- BUILD must not use `npm run dev` / `next dev`.

---

## File map

**Create (engine)**

- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationStatus.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationStepName.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationRun.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationStep.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationArtifact.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationCommandSet.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/HostVerificationStore.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/HostVerificationPort.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/HostVerificationEngine.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/verify/impl/InMemoryHostVerificationStore.java`
- matching tests under `engine/src/test/java/com/wish/rd/engine/requirement/verify/`

**Create (exec)**

- `exec/src/main/java/com/wish/rd/exec/repair/verify/HostVerificationCommandDetector.java`
- `exec/src/main/java/com/wish/rd/exec/repair/verify/HostVerificationCommandRunner.java`
- tests under `exec/src/test/java/com/wish/rd/exec/repair/verify/`

**Create (bootstrap)**

- `bootstrap/src/main/resources/sql/postgres/p15_host_verification.sql`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/HostVerificationRunRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/HostVerificationMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresHostVerificationStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/verify/HostVerificationExecutorAdapter.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/HostVerificationController.java`
- matching tests

**Modify**

- `engine/.../model/AgentWorkflowPlan.java`
- `engine/.../RequirementAgentStageOrchestrator.java`
- `engine/.../RequirementDeliveryEngine.java` (`createHostVerifyRemediationAttempt`, attempt cap)
- `engine/.../retry/model/TaskFailurePhase.java`
- `engine/.../retry/TaskRetryPointResolver.java` and provenance if a verification run id is required
- `rag/.../qa/model/QaValidationProfile.java` + `QaValidationProfileCommand.java` + service + postgres store + mapper
- `exec/.../qa/model/QaExecutionProfile.java` + `QaRepositoryProfileDetector.java`
- `bootstrap/.../controller/admin/project/QaValidationProfileController.java`
- `bootstrap/src/main/resources/sql/postgres/README.md`
- `frontend/test/viteProxy.test.ts` (path fixture only)

---

### Task 1: Domain records and plan flags

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationStatus.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationStepName.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/verify/model/HostVerificationRun.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/model/AgentWorkflowPlan.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/model/AgentWorkflowPlanTest.java` (create if missing)
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/verify/HostVerificationRunTest.java`

- [ ] **Step 1: Write the failing plan test**

```java
@Test
void productionPlanEnablesHostVerifyWithTwoPasses() {
    AgentWorkflowPlan plan = AgentWorkflowPlan.production();
    assertTrue(plan.hostVerifyRemediationAllowed());
    assertEquals(2, plan.hostVerifyMaxRemediationPasses());
    assertEquals(1, plan.qaMaxRemediationPasses());
}

@Test
void hostVerifyDisabledCannotRaisePassCap() {
    assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowPlan(
            AgentRole.requirementDeliveryOrder(),
            true,
            true,
            1,
            false,
            3,
            ledgerOfProduction(),
            "BAD"));
}
```

- [ ] **Step 2: Run the test and confirm it fails to compile or assert**

```bash
./mvnw -pl engine -am -Dtest=AgentWorkflowPlanTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL — `hostVerifyRemediationAllowed` does not exist.

- [ ] **Step 3: Add enums and the run record**

```java
public enum HostVerificationStatus {
    CREATED, PREPARING, BUILDING, STATIC_CHECKING,
    SUCCEEDED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN,
    SKIPPED_DOCS_ONLY, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == FAILED_RETRYABLE
                || this == FAILED_NEEDS_HUMAN
                || this == SKIPPED_DOCS_ONLY
                || this == CANCELLED;
    }
}

public enum HostVerificationStepName { BUILD, STATIC }

public enum HostVerificationStepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }
```

`HostVerificationRun` is an immutable record: `runId`, `taskId`, `codingStageRunId`, `parentRunId`, `attemptNo`, `status`, `docsOnly`, `failureCategory`, `errorMessage`, `remediationCount`, timestamps. Compact constructor rejects blank ids, `attemptNo < 1`, and `remediationCount < 0`.

- [ ] **Step 4: Extend `AgentWorkflowPlan`**

Add `boolean hostVerifyRemediationEnabled` and `int hostVerifyMaxRemediationPasses` after the QA fields. Default production / arm D: enabled, passes = 2. Arms A–C: disabled, passes = 2 (same pattern as QA: disabled cannot set passes > default 2).

```java
public boolean hostVerifyRemediationAllowed() {
    return hostVerifyRemediationEnabled && roles.contains(AgentRole.CODING_AGENT);
}
```

Update every constructor call site in `AgentWorkflowPlan` and tests. `codingBenchmark(D)` must enable host verify.

- [ ] **Step 5: Re-run tests**

```bash
./mvnw -pl engine -am -Dtest=AgentWorkflowPlanTest,HostVerificationRunTest,RequirementAgentStageOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS for new tests; existing orchestrator tests still compile.

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/requirement/verify engine/src/main/java/com/wish/rd/engine/requirement/model/AgentWorkflowPlan.java engine/src/test/java/com/wish/rd/engine/requirement
git commit -m "feat: add host verification domain flags and run model"
```

---

### Task 2: Profile command fields

**Files:**

- Modify: `rag/src/main/java/com/wish/rd/rag/qa/model/QaValidationProfileCommand.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/qa/model/QaValidationProfile.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/qa/QaValidationProfileService.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/qa/model/QaExecutionProfile.java`
- Modify: `bootstrap/.../QaValidationProfileController.java` `ProfileRequest`
- Modify: `bootstrap/.../mapper` + `PostgresQaValidationProfileStore`
- Test: `rag/src/test/java/com/wish/rd/rag/qa/QaValidationProfileServiceTest.java`

- [ ] **Step 1: Failing tests**

```java
@Test
void rejectsDevServerAsBuildCommand() {
    assertThrows(IllegalArgumentException.class, () -> new QaValidationProfileCommand(
            "AUTO", "", "", "", List.of(), List.of(),
            List.of("npm run dev -- --host 0.0.0.0"),
            List.of()));
}

@Test
void missingBuildCommandsMeansUndeclaredNotSkip() {
    QaValidationProfileCommand command = new QaValidationProfileCommand(
            "AUTO", "", "", "", List.of(), List.of(), null, null);
    assertTrue(command.buildCommandsDeclared() == false);
    assertTrue(command.buildCommands().isEmpty());
}
```

Add `boolean buildCommandsDeclared()` / `staticCommandsDeclared()`. JSON `null` or omitted = undeclared. Explicit `[]` from task/project PUT = skip that step.

- [ ] **Step 2: Run test**

```bash
./mvnw -pl rag -am -Dtest=QaValidationProfileServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL.

- [ ] **Step 3: Implement fields**

Append `List<String> buildCommands` and `List<String> staticCommands` to command, profile, execution profile, controller request, mapper JSON columns. Reuse `validateCommand`. After existing checks:

```java
private static void rejectDevServerBuild(List<String> buildCommands) {
    for (String command : buildCommands) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("npm run dev") || lower.contains("next dev") || lower.matches(".*\\bvite\\b.*--host.*")) {
            throw new IllegalArgumentException("buildCommands must not start a dev server");
        }
    }
}
```

Postgres: add columns in `p15_host_verification.sql` (see Task 3) using `JSONB NOT NULL DEFAULT 'null'::jsonb` **or** nullable JSONB. Prefer nullable JSONB: `NULL` = undeclared, `'[]'` = skip. Old rows stay NULL.

Controller: accept optional lists; if request JSON omits the keys, pass `null` (Jackson missing = null), not empty list. Document this in the controller JavaDoc so the frontend agent does not send `[]` by accident when the user left the box empty on first load of an old profile.

- [ ] **Step 4: Re-run**

```bash
./mvnw -pl rag,bootstrap -am -Dtest=QaValidationProfileServiceTest,QaValidationProfileControllerTest,PostgresQaValidationProfileStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add rag exec bootstrap
git commit -m "feat: add build and static commands to QA profiles"
```

---

### Task 3: PostgreSQL store

**Files:**

- Create: `bootstrap/src/main/resources/sql/postgres/p15_host_verification.sql`
- Modify: `bootstrap/src/main/resources/sql/postgres/README.md`
- Create store port + memory + postgres implementations
- Test: `engine` store contract test + `bootstrap` postgres policy test

- [ ] **Step 1: DDL**

```sql
ALTER TABLE rd_qa_validation_profiles
    ADD COLUMN IF NOT EXISTS build_commands_json JSONB,
    ADD COLUMN IF NOT EXISTS static_commands_json JSONB;

CREATE TABLE IF NOT EXISTS rd_host_verification_runs (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    coding_stage_run_id BIGINT NOT NULL,
    parent_run_id BIGINT NOT NULL DEFAULT 0,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    docs_only BOOLEAN NOT NULL DEFAULT FALSE,
    failure_category VARCHAR(32) NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    remediation_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE (task_id, attempt_no),
    CONSTRAINT chk_rd_host_verify_status CHECK (status IN (
        'CREATED','PREPARING','BUILDING','STATIC_CHECKING',
        'SUCCEEDED','FAILED_RETRYABLE','FAILED_NEEDS_HUMAN',
        'SKIPPED_DOCS_ONLY','CANCELLED')),
    CONSTRAINT chk_rd_host_verify_attempt CHECK (attempt_no >= 1),
    CONSTRAINT chk_rd_host_verify_remediation CHECK (remediation_count >= 0)
);

CREATE TABLE IF NOT EXISTS rd_host_verification_steps (
    run_id BIGINT NOT NULL,
    step VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    commands_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    exit_code INT,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    log_artifact_id BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (run_id, step),
    CONSTRAINT chk_rd_host_verify_step CHECK (step IN ('BUILD','STATIC'))
);

CREATE TABLE IF NOT EXISTS rd_host_verification_artifacts (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    relative_path TEXT NOT NULL,
    object_uri TEXT NOT NULL,
    content_type VARCHAR(160) NOT NULL DEFAULT 'text/plain',
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, relative_path)
);
```

Add README row `| 15 | p15_host_verification.sql |`.

- [ ] **Step 2: Store contract tests first**

`save` then `find` returns the same run. Transition uses `WHERE id=? AND status=?` and throws on 0 rows (stale write). Terminal status cannot transition to a non-terminal status.

- [ ] **Step 3: Implement memory + postgres stores**

Postgres class must not be `final` if it uses `@Transactional`. Follow `PostgresHostAssertionBundleStore`.

- [ ] **Step 4: Verify**

```bash
./mvnw -pl engine,bootstrap -am -Dtest=HostVerificationStoreContractTest,PostgresHostVerificationStoreTest,TransactionalProxyPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/resources/sql/postgres engine/src/main/java/com/wish/rd/engine/requirement/verify bootstrap/src/main/java/com/wish/rd/bootstrap/persistence
git commit -m "feat: persist host verification runs in PostgreSQL"
```

---

### Task 4: Command detection

**Files:**

- Create: `exec/src/main/java/com/wish/rd/exec/repair/verify/HostVerificationCommandDetector.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/qa/QaRepositoryProfileDetector.java` (docs-only reuse only)
- Test: `exec/src/test/java/com/wish/rd/exec/repair/verify/HostVerificationCommandDetectorTest.java`

- [ ] **Step 1: Failing tests with temp dirs**

Cases:

1. `package.json` with `build` + `test` + `typecheck` → BUILD `[install, npm test, npm run build]`, STATIC `[npm run typecheck]`.
2. Vite-only scripts, no explicit buildCommands → BUILD contains `npm run build`, not `npm run dev`.
3. Next.js → BUILD uses production build, not `next dev`.
4. `pom.xml` + `mvnw` → BUILD `./mvnw -q test`.
5. Empty repo → detector returns undeclared/empty and `ambiguous=true`.
6. docs-only file set → both command lists empty and `docsOnly=true`.

Install command: if `package-lock.json` exists use `npm ci`, else `npm install`. Do not delete `node_modules` or cache.

- [ ] **Step 2: Implement detector**

Priority inside detector: explicit profile lists (if declared) win; else auto-detect. Do not read Coding `testCommands`.

- [ ] **Step 3: Verify**

```bash
./mvnw -pl exec -am -Dtest=HostVerificationCommandDetectorTest,QaRepositoryProfileDetectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: Commit**

```bash
git add exec
git commit -m "feat: detect host BUILD and STATIC commands without dev servers"
```

---

### Task 5: Host executor adapter

**Files:**

- Create: `engine/.../HostVerificationPort.java`
- Create: `bootstrap/.../verify/HostVerificationExecutorAdapter.java`
- Create: `exec/.../verify/HostVerificationCommandRunner.java`
- Test: adapter + runner tests with fake workspace

Port signature:

```java
public interface HostVerificationPort {
    HostVerificationRun verify(
            RdRequirementTask task,
            AgentStageRun codingStage,
            AgentWorkflowPlan plan,
            int remediationCountAlreadyUsed
    );
}
```

Adapter algorithm:

1. Insert run `CREATED` with next `attemptNo`.
2. Classify docs-only from the already-applied candidate change set (`QaDocsOnlyChangeClassifier`). If docs-only → steps SKIPPED, run `SKIPPED_DOCS_ONLY` (treat as success for dispatch).
3. Replay patch with `CleanHostVerifierWorkspaceFactory`.
4. Mount task `cache/` if the factory exposes it; never delete cache.
5. Resolve commands. If BUILD undeclared and auto-detect empty → `FAILED_NEEDS_HUMAN` / `REQUIREMENT_AMBIGUITY`.
6. Run BUILD commands sequentially. First non-zero exit → record step, classify, stop. Do not run STATIC.
7. Run STATIC the same way.
8. Write logs under `verify-evidence/build/*.log` and `verify-evidence/static/*.log` with path/bytes/sha256.
9. Wall-clock budget default 10 minutes (`rd.host-verify.timeout-seconds:600`). Timeout → `QA_INFRASTRUCTURE` / `FAILED_NEEDS_HUMAN`.

Classification helper (deterministic, no LLM):

- exit 0 → pass
- command not found / permission / network install error text → `ENVIRONMENT` or `QA_INFRASTRUCTURE`
- compiler/test/lint non-zero after patch applied → `PRODUCT_DEFECT`

Coding `testStatus` is ignored.

- [ ] **Step 1: Test replay + failing compile does not run STATIC**
- [ ] **Step 2: Test passed BUILD runs STATIC**
- [ ] **Step 3: Test timeout does not request coding remediations**
- [ ] **Step 4: Implement and run**

```bash
./mvnw -pl bootstrap -am -Dtest=HostVerificationExecutorAdapterTest,HostVerificationCommandRunnerTest,CleanHostVerifierWorkspaceFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/requirement/verify exec/src/main/java/com/wish/rd/exec/repair/verify bootstrap/src/main/java/com/wish/rd/bootstrap/verify
git commit -m "feat: execute host BUILD and STATIC on a clean patch replay"
```

---

### Task 6: Orchestrator insertion and cheap remediations

**Files:**

- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestratorTest.java`

Insert after a `CODING_AGENT` success, before the loop continues to `QA_AGENT` (or immediately before dispatching QA if you keep a single loop):

```java
if (role == AgentRole.CODING_AGENT) {
    HostVerificationRun verification = hostVerificationPort.verify(
            task, stage, plan, hostVerifyRemediationCount);
    if (verification.status() == HostVerificationStatus.SKIPPED_DOCS_ONLY
            || verification.status() == HostVerificationStatus.SUCCEEDED) {
        // continue loop toward QA
    } else if (shouldCheapRemediate(plan, verification, hostVerifyRemediationCount, task.taskId())) {
        List<AgentStageRun> codingOnly = createHostVerifyRemediationAttempt(task.taskId());
        if (!codingOnly.isEmpty()) {
            return runInternal(..., hostVerifyRemediationCount + 1, qaRemediationCount);
        }
        return humanFailure("host verification exhausted");
    } else {
        return humanFailure(verification.errorMessage());
    }
}
```

`createHostVerifyRemediationAttempt` copies `createQaRemediationAttempts` but only for `CODING_AGENT`. If `attemptNo >= 3`, return empty list.

`shouldCheapRemediate`:

```java
return plan.hostVerifyRemediationAllowed()
        && hostVerifyRemediationCount < plan.hostVerifyMaxRemediationPasses()
        && "PRODUCT_DEFECT".equals(verification.failureCategory());
```

Inject the last BUILD/STATIC command, exit code, and 4000-char log tail into Coding's existing previous-failure prompt section.

`runInternal` gains `hostVerifyRemediationCount` next to `qaRemediationCount`. Do not change QA remediations: still max 1 and still creates Coding+QA.

- [ ] **Step 1: Failing tests**

1. Coding success + verify success → QA stage is executed.
2. Coding success + BUILD PRODUCT_DEFECT → new Coding attempt, **no** new QA attempt; `hostVerifyRemediationCount` becomes 1.
3. Two cheap remediations already used + BUILD PRODUCT_DEFECT → `NEEDS_HUMAN`, no new Coding.
4. BUILD ENVIRONMENT → `NEEDS_HUMAN`, no new Coding even if remediations remain.
5. Coding `attemptNo==3` + PRODUCT_DEFECT → human, even if remediations < 2.
6. Verify not success → QA `PENDING` is never created in this pass.

- [ ] **Step 2: Implement and run**

```bash
./mvnw -pl engine -am -Dtest=RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Commit**

```bash
git add engine
git commit -m "feat: gate QA behind host verification with two cheap coding remediations"
```

---

### Task 7: Retry provenance

**Files:**

- Modify: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskFailurePhase.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryPointResolver.java`
- Modify: provenance record if it cannot name a verification run today
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryPointResolverTest.java`

Add:

```java
HOST_VERIFY
```

Resolver: when `provenance.failurePhase() == HOST_VERIFY`, `retryFromRole = CODING_AGENT`. Require a persisted verification run id on the provenance (add `failedVerificationRunId` if the current record cannot carry it). Do not parse `errorMessage`.

If adding a provenance field, update the writers in `RequirementDeliveryEngine` / publication preflight in the same task so preview and recover stay identical (`RULE.md` §3.5.3).

```bash
./mvnw -pl engine -am -Dtest=TaskRetryPointResolverTest,TaskFailureRecoveryServiceTest,TaskRetryEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

```bash
git add engine/src/main/java/com/wish/rd/engine/retry engine/src/test/java/com/wish/rd/engine/retry
git commit -m "feat: resolve HOST_VERIFY retries from structured provenance"
```

---

### Task 8: Admin HTTP contract

**Files:**

- Create: `bootstrap/.../controller/admin/rdtask/HostVerificationController.java`
- Test: `bootstrap/.../HostVerificationControllerTest.java`
- Modify: `frontend/test/viteProxy.test.ts` only

JSON view (freeze this; frontend plan depends on it):

```json
{
  "taskId": "1",
  "runs": [
    {
      "runId": "2",
      "codingStageRunId": "3",
      "parentRunId": "",
      "attemptNo": 1,
      "status": "FAILED_NEEDS_HUMAN",
      "docsOnly": false,
      "failureCategory": "PRODUCT_DEFECT",
      "errorMessage": "npm run build exited 1",
      "remediationCount": 0,
      "createdAtEpochMillis": 0,
      "startedAtEpochMillis": 0,
      "finishedAtEpochMillis": 0,
      "steps": [
        {
          "step": "BUILD",
          "status": "FAILED",
          "commands": ["npm ci", "npm test", "npm run build"],
          "exitCode": 1,
          "durationMillis": 12000,
          "logArtifactId": "9",
          "errorMessage": "Type error"
        },
        {
          "step": "STATIC",
          "status": "SKIPPED",
          "commands": [],
          "exitCode": null,
          "durationMillis": 0,
          "logArtifactId": "",
          "errorMessage": "not executed because BUILD failed"
        }
      ],
      "artifacts": [
        {
          "artifactId": "9",
          "type": "VERIFY_BUILD_LOG",
          "name": "build.log",
          "relativePath": "verify-evidence/build/build.log",
          "contentType": "text/plain",
          "sizeBytes": 2048,
          "sha256": "ab…",
          "previewable": true,
          "contentUrl": "/admin/rd-tasks/1/host-verifications/2/evidence/9/content"
        }
      ]
    }
  ]
}
```

Endpoints:

- `GET /admin/rd-tasks/{taskId}/host-verifications`
- `GET /admin/rd-tasks/{taskId}/host-verifications/{runId}`
- `GET /admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content`

Ownership: task id on the path MUST match the artifact row. Wrong task → 404, never 200 with another task's bytes.

Profile PUT/GET add `buildCommands` and `staticCommands`. Omitted keys stay undeclared.

- [ ] **Step 1: Controller tests for list, detail, 404 ownership, profile compat**
- [ ] **Step 2: Implement**
- [ ] **Step 3: Proxy fixture**

In `frontend/test/viteProxy.test.ts` add:

```ts
"/admin/rd-tasks/7480495920010891264/host-verifications",
"/admin/rd-tasks/7480495920010891264/host-verifications/2/evidence/9/content",
```

`bypass` MUST be `undefined` so Spring Boot receives the request. `/admin/rd-tasks` proxy already prefixes these paths; do not add a new proxy key unless a test proves SPA navigation swallows them.

```bash
./mvnw -pl bootstrap -am -Dtest=HostVerificationControllerTest,QaValidationProfileControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && node --experimental-strip-types --test test/viteProxy.test.ts
```

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/wish/rd/bootstrap/controller frontend/test/viteProxy.test.ts
git commit -m "feat: expose host verification admin APIs"
```

---

### Task 9: Prompt lockstep and focused suite

**Files:**

- Modify: `RequirementAgentStageOrchestrator.roleInstructionLegacy/FactsV1` Coding section
- Test: `RequirementDeliveryEngineTest` / orchestrator prompt assertions

Coding prompt addition (Chinese, same style as existing bullets):

- 宿主会在本阶段成功后重跑安装、构建、仓库测试和静态检查。`testStatus` 只是交接信息，不是放行依据。
- 若存在上一轮宿主验证失败，只修反馈中的命令和日志，不要删 `/work/cache` 或 `node_modules`。

Do not tell QA to run BUILD/STATIC as a substitute for the host gate.

```bash
./mvnw -pl engine -am -Dtest=RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl rag,exec,bootstrap -am -Dtest=QaValidationProfileServiceTest,QaRepositoryProfileDetectorTest,HostVerificationCommandDetectorTest,HostVerificationExecutorAdapterTest,HostVerificationControllerTest,HostVerificationStoreContractTest,PostgresHostVerificationStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && node --experimental-strip-types --test test/viteProxy.test.ts
```

```bash
git add engine
git commit -m "docs: tell coding agents the host verify gate is authoritative"
```

---

## Out of scope for the backend agent

- Any React page, dialog, badge, or gallery work. See the frontend plan.
- Changing QA evidence reference rules, production-mode Next.js start, or docs-only allowlist semantics.
- Opening OpenViking as the default retrieval path.
- Raising Pi follow-up or `qaMaxRemediationPasses`.

## Spec coverage

| Spec requirement | Task |
|---|---|
| Verify after Coding, before QA; not a fifth role | 6 |
| BUILD then STATIC; BUILD failure skips STATIC; docs-only | 4, 5, 6 |
| Command sources and no self-report | 2, 4, 5 |
| Cheap remediations = 2, human for env, attempt cap 3 | 1, 6 |
| Auditable evidence + owned content API | 3, 8 |
| Existing QA protocol unchanged | 6, 9 |
