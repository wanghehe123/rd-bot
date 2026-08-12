# Checkpoint-Bound Retry Dispatch Implementation Plan

> **Status (2026-08-13):** Happy-path role → QA → GitHub PR is live-verified
> (`7493354884037742592`, PR #9). Provenance, preview=snapshot, and GitHub 422-as-absent
> are recorded in
> `docs/superpowers/specs/2026-08-13-requirement-publication-preflight-and-retry-provenance-spec.md`.
> Remaining open work is the full HTTP/concurrency/crash matrix in later tasks, not the
> docs-only delivery path. Do not treat the archived
> `2026-08-12-checkpoint-bound-retry-handoff.md` as an operator runbook.

> **For agentic workers:** implement this plan task-by-task with TDD. Do not start the next task until the focused tests for the current task pass and the diff has been reviewed for the frozen invariants below.

**Goal:** Make every requirement-task business retry an exact, crash-safe PostgreSQL generation bound to one durable checkpoint, one new stage-command generation, and exact role/retrieval/AI-review attempts.

**Architecture:** Replace `TaskRetryEngine -> RECOVERING -> create attempts -> DISPATCHED -> generic submit` with a checkpoint-idempotent initialization transaction. It validates the immutable failure provenance, moves the task to `RECOVERING`, persists every attempt known at initialization, inserts one `RECOVERING`-bound business-generation command, and commits the checkpoint as `DISPATCHED`. It does **not** restore a normal predecessor state. After commit the dispatcher only wakes that command. The checkpoint-bound execution plan performs the first recovery transition, every continuation propagates generation identity, and all normal producers refuse to mint a normal generation while an active retry checkpoint exists.

**Tech Stack:** Java 21 records/enums, Spring Boot 3.5, MyBatis-Plus, PostgreSQL transactions/advisory locks/composite foreign keys/partial unique indexes, JUnit 5, Mockito, and real PostgreSQL HTTP/concurrency smoke tests.

---

## Frozen invariants

1. The business idempotency key owns one durable checkpoint/generation and one first command. Competing callers may propose different Snowflake IDs; the loser validates only source/business/route input and returns the winner's persisted identities.
2. `RequirementStageCommand.attemptNo` is only the technical lease retry counter. A business retry always mints a new command row and never revives an old `SUCCEEDED`, `DEAD_LETTERED`, or `CANCELLED` command.
3. Source identity is `(taskId, status, version, fencingToken)`, never update time. Dispatch identity is the post-`RECOVERING` `(version, fencingToken)` stored on checkpoint and command. Legacy checkpoints with fence `0` are audit-only and can never authorize execution.
4. The initialization transaction commits the task in `RECOVERING`; it never performs `RECOVERING -> CREATED` or exposes a normal predecessor status before the exact retry command is durable. `nextStageFor(RECOVERING)` remains fail-closed.
5. All generic submit/recovery/umbrella producers check for an active exact checkpoint. While it is `CREATED`, `DISPATCHED`, or `WAITING_APPROVAL`, they may only wake its persisted command or no-op; they never enqueue a normal generation, even after a retry plan has advanced the task to `EXECUTING`, `VALIDATING`, or another ordinary status.
6. Every retry command references the checkpoint/generation. Commands that consume attempts also reference one exact primary binding: role/RAG targets `AGENT_STAGE`, while direct AI review targets `AI_REVIEW`. Infrastructure/policy stages have no fabricated attempt binding. RAG resolves its exact child retrieval binding through the same-checkpoint parent role binding.
7. Binding rows are immutable and the set is append-only. MATERIAL/CONTEXT/PLAN/POLICY initialization precreates every future role from `REQUIREMENT_REVIEWER` through QA; role/RAG retries precreate every role from `retryFromRole`; direct RAG and direct AI review also bind their known child/target. A later retrieval or AI-review child may be added only once through an exact `(checkpointId, parentBindingId, kind, ordinal)` append transaction; execution never selects `latest(role)` or mints an unbound child.
8. Existing successful upstream attempts are immutable. Old non-terminal failed/downstream attempts are CAS-terminalized before new attempts and bindings are inserted.
9. A role retry reuses only an APPLIED policy from the same task and frozen-plan digest. The normal first-role task-version/fence authorization remains unchanged; only an exact checkpoint lineage may authorize the retry generation's new fence.
10. A POLICY retry never mutates an APPLIED or DENIED generation. It locks the exact source; an active stuck `PLAN_READY/POLICY_DECIDED/WAITING_APPROVAL/APPROVED` generation is CASed to terminal `SUPERSEDED`, while immutable terminal `DENIED` remains unchanged. In either eligible case one new `PLAN_READY` generation is inserted from the frozen plan before the FK-bound `POLICY_EVALUATE` command.
11. The exact checkpoint stays `DISPATCHED` through intermediate execution. A policy wait moves it to stable `WAITING_APPROVAL`; approval atomically returns it to `DISPATCHED`. Only a true terminal outcome settles it, always by `checkpointId`, never `findActiveByTask`.
12. Every new terminal task failure has one structured durable provenance row written in the same transaction as command/policy disposition and task failure. Business-rejected commands, technical exhaustion before outcome recording, policy-control exhaustion, and publication failure are all resolvable without inferring from command status/timing.
13. No transaction calls a model, retrieval network, Git/provider, Docker, or workspace recovery while holding database locks. A committed `PENDING` command remains recoverable if JVM scheduling never happens.

## Recovery route matrix

Initialization always leaves the task at `RECOVERING`. The first command is fenced to that snapshot. The first committed recovery mutation belongs to the exact command plan or policy control transaction:

| Failure provenance | First command | First committed mutation chain | Primary/associated target |
| --- | --- | --- | --- |
| durable `MATERIAL_COLLECTING` failure | `MATERIAL_COLLECTING` | `RECOVERING -> MATERIAL_COLLECTING` | future reviewer-through-QA roles pre-bound |
| durable `MATERIAL_READY` failure | `MATERIAL_READY` | `RECOVERING -> MATERIAL_COLLECTING -> MATERIAL_READY` | future reviewer-through-QA roles pre-bound |
| durable `CONTEXT_BUILDING` failure | `CONTEXT_BUILDING` | `RECOVERING -> CONTEXT_BUILDING` | future reviewer-through-QA roles pre-bound |
| durable `CONTEXT_READY` failure | `CONTEXT_READY` | `RECOVERING -> CONTEXT_BUILDING -> CONTEXT_READY` | future reviewer-through-QA roles pre-bound |
| durable `PLAN_GENERATING` failure | `PLAN_GENERATING` | `RECOVERING -> PLAN_GENERATING` | future reviewer-through-QA roles pre-bound |
| durable `PLAN_GENERATED` failure | `PLAN_GENERATED` | `RECOVERING -> PLAN_GENERATING -> PLAN_GENERATED` | future reviewer-through-QA roles pre-bound |
| exact retryable POLICY ledger/control failure | `POLICY_EVALUATE` | policy transaction: `RECOVERING -> WAITING_POLICY -> ...` | new `PLAN_READY` run + reviewer-through-QA roles |
| RAG failure | `ROLE_EXECUTION:<retryFromRole>` | `RECOVERING -> EXECUTING`, then role snapshot | AgentStage binding + its Retrieval child |
| agent-role failure | `ROLE_EXECUTION:<retryFromRole>` | `RECOVERING -> EXECUTING`, then role snapshot | exact AgentStage; downstream roles pre-bound |
| deterministic review | `DETERMINISTIC_REVIEW` | `RECOVERING -> EXECUTING -> VALIDATING` | none |
| AI review with role rollback | `ROLE_EXECUTION:<retryFromRole>` | `RECOVERING -> EXECUTING`, then downstream flow | role bindings; AI child appended once later |
| direct AI review | `AI_REVIEW` | `RECOVERING -> VALIDATING` | exact AiReviewRun |
| PR publication | `PUBLICATION:<operationId>` | `RECOVERING -> PR_CREATING`, then reconciliation | immutable publication operation/receipt |

The transition graph may add only missing `RECOVERING -> in-progress` edges required above (currently `PLAN_GENERATING` is the known gap). It must never add `RECOVERING -> CREATED`, `MATERIAL_READY`, `CONTEXT_READY`, or `PLAN_GENERATED` merely to reuse generic routing. Recovery-only plan construction must require a valid checkpoint before emitting these mutations.

## Transaction lock matrix

| Owner | Required order |
| --- | --- |
| retry initialization | idempotency-key advisory lock -> source policy (if any) -> task -> checkpoint -> old attempts -> new attempts/bindings -> new command -> checkpoint dispatch update |
| policy control | policy run -> task -> checkpoint -> command/continuation -> bindings/unused attempts |
| generic finalizer | running command -> finalization marker -> task -> checkpoint -> bindings/continuation |
| append-once child attempt | checkpoint -> parent binding -> child attempt -> child binding |

The failed command/finalization marker must already be terminal and is read as immutable provenance before the task lock. Initialization must not lock a live command after locking task/checkpoint. Replay reads the winner command after the checkpoint decision without `FOR UPDATE`; no path may invert `command -> task` by waiting on a running command.

### Task 0: Reconcile the protected retry rule before implementation

**Files:**
- Modify: `RULE.md`
- Modify: `docs/superpowers/specs/2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md`

- [ ] **Step 1: Amend both affected RULE 3.5.3 clauses without weakening normal delivery.** Preserve `rd_requirement_delivery_jobs` as the durable truth for umbrella/normal delivery submission. Explicitly define a checkpoint-bound stage retry as the narrow alternative whose durable dispatch truth is the `rd_requirement_stage_commands` row written in the same transaction as checkpoint/task/attempt bindings; it need not mint a second umbrella job unless that runtime path already requires one. Also replace the ambiguous “retry preparation or dispatch failure” sentence with two verifiable cases: (a) any initialization failure before the atomic commit fully rolls back checkpoint/task/attempt/binding/policy/command writes, so the task is not left `RECOVERING`; (b) after commit, local scheduler rejection is not a preparation failure—the durable `DISPATCHED` checkpoint, `RECOVERING` task, and `PENDING` command remain authoritative and must be recovered by exact command ID, not compensated. Compensation is allowed only as one atomic transaction when the durable command is proven unusable.
- [ ] **Step 2: Mirror both dispatch truth and commit-boundary distinctions in the retry design spec.** State which path owns `rd_requirement_delivery_jobs`, which path owns checkpoint-bound `rd_requirement_stage_commands`, the commit boundary, recovery owner, and required evidence tests. Cite the exact transaction/dispatcher paths from this plan.
- [ ] **Step 3: Verify the protected text and formatting.**

```bash
rg -n "delivery_jobs|stage_commands|初始化事务提交前|本地调度|PENDING|RECOVERING" RULE.md docs/superpowers/specs/2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md
git diff --check -- RULE.md docs/superpowers/specs/2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md
```

### Task 1: Persist and resolve exact failure provenance

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryPoint.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCommand.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryPointResolver.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryFailureProvenanceStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryFailureProvenance.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/TaskFailureRecoveryService.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageFinalizationMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/TaskFailureProvenanceRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskFailureProvenanceMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryFailureProvenanceStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/impl/InMemoryTaskRetryFailureProvenanceStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryPointResolverTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskFailureRecoveryServiceTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryFailureProvenanceStoreTest.java`

- [ ] **Step 1: Write RED tests for every failure writer shape.** A deterministic-review command may be `SUCCEEDED` while the durable business outcome is `REJECTED`; a technical exception may leave a PREPARED marker while the command is `DEAD_LETTERED`; policy-control exhaustion may have no generic finalization marker. In all cases the resolver must select the structured provenance's exact `commandId/stage`, not command status/timing. Conflicting provenance or a coarse phase without one exact row must throw `RETRY_POINT_AMBIGUOUS`.
- [ ] **Step 2: Define immutable provenance.**

```java
public record TaskRetryFailureProvenance(
        String provenanceId,
        String taskId,
        String failedStageCommandId,
        int failedCommandAttemptNo,
        String failedStage,
        TaskFailurePhase failurePhase,
        RdTaskStatus outcomeStatus,
        long failedTaskVersion,
        long failedTaskFencingToken,
        String failedStageRunId,
        String failedRetrievalRunId,
        String failedAiReviewRunId,
        String sourcePolicyRunId,
        String sourcePlanDigest,
        String publicationOperationId,
        String failureKind,
        long recordedAtEpochMillis
) { }
```

The three attempt IDs come from the exact finalization/policy execution context rather than a later `latest(...)` lookup. `sourcePolicyRunId/sourcePlanDigest` come from the exact policy ledger; `publicationOperationId` comes from the publication ledger/receipt, not free-form error text. The row is written atomically when the task enters its failed snapshot and is unique for `(taskId,failedTaskVersion,failedTaskFencingToken)` and `(failedStageCommandId,failedCommandAttemptNo)`.
- [ ] **Step 3: Extend `TaskRetryPoint` and stale guards.** Add `sourceFencingToken`, `failedStageCommandId`, `failedStage`, `sourcePolicyRunId`, `sourcePlanDigest`, and `publicationOperationId`. Add optional expected command/stage/fence fields to `TaskRetryCommand` after existing constructor-compatible fields. Use `task.version()`/`task.fencingToken()`; remove every update-time-as-version fallback.
- [ ] **Step 4: Add mapper/store queries that return only exact durable provenance.** Require its failed task version/fence/status to equal the current source snapshot, then corroborate policy/stage/retrieval/AI/publication ledgers. Existing FINALIZED markers may be migrated into provenance; a new retry never infers the winner solely from stage-command status/timing.
- [ ] **Step 5: Run focused tests.**

```bash
./mvnw -pl engine -am -Dtest=TaskRetryPointResolverTest,TaskFailureRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=PostgresTaskRetryFailureProvenanceStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 2: Define checkpoint, route, generation, and binding identities

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCheckpoint.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryCheckpointStatus.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryAttemptKind.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryAttemptBinding.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryRoute.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryRoutePlanner.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryAttemptBindingStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/impl/InMemoryTaskRetryAttemptBindingStore.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/job/model/RequirementStageCommand.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageCommandStore.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/job/impl/InMemoryRequirementStageCommandStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementStageCommandFactory.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryRoutePlannerTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/job/RequirementStageCommandStoreTest.java`

- [ ] **Step 1: Write RED route tests for every matrix row.** Each route requires exact provenance and always declares `RECOVERING` as initial expected status. Missing role, source policy/digest, publication operation, or exact sub-stage fails before persistence.
- [ ] **Step 2: Extend checkpoint identity.** Persist source status/version/fence, failed command/stage, `sourcePolicyRunId`, authorization/new `policyRunId`, immutable `sourcePlanDigest`, fill-once `authorizationPlanDigest`, `publicationOperationId`, `dispatchTaskVersion`, `dispatchFencingToken`, `dispatchCommandId`, and `businessGeneration`. Add stable `WAITING_APPROVAL`. `businessGeneration` equals the durable checkpoint Snowflake ID; proposed IDs are not part of replay equivalence. Early MATERIAL/CONTEXT/PLAN retries may start with blank authorization policy/digest, but only the PLAN_GENERATED finalizer may CAS both from blank to the exact newly prepared policy generation/digest; replay must match them thereafter.
- [ ] **Step 3: Define immutable, parent-aware bindings.**

```java
public record TaskRetryAttemptBinding(
        String bindingId,
        String checkpointId,
        TaskRetryAttemptKind kind,
        AgentRole role,
        String attemptId,
        String parentBindingId,
        int attemptNo,
        int ordinal
) { }
```

An `AGENT_STAGE` binding has no parent. A RAG/AI child references its same-checkpoint parent role binding. A direct AI-review target may have no parent.
- [ ] **Step 3a: Define checkpoint-scoped binding lookup.** The store exposes `findById`, ordered `listByCheckpoint`, primary lookup by checkpoint/kind/role, and child lookup by checkpoint/parent/kind/ordinal. The in-memory implementation enforces the same immutable slot and target-at-most-once rules as PostgreSQL.
- [ ] **Step 4: Extend command identity.** Add `retryCheckpointId`, `long businessGeneration`, and `targetRetryBindingId`; normal commands use blank/`0`/blank, retry commands require nonblank/positive/nonblank except infrastructure stages with no attempt target. Add exact `find(taskId, role, stage, retryCheckpointId)` and forbid legacy three-field lookup in retry code.
- [ ] **Step 5: Prove normal and retry commands coexist.** A terminal normal command and a new retry command for the same `(task,role,stage)` must have different IDs. Technical reclaim increments only `attemptNo`.
- [ ] **Step 6: Run focused tests.**

```bash
./mvnw -pl engine -am -Dtest=TaskRetryRoutePlannerTest,RequirementStageCommandStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 3: Add policy supersession and checkpoint-aware policy contracts

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/model/RequirementPolicyRunState.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/model/RequirementPolicyRun.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/RequirementPolicyTransactionPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/policy/model/RequirementPolicyRetryContext.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/RequirementPolicyRunStore.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/impl/InMemoryRequirementPolicyRunStore.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/impl/InMemoryRequirementPolicyTransactionAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementPolicyRunStore.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/policy/RequirementPolicyRunTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/policy/impl/InMemoryRequirementPolicyTransactionAdapterTest.java`

- [ ] **Step 1: Write RED state tests.** Exact `PLAN_READY`, `POLICY_DECIDED`, `WAITING_APPROVAL`, or `APPROVED` source generations may CAS to `SUPERSEDED` only for an authorized POLICY retry. `APPLIED`, `DENIED`, and already `SUPERSEDED` cannot be superseded. A DENIED policy may be the immutable source of a later operator-authorized new generation without changing the old row; APPLIED may authorize role retry but never POLICY reevaluation.
- [ ] **Step 2: Add terminal `SUPERSEDED`.** Exclude it from active policy lookup and preserve ledger immutability/version CAS. Do not weaken normal transitions.
- [ ] **Step 3: Add optional immutable retry context to policy commands/results.** It carries checkpoint ID, business generation, target binding, source policy run/digest, and expected checkpoint status. Normal policy operations keep an explicit empty context.
- [ ] **Step 4: Run policy domain tests.**

```bash
./mvnw -pl engine -am -Dtest=RequirementPolicyRunTest,InMemoryRequirementPolicyTransactionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 4: Migrate PostgreSQL with same-checkpoint composite constraints

**Files:**
- Modify: `bootstrap/src/main/resources/sql/postgres/p1_multi_agent_orchestration.sql`
- Modify: `bootstrap/src/main/resources/sql/postgres/p3_task_retry_ai_review.sql`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/TaskRetryCheckpointRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/TaskRetryAttemptBindingRow.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RequirementStageCommandRow.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskRetryCheckpointMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskRetryAttemptBindingMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageCommandMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementPolicyRunMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskFailureProvenanceMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStore.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryAttemptBindingStore.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageCommandStore.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresMigrationOrderPolicyTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRetrySchemaConstraintRealSmokeTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryCheckpointStoreTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskRetryAttemptBindingStoreTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageCommandStoreTest.java`

- [ ] **Step 1: Write RED real-PostgreSQL migration tests.** Execute P1/P3 twice and inspect `pg_constraint`/`pg_indexes`. Also attempt cross-checkpoint command/binding references and require FK rejection.
- [ ] **Step 2: Add structured failure provenance and checkpoint columns safely.** Create `rd_task_failure_provenance` with typed task/command/policy FKs, exact command attempt/stage/phase, failed task status/version/fence, plan digest, publication operation, failure kind, and immutable timestamps; all audit FKs use `ON DELETE RESTRICT`. Backfill only unambiguous FINALIZED markers and exact terminal command/task-event joins; leave ambiguous legacy failures unauthorizable. Historical checkpoints receive `source_fencing_token = 0` and `business_generation = 0` (or nullable during migration), never a fabricated fence. New initialization rejects `<= 0` and writes `business_generation = id`; enforce `((source_fencing_token = 0 AND business_generation = 0) OR (source_fencing_token > 0 AND business_generation = id))`. Add exact provenance, source/new policy IDs, source/authorization plan digests, publication operation, and dispatch pair. Add a CAS mapper for the sole blank-to-exact authorization policy/digest bind. Replace the active index with a **unique** partial index on `task_id WHERE status IN ('CREATED','DISPATCHED','WAITING_APPROVAL')`. Add task/policy FKs with `ON DELETE RESTRICT`.
- [ ] **Step 3: Create a typed, parent-aware binding table after all target tables exist.** Persist three nullable columns `stage_run_id`, `retrieval_run_id`, and `ai_review_run_id`; add a kind/exactly-one CHECK and individual typed FKs with `ON DELETE RESTRICT`. Add unique `(id,checkpoint_id)`, partial target-at-most-once indexes, a top-level partial unique `(checkpoint_id,binding_kind,role,ordinal) WHERE parent_binding_id IS NULL`, a child partial unique `(checkpoint_id,parent_binding_id,binding_kind,role,ordinal) WHERE parent_binding_id IS NOT NULL`, and composite same-checkpoint parent FK `(parent_binding_id,checkpoint_id) -> (id,checkpoint_id)`. The Java `attemptId` is mapped to exactly one typed column by `kind`; it is never used as a polymorphic SQL FK.
- [ ] **Step 4: Migrate command generation.** Add retry checkpoint, generation, and target binding columns. Replace the legacy identity index with:

```sql
CREATE UNIQUE INDEX uk_rd_requirement_stage_commands_normal_identity
    ON rd_requirement_stage_commands(task_id, role, stage)
    WHERE retry_checkpoint_id IS NULL;
CREATE UNIQUE INDEX uk_rd_requirement_stage_commands_retry_identity
    ON rd_requirement_stage_commands(task_id, role, stage, retry_checkpoint_id)
    WHERE retry_checkpoint_id IS NOT NULL;
```

Add unique `(id,retry_checkpoint_id)` and a direct `retry_checkpoint_id -> checkpoint.id ON DELETE RESTRICT` FK so targetless infrastructure/policy commands cannot be orphaned. Enforce checkpoint dispatch with composite FK `(dispatch_command_id,id) -> stage_command(id,retry_checkpoint_id)` and command target with `(target_retry_binding_id,retry_checkpoint_id) -> binding(id,checkpoint_id)`. Add checks `checkpoint.business_generation = checkpoint.id` for new verified rows and `command.business_generation = command.retry_checkpoint_id` for retry rows; normal generation is `0`. All audit links are `ON DELETE RESTRICT`.
- [ ] **Step 5: Update policy constraints.** Fresh and upgraded schemas accept terminal `SUPERSEDED`; its row retains historical approval metadata, so approval-state checks must explicitly allow that terminal audit snapshot. Recreate `uk_rd_requirement_policy_runs_one_active` so only `PLAN_READY/POLICY_DECIDED/WAITING_APPROVAL/APPROVED` are active.
- [ ] **Step 6: Implement semantic replay stores.** Immutable row mismatch fails closed. Binding order is `(ordinal,id)`. Add parent lookup and checkpoint-scoped primary/child lookup.
- [ ] **Step 7: Run migration/persistence tests.**

```bash
./mvnw -pl bootstrap -am -Dtest=PostgresMigrationOrderPolicyTest,PostgresRetrySchemaConstraintRealSmokeTest,PostgresTaskRetryCheckpointStoreTest,PostgresTaskRetryAttemptBindingStoreTest,PostgresRequirementStageCommandStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 5: Implement the single checkpoint-idempotent initialization transaction

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/retry/RequirementRetryDispatchTransactionPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/InitializeRequirementRetryCommand.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/RequirementRetryDispatchResult.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementRetryDispatchTransactionAdapter.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/impl/InMemoryRequirementRetryDispatchTransactionAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdAgentStageRunMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdRagRetrievalRunMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/AiReviewRunMapper.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementRetryDispatchTransactionAdapterTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementRetryDispatchRealSmokeTest.java`

- [ ] **Step 1: Write RED write-order, rollback, and different-ID concurrency tests.** Two transactions use the same business idempotency key but different proposed checkpoint/attempt/binding/command IDs. Exactly one wins; the loser returns the winner's durable IDs after comparing source identity, route, note/evidence hash, and policy/publication provenance.
- [ ] **Step 2: Define transaction input/output.** Input carries immutable source task/provenance, business idempotency key, route scope, proposed IDs/attempts/bindings, frozen plan, and time; it does **not** carry an authoritative list of old non-terminal attempts. MATERIAL/CONTEXT/PLAN/POLICY plans include proposed reviewer-through-QA role attempts; RAG/role plans include `retryFromRole` through QA. Proposed IDs are creation hints, not replay identity. Result returns the persisted checkpoint, first command, bindings, and `replayed`.
- [ ] **Step 3: Implement canonical order.** Acquire a transaction-scoped advisory lock derived from the idempotency key. Validate durable provenance before task lock. Lock source policy when applicable, then exact task, revalidate source status/version/fence, and insert-or-resolve checkpoint. Under the task lock, query and lock **all** current non-terminal AgentStage/Retrieval/AiReview attempts in the route scope ordered by `(kind,id)`; compare against the planned replacement scope, fail on any unaccounted row, then CAS-terminalize that locked set. CAS task to `RECOVERING`, insert every route-required attempt/binding, insert a command fenced to the resulting `RECOVERING` version/fence, fill `dispatch_command_id`, and transition checkpoint `CREATED -> DISPATCHED`. Do not trust a lock-free old-attempt snapshot and do not perform a route pre-status transition.
- [ ] **Step 4: Handle POLICY retry in the same transaction.** Lock exact source ledger and verify source plan digest. CAS an eligible active stuck source to `SUPERSEDED`; preserve an eligible terminal DENIED source unchanged. Then use `RequirementPolicyEvaluationPreparationSupport` to insert the new `PLAN_READY` run before `POLICY_EVALUATE`. APPLIED role authorization is validated but never superseded or used as a POLICY reevaluation source.
- [ ] **Step 5: Make half-state invisible.** `CREATED` is transaction-internal; a failed transaction leaves no checkpoint, task event, attempt, binding, policy supersession, or command. A committed initialization is already `DISPATCHED`. Legacy visible `CREATED` rows with fence `0` fail closed and require explicit repair, not automatic authorization.
- [ ] **Step 6: Add synchronized in-memory parity.** It must restore snapshots on injected failure and reproduce winner-ID replay semantics.
- [ ] **Step 7: Run focused tests.**

```bash
./mvnw -pl bootstrap -am -Dtest=PostgresRequirementRetryDispatchTransactionAdapterTest,PostgresRequirementRetryDispatchRealSmokeTest,PostgresRdTaskStateAtomicRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 6: Refactor retry engine, dispatcher, and generic producer isolation

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retry/TaskRetryDispatcherPort.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/retry/impl/RequirementTaskRetryDispatcherAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java`
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/RdTaskTransitionPolicy.java`
- Test: `engine/src/test/java/com/wish/rd/engine/retry/TaskRetryEngineTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/retry/impl/RequirementTaskRetryDispatcherAdapterTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchServiceTest.java`
- Test: `rag/src/test/java/com/wish/rd/rag/runtime/RdTaskTransitionPolicyTest.java`

- [ ] **Step 1: Write RED tests for post-commit-only scheduling.** `TaskRetryEngine` calls `initialize`, then `dispatch(checkpointId)`. A scheduler failure leaves the same `DISPATCHED` checkpoint/PENDING command; replay wakes it and creates nothing.
- [ ] **Step 2: Change dispatcher authority to checkpoint ID.** It loads the exact checkpoint/`dispatchCommandId`, validates generation/task/fence, and calls `schedulePersistedCommand(commandId)`. It never calls generic `submit(taskId)`.
- [ ] **Step 3: Remove pre-dispatch status/compensation writes.** Workspace recovery remains outside the transaction; after successful external recovery, re-resolve source/provenance before initialization. Before resolving the now-`RECOVERING` task on an exact repeated request, derive the business idempotency key from supplied immutable source guards and return/validate the winner checkpoint. For null/legacy requests whose new guards are omitted, first load the database-enforced single active checkpoint by task and semantically compare every supplied nonblank guard plus note/evidence/role override; compatible omissions accept the stored authoritative identity, conflicts fail, and absence of an active checkpoint falls through to fresh failure resolution. A repeat never reconstructs a failed source from an advanced task.
- [ ] **Step 4: Add active-checkpoint guard to every normal producer.** Cover API submit, umbrella recovery, stale job recovery, normal continuation recovery, and any scheduled producer. An active checkpoint returns/wakes its exact pending command or fails closed; it never calls the three-field normal enqueue path.
- [ ] **Step 5: Keep `RECOVERING` fail-closed.** Add only the missing recovery in-progress transition needed by checkpoint-bound plan validation. Prove `RECOVERING -> CREATED/MATERIAL_READY/CONTEXT_READY/PLAN_GENERATED` stays illegal and `nextStageFor(RECOVERING)` still throws.
- [ ] **Step 6: Run focused tests.**

```bash
./mvnw -pl engine -am -Dtest=TaskRetryEngineTest,TaskRetryPointResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=RequirementTaskRetryDispatcherAdapterTest,RequirementDeliveryDispatchServiceTest,RequirementDeliveryDispatchServiceWiringTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl rag -am -Dtest=RdTaskTransitionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 7: Execute exact attempts and append later children once

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/retry/RequirementRetryAttemptAppendTransactionPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/retry/model/TaskRetryExecutionContext.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/retrieval/DeepRetrievalOrchestrator.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/review/AiDeliveryReviewEngine.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementRetryAttemptAppendTransactionAdapter.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestratorTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementRetryAttemptAppendTransactionAdapterTest.java`

- [ ] **Step 1: Write RED substitution tests.** With two PENDING attempts for one role, only the command's bound ID may run. Wrong checkpoint, generation, parent binding, task, plan digest, version, fence, or policy fails before any attempt transition.
- [ ] **Step 2: Build retry execution context from command.** Require exact checkpoint `DISPATCHED`, generation, command fence, and primary binding. Normal commands retain the existing path.
- [ ] **Step 3: Add recovery prefixes to the first command plan.** Early in-progress stages may transition directly from `RECOVERING`; ready/generated outcomes first pass through their legal in-progress predecessor as shown in the route matrix. Role/RAG prepend `RECOVERING -> EXECUTING`; deterministic review prepends `RECOVERING -> EXECUTING -> VALIDATING`; direct AI prepends `RECOVERING -> VALIDATING`; publication prepends `RECOVERING -> PR_CREATING`. Only a validated retry context may use these prefixes.
- [ ] **Step 4: Execute exact AgentStage/Retrieval/AiReview attempts.** RAG resolves the child by `(checkpointId,parentAgentBindingId,RETRIEVAL,ordinal)`. Direct AI uses its primary binding. Do not call `latest(role)` or mint another run.
- [ ] **Step 5: Add append-once child transaction.** For later role-triggered retrieval/AI review, lock checkpoint and parent binding, insert-or-return one child attempt/binding for the deterministic slot, and accept the winner's ID under concurrency. No external retrieval/model call occurs in this transaction.
- [ ] **Step 6: Preserve narrow APPLIED-policy authorization.** Validate task, policy ID/state, frozen plan digest, checkpoint dispatch fence, and role binding. Do not remove normal first-role bound-version/fence checks.
- [ ] **Step 7: Run focused tests.**

```bash
./mvnw -pl engine -am -Dtest=RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,TaskFailureRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=PostgresRequirementRetryAttemptAppendTransactionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 8: Propagate retry identity through policy control transactions

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementPolicyTransactionAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementPolicyRunMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskFailureProvenanceMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/policy/impl/InMemoryRequirementPolicyTransactionAdapter.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementPolicyTransactionAdapterTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementPolicyTransactionRealSmokeTest.java`

- [ ] **Step 1: Write RED control-plane tests.** `prepareEvaluation`, `recordEvaluation`, `consumePolicyApply`, approval, and approval resume must use generation-aware command lookup and propagate checkpoint/generation/next target binding. A normal policy command remains unchanged.
- [ ] **Step 2: Evaluate the frozen retry plan outside locks.** A checkpoint-bound `POLICY_EVALUATE` may read the immutable checkpoint plan while task status is `RECOVERING`; it must not regenerate a plan or require generic `PLAN_GENERATED` routing.
- [ ] **Step 3: Record evaluation atomically.** Lock policy -> task -> checkpoint -> command; CAS new ledger `PLAN_READY -> POLICY_DECIDED`, transition retry task `RECOVERING -> WAITING_POLICY`, complete evaluate command, and enqueue `POLICY_APPLY` with the same generation/checkpoint. Checkpoint remains `DISPATCHED`.
- [ ] **Step 4: Consume apply/approval exactly.** ALLOWED resolves the pre-bound first role, enqueues it, and leaves checkpoint `DISPATCHED`; WAITING_APPROVAL atomically moves checkpoint to `WAITING_APPROVAL`. A normal POLICY_APPLY denial or human approval rejection must, in the same policy transaction, move task/policy to the exact terminal state, insert structured provenance, settle that checkpoint when present, and CAS-cancel every unused PENDING attempt bound to it. Approval acceptance validates the same checkpoint/policy, creates the exact resume/role command, and atomically moves `WAITING_APPROVAL -> DISPATCHED`.
- [ ] **Step 5: Own policy-control exhaustion.** Add a policy transaction operation for exhausted `POLICY_EVALUATE/POLICY_APPLY/APPROVAL_RESUME`: in lock order policy -> task -> checkpoint -> command -> bindings/attempts, atomically mark the command terminal, move the task to its exact failure status, insert `rd_task_failure_provenance` with policy/digest/command identity, cancel unused bound PENDING attempts, and settle only that checkpoint when present. Provenance insert, attempt cancellation, or checkpoint update failure rolls back command/task writes.
- [ ] **Step 6: Remove retry use of task-wide/three-field policy lookup.** Every replay compares checkpoint, generation, command, policy, target binding, task, and plan digest. Generic finalizer never consumes policy control commands.
- [ ] **Step 7: Run policy tests.**

```bash
./mvnw -pl bootstrap -am -Dtest=PostgresRequirementPolicyTransactionAdapterTest,PostgresRequirementPolicyTransactionRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl engine -am -Dtest=InMemoryRequirementPolicyTransactionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 9: Propagate generation in generic finalization and settle exact checkpoint

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageFinalizationPort.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/job/impl/InMemoryRequirementStageFinalizationPort.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageFinalizationAdapter.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/threading/RequirementDeliveryDispatchService.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementStageFinalizationMapper.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/TaskFailureProvenanceMapper.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/job/InMemoryRequirementStageFinalizationPortTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementStageFinalizationAdapterTest.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementStageFinalizationRealSmokeTest.java`

- [ ] **Step 1: Write RED continuation tests.** Reviewer -> architect keeps checkpoint/generation but uses architect's own binding. Missing/foreign binding rolls back task mutation, current completion, continuation insert, and checkpoint update.
- [ ] **Step 2: Validate lineage in the existing finalizer transaction.** After command -> marker -> task locks, lock exact checkpoint/bindings. Validate current command and resolve continuation target from the same checkpoint; serialized worker output cannot override retry identity.
- [ ] **Step 3: Bind the first policy generation for early retries.** When a checkpoint-bound `PLAN_GENERATED` continuation prepares its `PLAN_READY` row, the same finalizer transaction must insert/revalidate the ledger, CAS checkpoint `(policyRunId,authorizationPlanDigest)` from blank to that exact generation/digest, then enqueue `POLICY_EVALUATE` with checkpoint/generation. On replay all three identities must match; any mismatch rolls back. POLICY-direct initialization already fills these fields and follows the same semantic check.
- [ ] **Step 4: Propagate primary and child slots.** Early infrastructure continuations may have no primary attempt binding; role/AI continuations must select their exact pre-bound binding. Any later child creation goes through the append-once transaction before external execution.
- [ ] **Step 5: Persist every generic failure atomically.** Business-terminal finalization inserts `rd_task_failure_provenance` in the same command/marker/task/checkpoint transaction. Add an exhaustion operation on `RequirementStageFinalizationPort` for PREPARED/no-outcome technical failures that atomically terminalizes the command, fails the task, inserts exact provenance, and settles the exact checkpoint; remove the current separate `fail command` then `failTaskAfterCommandExhausted` sequence. Provenance insert failure rolls back every disposition.
- [ ] **Step 6: Settle only by checkpoint ID.** Remove retry use of `findActiveByTask`/`completeRetryCheckpoint(taskId,...)`. Intermediate statuses and any real continuation stay `DISPATCHED`; terminal result updates only `completed.retryCheckpointId()`. Terminalization also CAS-cancels any unused PENDING attempts bound to that checkpoint. Policy waiting/approval remains owned by Task 8.
- [ ] **Step 7: Reconcile publication.** `PUBLICATION:<operationId>` must load the exact operation/receipt; UNKNOWN reconciles before an external write and never blindly creates a second PR. A publication terminal failure writes the same structured provenance with `publicationOperationId`.
- [ ] **Step 8: Run finalization tests.**

```bash
./mvnw -pl engine -am -Dtest=InMemoryRequirementStageFinalizationPortTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=PostgresRequirementStageFinalizationAdapterTest,PostgresRequirementStageFinalizationRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 10: HTTP, concurrency, crash recovery, and completion audit

**Files:**
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryController.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskRetryControllerTest.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/TaskRetryHttpRealAcceptanceTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementRetryDispatchRealSmokeTest.java`
- Modify: `bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresRequirementStageFinalizationRealSmokeTest.java`
- Modify: `docs/superpowers/specs/2026-07-13-rd-task-stage-retry-and-ai-delivery-review-design.md`
- Modify: `RULE.md` to finalize the Task 0 commit-boundary wording against implemented path/test names.

- [ ] **Step 1: Add controller/DTO compatibility tests.** Existing retry request/response fields remain compatible; optional expected source fence/failed command/stage and returned generation/provenance fields are additive. When supplied, every guard is exact; legacy omissions still rely on the transaction's authoritative source snapshot and never fabricate identity. Cover success, stale source fence, conflicting idempotency input, and exact repeated request.
- [ ] **Step 2: Add real HTTP `/admin/rd-tasks/{taskId}/retry` acceptance.** Start an isolated PostgreSQL instance, POST retry, assert 2xx, then query checkpoint/task event/attempt bindings/stage command/policy ledger. Repeat the same request and prove identical durable identities. Conflicting payload returns deterministic 409/4xx with no writes.
- [ ] **Step 3: Complete phase/sub-stage matrix.** Cover all route rows, active-policy supersession, immutable DENIED-to-new-generation retry, policy/approval denial provenance plus unused-attempt cancellation, first-role APPLIED narrow authorization, RAG parent-child binding, direct/later AI review, and publication UNKNOWN reconciliation.
- [ ] **Step 4: Complete concurrency/constraint matrix.** Use different proposed IDs in two instances; require one winner. Prove active checkpoint blocks every generic producer, cross-checkpoint composite FKs reject, legacy fence `0` cannot authorize, old terminal command is not reused, and downstream old PENDING attempt is not reused.
- [ ] **Step 5: Fault-inject every boundary.** Checkpoint/task event/policy supersession/new policy/old-attempt terminalization/new attempt/binding/command/DISPATCHED each roll back the full initialization transaction. Separately inject failure at generic and policy-control provenance insert and prove command/task/checkpoint terminalization all roll back. Commit initialization without scheduling, restart dispatcher, and claim the same PENDING command. Technical lease retry keeps command ID and increments only `attemptNo`; stale old-fence result is rejected.
- [ ] **Step 6: Run the verification matrix.**

```bash
./mvnw -pl engine -am -Dtest=TaskRetryPointResolverTest,TaskFailureRecoveryServiceTest,TaskRetryRoutePlannerTest,TaskRetryEngineTest,RequirementStageCommandStoreTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,InMemoryRequirementStageFinalizationPortTest,InMemoryRequirementPolicyTransactionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=TaskRetryControllerTest,RequirementTaskRetryDispatcherAdapterTest,RequirementDeliveryDispatchServiceTest,PostgresTaskRetryFailureProvenanceStoreTest,PostgresTaskRetryCheckpointStoreTest,PostgresTaskRetryAttemptBindingStoreTest,PostgresRequirementStageCommandStoreTest,PostgresRequirementRetryDispatchTransactionAdapterTest,PostgresRequirementRetryAttemptAppendTransactionAdapterTest,PostgresRequirementStageFinalizationAdapterTest,PostgresRequirementPolicyTransactionAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=TaskRetryHttpRealAcceptanceTest,PostgresRetrySchemaConstraintRealSmokeTest,PostgresRequirementRetryDispatchRealSmokeTest,PostgresRequirementStageFinalizationRealSmokeTest,PostgresRequirementPolicyTransactionRealSmokeTest,PostgresRdTaskStateAtomicRealSmokeTest,PostgresMigrationOrderPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -DskipTests compile
git diff --check
```

- [ ] **Step 7: Perform the completion audit.** For each invariant and route row, link a domain/unit test and a PostgreSQL/finalization/HTTP test. Confirm no retry path uses update time as version, generic `submit(taskId)`, `nextStageFor(RECOVERING)`, `latest(role)` authorization, retry three-field lookup, task-wide checkpoint settlement, blind publication replay, or a second live retry POST.

## Execution notes

- The worktree contains extensive user-owned uncommitted work. Do not reset, clean, stage, commit, or push unless the user separately requests it.
- Keep `PostgresRequirementStageFinalizationAdapter` responsible only for already-running generic command outcomes. Keep `PostgresRequirementPolicyTransactionAdapter` responsible for policy control commands. The retry initialization adapter only establishes a commandless-to-first-command generation.
- Use package-private semantic identity helpers only where two transaction owners must enforce exactly the same invariant; do not call one `@Transactional` adapter from another.
- Do not perform any live retry POST while implementing. The unique authorized retry for task `7492757104181252096` has already been consumed.
