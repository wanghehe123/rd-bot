# RD-Bot Interview Remediation Full Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use test-driven development for every behavior change. Write the failing test, run it and record the expected failure, then implement the minimum production change. Do not commit or revert unrelated working-tree changes.

**Goal:** Close the P0/P1 exit gates (WP-1 through WP-6) from `2026-08-03-rd-bot-interview-remediation-execution-plan.md` with repository-backed implementation, fault tests, and real PostgreSQL/Redis/HTTP/Docker evidence. WP-0 is limited to evidence needed by WP-1 through WP-6.

> **Scope amendment (2026-08-09):** The user explicitly removed WP-7 and WP-8 from this completion run. Their task descriptions remain below as historical planning context, but they are out of scope, are not assigned to an implementer, and are not completion or review blockers. Existing unrelated WP-7/WP-8 working-tree changes must not be reverted or represented as part of this delivery.

**Architecture:** The Host remains the authority for credentials, assertion specifications, shared state, and publication decisions. PostgreSQL owns task/publication/stage-command state, Redis owns cross-instance provider health, and untrusted Pi containers receive only bounded capabilities over a controlled relay path. Each work package must be independently testable and must fail closed when its authoritative dependency is unavailable.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL/MyBatis, Redis/Redisson, Docker, Node.js Pi bridge, JUnit 5, local HTTP fixtures, Bash/Python benchmark runners.

---

## 1. Completion Contract

Completion is proved only when all of the following are true:

- [ ] WP-0 has one directly referenced success and one fault case for each in-scope WP, with non-empty run metadata.
- [ ] WP-1 identifies a remote branch by immutable operation and candidate-patch markers, atomically commits task/publication state, and resumes reconciled work without replaying remote writes.
- [ ] WP-2 keeps raw Provider/GitHub credentials out of the untrusted Pi container, restricts egress to a controlled relay, enforces role-specific mounts and resource limits, and passes real Docker fault probes.
- [ ] WP-3 compiles and freezes assertion specs on the Host, rejects missing/tampered bundles, evaluates CURRENT and REGRESSION independently from a clean replay workspace, and mirrors protocol rules in prompt, bridge, and Host validation.
- [ ] WP-4 carries the task version/fencing token from claim through every state mutation, commits snapshot and event atomically, and rejects stale workers in PostgreSQL.
- [ ] WP-5 routes only to capable Providers, persists circuit/half-open state in Redis, checks publication/side-effect state before fallback, and creates a new clean attempt on Provider change.
- [ ] WP-6 dispatches persistent stage commands through bounded `FOR UPDATE SKIP LOCKED` claims with priority, aging, project fairness, and resource quotas; a 100-task simulation proves no starvation.
- [ ] WP-7 is out of scope for this run; no completion claim is made.
- [ ] WP-8 is out of scope for this run; no completion claim is made.
- [ ] The global Definition of Done in the frozen plan is satisfied, including real HTTP/PostgreSQL/container checks and protocol synchronization.

## 2. Agent Ownership

Parallel workers must stay inside these ownership boundaries. Shared files are integrated by the primary agent after both workers stop.

| Owner | Work packages | Owned production areas | Owned tests/resources |
|---|---|---|---|
| Terra implementer | WP-1, WP-2, WP-3 | `engine/.../requirement/publication`, `engine/.../oracle`, publication portions of `RequirementDeliveryEngine`, `exec/.../pi`, `bootstrap/.../oracle`, Pi relay/controller/executor adapters, GitHub branch lookup, Pi bridge resources | Publication/oracle/Pi/GitHub tests and Pi Node tests |
| Luna implementer | WP-0 evidence for WP-1 through WP-6, WP-4, WP-6 | `rag/.../runtime`, delivery job/stage-command persistence, `bootstrap/.../threading`, `engine/.../scheduling`, QA/fault documentation | CAS/atomic PostgreSQL tests, fairness simulation, in-scope evidence references |
| Primary agent | WP-5 and integration | `engine/.../provider`, provider-fallback portions of `RequirementAgentStageOrchestrator`, `exec/.../health`, Redis health adapter/configuration, shared SQL/config/POM wiring | Provider, Redis two-instance, cross-WP and full acceptance tests |
| Sol reviewer | Final review only | Read-only review of all diffs and evidence | Requirement-by-requirement findings, no implementation edits |

Workers must not edit `RULE.md`, delete benchmark history, change unsupported metrics into successful metrics, or revert user changes. No worker creates commits, pushes, or PRs.

## 3. Wave A: Publication, Pi Security, and Host Oracle (Terra)

### Task A1: Remote publication fingerprint and replay safety

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/publication/RequirementPublicationReconcilePort.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementBranchPublisherAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementPublicationReconcileAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/ProcessGitRepairWorkspaceRepository.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/github/impl/GitHubCodePlatformAdapter.java`
- Test: existing publication, branch publisher, and GitHub adapter tests

- [ ] Add failing tests proving that an existing branch with the wrong operation or patch marker enters `NEEDS_HUMAN`, while an exact marker match skips patch application and push.
- [ ] Include both markers in the pushed commit message:

```text
RD-Bot requirement <taskId>

rd-operation-id: <operationId>
rd-candidate-patch-sha256: <candidatePatchSha256>
```

- [ ] Extend branch-head metadata to expose the remote commit message or parsed markers. A bare branch SHA without matching markers is never sufficient for `BRANCH_CONFIRMED`.
- [ ] Add timeout-after-push and concurrent replay tests using a local bare Git remote; assert one remote commit and one ledger operation.
- [ ] Run:

```bash
./mvnw -pl bootstrap,engine -am \
  -Dtest=RequirementPublicationReconciliationTest,RequirementPublicationCrashWindowAcceptanceTest,EngineRequirementBranchPublisherAdapterTest,EngineRequirementPublicationReconcileAdapterTest,GitHubCodePlatformAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task A2: Atomic publication finalization and automatic reconciliation resume

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/publication/RequirementPublicationCommitPort.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementPublicationCommitAdapter.java`
- Modify: task/publication mappers and configuration as required
- Modify: `RequirementPublicationReconcileScheduler.java`
- Test: PostgreSQL publication persistence and dispatch recovery tests

- [ ] First write a failing PostgreSQL test where task snapshot/event commit succeeds but publication commit is forced to fail; assert the whole transaction rolls back.
- [ ] Define one Host port that atomically writes the task status/result, timeline event, and publication `PR_CONFIRMED -> COMMITTED` CAS. The PostgreSQL adapter must be `@Transactional`.
- [ ] Make PostgreSQL production wiring fail closed if the commit port or ledger is unavailable.
- [ ] Add a bounded idempotent resume command keyed by `operationId`; reconciliation that reaches `BRANCH_CONFIRMED` or `PR_CONFIRMED` enqueues exactly one continuation instead of waiting for manual resubmission.
- [ ] Prove two scheduler instances cannot enqueue duplicate continuations.

### Task A3: Host-frozen assertion bundle

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/oracle/AssertionSpecCompiler.java`
- Create: `engine/src/main/java/com/wish/rd/engine/oracle/HostAssertionBundleStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/HostAssertionBundleRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/HostAssertionBundleMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresHostAssertionBundleStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/oracle/HostOwnedAssertionGate.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementExecutorAdapter.java`
- Modify: assertion configuration and tests

- [ ] Write compiler tests for explicit structured FILE, HTTP status/JSONPath, SQL read-only, LOG and browser DOM assertions. Reject unknown operators and non-read-only SQL.
- [ ] Freeze the compiled bundle before QA starts and persist `taskId`, `stageRunId`, canonical specs, content hash, scope, and version. The Agent result may echo only the Host hash and assertion result references; it cannot replace the canonical specs.
- [ ] Change the gate API to load the expected bundle from the Host store. If a non-empty Host bundle exists, a missing or mismatched echo fails validation.
- [ ] Remove Agent-controlled workspace/base URL selection. Build the evaluation context from Host-owned execution-profile/workspace records.
- [ ] Run CURRENT and REGRESSION bundles separately and require referenced evidence for both.

The store model must preserve this minimum identity:

```java
public record FrozenAssertionBundle(
        String taskId,
        String stageRunId,
        String scope,
        AssertionSpecBundle bundle,
        long version
) {}
```

### Task A4: Clean replay and complete Host runners

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/oracle/HostVerifierWorkspaceFactory.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/oracle/CleanHostVerifierWorkspaceFactory.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/oracle/LogPatternAssertionRunner.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/oracle/BrowserDomAssertionRunner.java`
- Harden: `bootstrap/src/main/java/com/wish/rd/bootstrap/oracle/JdbcSqlExistsProbe.java`
- Test: clean patch replay, SQL policy, log privacy, DOM assertion, semantic-failure integration

- [ ] Reproduce `exitCode=0` plus wrong JSONPath/SQL value as a failing integration test before implementation.
- [ ] Apply the reviewed candidate patch to a fresh verifier checkout; do not read Coding or QA residual files outside referenced artifacts.
- [ ] Restrict SQL assertions to a read-only transaction, one statement, allowed schemas, and a SELECT/CTE grammar; reject DDL/DML and comments that can smuggle a second statement.
- [ ] Implement log required/forbidden pattern plus secret/PII scanning, and browser DOM/ARIA/route-state assertions. Screenshots remain evidence, never the sole truth source.

### Task A5: Controlled Pi provider relay

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/PiCredentialRelayService.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/internal/PiCredentialRelayController.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/model/ContainerRunRequest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/ProcessContainerRunner.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/pi/impl/DockerPiAgentExecutor.java`
- Modify: `bootstrap/src/main/resources/executor/pi/src/rd-pi-bridge.mjs`
- Modify: `bootstrap/src/main/resources/executor/pi/Dockerfile`, `bootstrap/src/main/resources/executor/pi/Dockerfile.qa`, and their tests

- [ ] Write a failing bridge test proving relay mode never materializes the raw Provider secret in Pi environment, result JSON, raw events, command arguments, or artifacts.
- [ ] Replace `/redeem` with a request proxy: the untrusted Pi container sends lease-bound Provider requests; the trusted relay adds the Host credential and forwards only to the configured Provider origin.
- [ ] Run Pi on a task-local Docker internal network. A trusted relay sidecar joins both that internal network and the outbound bridge; Pi joins only the internal network. Direct public egress from Pi must fail.
- [ ] Bind the lease to task/stage/provider/expiry/maxCalls and enforce request/response byte limits, allowed methods/paths, deadline, and redacted structured audit events.
- [ ] Keep Architect/Review repository mounts read-only, Coding limited to task repo, and QA on an independent candidate-patch copy.
- [ ] Add real Docker tests for secret reads, arbitrary egress, cross-task lease use, fork bomb, memory/process limits, timeout, and host-path writes.
- [ ] After bridge changes, run `npm test` and rebuild both required Pi images from `Dockerfile` and `Dockerfile.qa`; inspect image contents for the synchronized validator.

## 4. Wave B: Task Fencing, Stage Scheduling, Retrieval, and Evidence (Luna)

### Task B1: Carry version and fencing token through the task lifecycle

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/model/RdBugFixTask.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/model/RdRequirementTask.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RdTaskStore.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RdTaskStatePersistence.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/impl/InMemoryRdTaskStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdTaskStore.java`
- Test: CAS, atomic persistence, and real PostgreSQL smoke tests

- [ ] Add failing tests showing worker A reads version/fencing token, worker B advances, and worker A is rejected without re-reading a fresh version.
- [ ] Add immutable `version` and `fencingToken` fields to persisted task snapshots. Every `with*` method carries them; successful CAS returns the incremented values.
- [ ] Convert MATERIAL, CONTEXT, PLAN, WAITING, EXECUTING, VALIDATING, PR_CREATING, REPORTING, COMPLETED/MERGED, cancel, pause, and rejection transitions to expected-version/status/fencing CAS.
- [ ] Remove status mutation from blind `upsertTask`; allow upsert only for initial creation or metadata commands that do not alter status/version.
- [ ] Make CAS snapshot change and timeline event one transaction through `RdTaskStatePersistence`.

Required PostgreSQL predicate:

```sql
WHERE id = :id
  AND version = :expected_version
  AND status = :expected_status
  AND fencing_token = :expected_fencing_token
```

- [ ] Run `PostgresRdTaskStateAtomicRealSmokeTest` in addition to module tests.

### Task B2: Persistent stage commands and bounded claims

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/model/RequirementStageCommand.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageCommandStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/job/impl/InMemoryRequirementStageCommandStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RequirementStageCommandRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageCommandStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryJobConfiguration.java`
- Modify: delivery orchestration only through a narrow next-stage execution port

- [ ] Write failing store tests for claim, heartbeat, expiry, retry, dead letter, and exactly one claimant.
- [ ] Persist one command per role/step with task version, fencing token, role, attempt, deadline, resource class, project, Provider, lease owner/until, priority, and next-visible-at.
- [ ] Claim a configured small batch atomically using:

```sql
SELECT id
FROM rd_requirement_stage_commands
WHERE status IN ('PENDING', 'FAILED_RETRYABLE')
  AND next_visible_at <= :now
ORDER BY effective_priority DESC, created_at, id
FOR UPDATE SKIP LOCKED
LIMIT :batch_size
```

- [ ] Make both `submit()` and recovery enter the same fair claim loop. No direct whole-workflow `executor.execute` path remains.
- [ ] A worker executes one bounded stage action, persists its result, then enqueues the next command. External rate limits update `next_visible_at`; workers do not sleep while holding a thread.

### Task B3: Fairness, quotas, and 100-task simulation

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/scheduling/FairRequirementDeliveryClaimPlanner.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/scheduling/FairScheduleLimits.java`
- Test: `engine/src/test/java/com/wish/rd/engine/scheduling/RequirementFairSchedulingSimulationTest.java`

- [ ] Add task resource classification for Provider, Docker, Browser QA, and lightweight Host work; remove the all-`GENERIC` fallback on known stages.
- [ ] Enforce project weighted fair queue, aging, per-project in-flight, per-Provider calls, Docker slots, and Browser slots before lease claim.
- [ ] Simulate 100 mixed tasks across at least five projects and three priorities with 30-minute to two-hour virtual durations. Assert every active project receives service, P0 latency is bounded, aging eventually serves P2, queue size stays bounded during 429/5xx, and retries do not grow exponentially.
- [ ] Persist/emit queue age P50/P95, oldest age, project wait ratio, lease lost, retries, resource utilization, and rejections.

### Task B4: Opt-in iterative retrieval with persisted round audit

**Out of scope for the 2026-08-09 P0/P1 completion run. Retained as historical planning context only.**

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/retrieval/DeepRetrievalOrchestrator.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementContextRetrievalRecorder.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retrieval/iterative/IterativeRetrievalPolicy.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retrieval/iterative/RetrievalRoundAuditStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRetrievalRoundAuditStore.java`
- Test: orchestrator, recorder, scope-boundary, and stop-reason tests

- [ ] Add a default-off production policy selected by project/task configuration; single-pass remains the default.
- [ ] Persist query, candidates, selected evidence, missing evidence types, information gain, cumulative tokens/time, and stop reason for every round.
- [ ] Enforce tenant/project/repository scope as an immutable filter across rewrites.
- [ ] Stop on evidence gate satisfied, two consecutive zero-gain rounds, max rounds, token/time budget, or clarification required. Every completion has a non-empty `stopReason`.

### Task B5: Offline retrieval evaluation and interview evidence package

**Out of scope for the 2026-08-09 P0/P1 completion run. Retained as historical planning context only.**

**Files:**
- Create: versioned evaluation dataset under `benchmarks/interview-claims/datasets`
- Create: deterministic runner and HTML report generator under `benchmarks/interview-claims/runners`
- Modify: `metrics.json`, QA plan, fault matrix, package verifier
- Create: one immutable `raw/<run-id>` and matching `reports/<run-id>.html`

- [ ] First add tests for Recall@K, reciprocal rank, NDCG, evidence coverage, refusal correctness, latency aggregation, and confidence/variation output.
- [ ] Run single-pass and iterative modes against the same frozen dataset and record every query/result, not only aggregate metrics.
- [ ] Record `gitCommit`, dirty-tree hash, config hash, dataset version, model/temperature when applicable, budget, sample count, run count, timestamps, commands, and tool versions.
- [ ] Execute one success and one failure-injection command for WP-1 through WP-7 and reference raw logs from the Q1-Q12 acceptance matrix.
- [ ] Generate HTML strictly from raw JSON. `verify-package.sh` recomputes hashes/aggregates and fails on unsupported resume claims.
- [ ] Update `resume_optimized.md` conservatively: remove or label claims that lack a matching non-zero raw run. Preserve unrelated user edits.

## 5. Wave C: Provider Routing and Distributed Health (Primary Agent)

### Task C1: Shared circuit state and single half-open probe

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/health/ModelHealthStateStore.java`
- Refactor: `exec/src/main/java/com/wish/rd/exec/repair/health/ModelHealthStore.java` to depend on the port
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/provider/RedisModelHealthStateStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/provider/ModelHealthStoreConfiguration.java`
- Test: unit state machine and two-instance Redis integration

- [ ] Write failing tests showing two `ModelHealthStore` instances share OPEN state and only one acquires HALF_OPEN.
- [ ] Store failure count, open-until, state, version, and half-open lease in Redis with one atomic Lua transition. Production configuration must not silently fall back to JVM state.
- [ ] Release or expire the half-open lease on success, failure, timeout, and process death.
- [ ] Keep an explicit in-memory implementation for unit tests and `rd.knowledge.store=memory` only.

### Task C2: Side-effect ledger preflight and clean Provider attempts

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/provider/ProviderFallbackGate.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/provider/ProviderFallbackPolicyEnforcer.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`
- Create: `engine/src/main/java/com/wish/rd/engine/provider/ProviderSideEffectStatusPort.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/PublicationProviderSideEffectStatusAdapter.java`
- Test: unknown publication, duplicate tool effect, new attempt, and clean workspace cases

- [ ] Evaluate fallback before invoking the alternate Provider. For tool or side-effect work, `UNKNOWN_REMOTE_RESULT`, uncommitted tool operations, or missing ledger evidence returns `WAITING_POLICY`/human.
- [ ] A Provider change creates a new persisted attempt ID and a fresh output/workspace. A stage ID plus attempt number is not accepted as proof of cleanliness.
- [ ] High-risk database, migration, security, permission, and publication work cannot fall back to a weaker capability profile without explicit approval.
- [ ] All Provider results pass the same schema, Host Oracle, and Delivery Review.

## 6. Wave D: Integration and Acceptance (Primary Agent)

### Task D1: Shared wiring and protocol consistency

- [ ] Integrate shared SQL, Spring configuration, `application.yaml`, POM changes, and constructors after both workers finish.
- [ ] Keep high-risk controls safe by default. Startup must fail or emit a prominent warning when a required production control is disabled.
- [ ] Compare Prompt, `rd-pi-bridge.mjs`, `result-tool.mjs`, and Host validators field-by-field.
- [ ] Scan the diff for raw secrets, unbounded loops, blind upserts, JVM shared-state fallbacks, and broad SQL/network permissions.

### Task D2: Required verification

Run and preserve logs for:

```bash
cd bootstrap/src/main/resources/executor/pi && npm test

./mvnw -pl engine -am \
  -Dtest=RequirementDeliveryEngineTest,RequirementDeliveryReviewerTest,RequirementPublicationReconciliationTest,RequirementPublicationCrashWindowAcceptanceTest,ProviderFallbackGateTest,ProviderFallbackPolicyEnforcerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl exec -am \
  -Dtest=DockerPiAgentExecutorTest,QaEvidenceBundleValidatorTest,ModelHealthStoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl rag,bootstrap -am \
  -Dtest=RdTaskFencingConcurrencyTest,PostgresRdTaskStateAtomicRealSmokeTest,RequirementDeliveryDispatchServiceTest,RequirementFairSchedulingSimulationTest,HostOwnedAssertionGateTest,HostAssertionOracleWiringTest,PiCredentialRelayControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw test
```

- [ ] Rebuild `rd-bot/pi-agent:local` and `rd-bot/pi-agent-qa:local`, then run the real Docker isolation suite against those exact image IDs.
- [ ] Start the local application with PostgreSQL and Redis and execute real HTTP success, state-changing, and read-back requests. Record status, key fields, and database side effects.
- [ ] Run the local bare-Git/mock-GitHub crash matrix and the 100-task virtual-time simulation.
- [ ] Record the final in-scope test/acceptance logs against the base SHA and final dirty-tree hash.

### Task D3: Independent Sol review and remediation loop

- [ ] Give the reviewer the frozen plan, base SHA, current diff, test logs, Docker image IDs, HTTP evidence, raw benchmark run, and HTML report.
- [ ] Reviewer reports findings first, ordered by severity and linked to exact files/lines.
- [ ] Fix every Critical and Important finding with a new failing test, rerun the affected wave, and request one final read-only re-review.
- [ ] Mark the goal complete only when the reviewer finds no blocking issue and every Completion Contract checkbox has direct evidence.

## 7. Stop Conditions

Do not claim completion when any of these remains true:

- A green unit test is the only evidence for a real Docker, HTTP, PostgreSQL, Redis, or multi-instance requirement.
- Pi receives a raw long-lived credential or can reach arbitrary public destinations.
- Agent output can define, replace, disable, or relocate the Host assertion bundle.
- Any task status path uses blind upsert or re-reads the latest version immediately before a stale worker write.
- `submit()` bypasses fair persistent stage claims, or recovery scans an unbounded set.
- Provider health is a production `ConcurrentHashMap`, or two instances can both half-open probe.
- An in-scope completion claim lacks direct success and fault evidence.

## 8. Final Sol Review Remediation Wave (2026-08-09)

The first post-implementation Sol review returned `fix-first`. The following items are part of
WP-2 through WP-6 and must be closed before the final completion claim. They do not reopen the
cancelled WP-7 or WP-8 scope.

### Task E1: Make the controlled Pi relay mandatory for credentialed execution

- [ ] Remove every path that injects a raw Provider credential into an untrusted Pi container.
- [ ] Make relay-backed internal networking the safe production default. If a credentialed Pi
  execution cannot obtain the relay/network capability, fail closed before container creation.
- [ ] Add default-configuration and explicitly-disabled relay tests proving the raw credential is
  absent from environment, arguments, inspect data, artifacts, and bridge traffic.
- [ ] Rebuild both Pi images and rerun the real Docker isolation suite against the new image IDs.

### Task E2: Make Host assertions reachable and bind HTTP/browser checks to clean replay

- [ ] Extend the requirement-creation contract with a backward-compatible structured assertion
  bundle field while retaining human-readable acceptance criteria.
- [ ] Persist the structured bundle as Host-owned task input and freeze it before QA. Missing,
  malformed, or tampered structured input must fail closed when Host assertions are requested.
- [ ] For HTTP/browser assertions, start the configured application from the clean replay checkout,
  derive the probe URL from that runtime, enforce startup timeout/readiness, and always clean up.
  Never validate a candidate patch against an unrelated pre-existing service URL.
- [ ] Add public API-to-freeze integration coverage and a semantic test whose patched replay service
  differs from the pre-existing configured service.

### Task E3: Close Provider fallback authority gaps

- [ ] Remove the all-circuits-open fallback bypass. Every Provider invocation, including the last
  candidate path, must pass the shared `allowCall`/HALF_OPEN lease decision.
- [ ] Introduce a durable, authoritative tool-operation ledger for side-effecting attempts. Missing,
  unknown, or uncommitted operation evidence blocks fallback; an empty workspace is not evidence.
- [ ] Prove with two shared-state instances that only one HALF_OPEN probe executes, and prove a
  side effect recorded before publication blocks a second Provider attempt.

### Task E4: Atomically finalize a stage and schedule its continuation

- [ ] Define one transactional Host port that validates the running command lease, persists the task
  stage outcome, completes the current stage command, and enqueues the next command (or terminal
  outcome) as one PostgreSQL transaction.
- [ ] Recovery must repair or deterministically resume every crash point; it must not dead-letter a
  command merely because its task mutation committed before command completion.
- [ ] Completion CAS failure must stop continuation enqueue. No exception-swallowing path may create
  the next command after the current command failed to finalize.
- [ ] Add fault-injection tests for each write boundary plus a real PostgreSQL crash-window smoke.

### Task E5: Repeat acceptance and final review

- [ ] Rerun all affected focused suites, Pi Node tests, real Docker, real PostgreSQL, real Redis, the
  HTTP read-back sequence, and the full Maven reactor. Keep cancelled WP-7/WP-8 failures explicit.
- [ ] Recompute implementation and evidence digests after all files stop changing.
- [ ] Request a fresh read-only `sol_advisor_sol_reviewer` review. Any Critical or Important finding
  reopens this remediation wave.
