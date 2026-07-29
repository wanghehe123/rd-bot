# Evaluation V2 Coding Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible, offline, Oracle-scored A/B/C/D coding benchmark to RD-Bot without breaking the existing quality-evaluation console.

**Architecture:** Keep `rd_evaluation_runs` as the campaign aggregate and add a mode-specific coding-benchmark trial aggregate below it. Engine owns lifecycle, configuration validation, deterministic trial planning, and ports; bootstrap owns PostgreSQL, Docker/process adapters, manifest discovery, and HTTP; Python owns offline preparation, verification, scoring, and report generation. The existing `RequirementDeliveryEngine -> RequirementExecutorPort -> DockerPiAgentExecutor` remains the execution path; it is extracted behind a workflow-plan boundary only after the control plane and harness contracts are covered by tests.

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis/PostgreSQL, Docker, Node/Pi bridge, Python 3 standard library, React/TypeScript, JUnit 5, Node test runner.

---

## Repository map

- `engine/.../evaluation/*`: campaign lifecycle, immutable benchmark models, trial planning and state transitions.
- `bootstrap/.../persistence/*` and `resources/sql/postgres/*`: PostgreSQL snapshots, events, CAS, leases, and schema.
- `bootstrap/.../evaluation/*`: manifest catalog, bounded scheduler, trusted Python/Docker adapters and output reading.
- `engine/.../requirement/RequirementDeliveryEngine.java`: current production stage loop to be extracted without changing D semantics.
- `bootstrap/.../executor/impl/EngineRequirementExecutorAdapter.java` and `exec/.../DockerPiAgentExecutor.java`: production execution adapter and Docker boundary; changes are deferred until their currently uncommitted work is settled.
- `scripts/evaluation/*`: deterministic prepare, verifier, scorer, and report commands.
- `frontend/src/pages/admin/evaluation/*` and `frontend/src/services/evaluationService.ts`: safe benchmark UI and contract.

## Task 1: Add immutable coding-benchmark domain vocabulary

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/EvaluationMode.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkArm.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkTrialStatus.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkVerdict.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkTrial.java`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkTrialTest.java`

- [ ] **Step 1: Write the failing model tests**

```java
@Test
void should_create_first_attempt_with_queued_status() {
    CodingBenchmarkTrial trial = CodingBenchmarkTrial.queued("1", "case-a", CodingBenchmarkArm.A, 0, 100L);

    assertThat(trial.status()).isEqualTo(CodingBenchmarkTrialStatus.QUEUED);
    assertThat(trial.replicateNo()).isZero();
    assertThat(trial.attemptNo()).isEqualTo(1);
}

@Test
void should_reject_non_sentinel_replicate_number() {
    assertThatThrownBy(() -> CodingBenchmarkTrial.queued("1", "case-a", CodingBenchmarkArm.A, 2, 100L))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl engine -Dtest=CodingBenchmarkTrialTest test`

Expected: compilation failure because `CodingBenchmarkTrial` and its enums do not yet exist.

- [ ] **Step 3: Implement the smallest immutable records/enums**

```java
public enum CodingBenchmarkArm { A, B, C, D }

public enum CodingBenchmarkTrialStatus {
    QUEUED, PREPARING, RUNNING_AGENTS, RUNNING_ORACLE, RETRY_PENDING,
    SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
```

`CodingBenchmarkTrial` must normalize blank IDs to an `IllegalArgumentException`, permit only replicate `0` and `1`, and keep verdict/error fields empty at creation.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl engine -Dtest=CodingBenchmarkTrialTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation/model engine/src/test/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkTrialTest.java
git commit -m "feat: add coding benchmark trial model"
```

## Task 2: Make EvaluationRun mode-aware without regressing legacy runs

**Files:**

- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/model/EvaluationRunConfig.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/model/EvaluationRun.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/EvaluationTransitionPolicy.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/evaluation/EvaluationRunEngine.java`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/EvaluationRunEngineTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/EvaluationTransitionPolicyTest.java`

- [ ] **Step 1: Write failing mode tests**

```java
@Test
void should_start_coding_benchmark_in_preparing_not_recording() {
    EvaluationRun run = engine.start(CodingBenchmarkFixtures.config());

    scheduler.runNext();

    assertThat(store.find(run.runId()).orElseThrow().status()).isEqualTo(EvaluationRunStatus.PREPARING);
}

@Test
void should_keep_legacy_recording_transition_for_fixture_runs() {
    assertThat(policy.canTransition(EvaluationMode.LEGACY_QUALITY,
            EvaluationRunStatus.QUEUED, EvaluationRunStatus.RECORDING)).isTrue();
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./mvnw -pl engine -Dtest=EvaluationRunEngineTest,EvaluationTransitionPolicyTest test`

Expected: compilation failure for `EvaluationMode` or assertion failure because the current policy has one shared graph.

- [ ] **Step 3: Implement mode defaults and per-mode state graph**

Add `EvaluationMode mode` as the last `EvaluationRunConfig` component with a backward-compatible constructor that defaults old JSON to `LEGACY_QUALITY`. Add benchmark states `PREPARING`, `RUNNING_TRIALS` and `SCORING`/`REPORTING` edges only for `CODING_BENCHMARK`; retain the existing `RECORDING -> SCORING -> REPORTING -> DIFFING` graph for legacy modes. `EvaluationRunEngine` must choose phase transitions from `config.mode()`, not infer from user-provided strings.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `./mvnw -pl engine -Dtest=EvaluationRunEngineTest,EvaluationTransitionPolicyTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation engine/src/test/java/com/wish/rd/engine/evaluation
git commit -m "feat: add mode-aware evaluation campaigns"
```

## Task 3: Generate the fixed 88-trial plan before any dispatch

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkPlanFactory.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkCase.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkPlan.java`
- Test: `engine/src/test/java/com/wish/rd/engine/evaluation/CodingBenchmarkPlanFactoryTest.java`

- [ ] **Step 1: Write the failing planning tests**

```java
@Test
void should_create_eighty_primary_trials_and_eight_sentinel_repeats() {
    CodingBenchmarkPlan plan = factory.create(Fixtures.twentyCases(), Set.of("case-01", "case-06", "case-11", "case-16"));

    assertThat(plan.trials()).hasSize(88);
    assertThat(plan.trials().stream().filter(trial -> trial.replicateNo() == 0)).hasSize(80);
    assertThat(plan.trials().stream().filter(trial -> trial.replicateNo() == 1)).hasSize(8);
}

@Test
void should_balance_primary_case_arm_sequences() {
    assertThat(factory.create(Fixtures.twentyCases(), Fixtures.sentinels()).sequenceCounts())
            .containsEntry("ABCD", 5).containsEntry("BCDA", 5)
            .containsEntry("CDAB", 5).containsEntry("DABC", 5);
}
```

- [ ] **Step 2: Run the test to verify RED**

Run: `./mvnw -pl engine -Dtest=CodingBenchmarkPlanFactoryTest test`

Expected: compilation failure because no plan factory exists.

- [ ] **Step 3: Implement deterministic planning**

The factory must sort case IDs before using a frozen seed, assign the four sequences exactly five times, add all `replicateNo=0` arm cells, then add A/D `replicateNo=1` only for the four preselected sentinel cases. Reject non-20 case collections, duplicate case IDs, a sentinel outside the collection, or a sentinel count other than four.

- [ ] **Step 4: Run the test to verify GREEN**

Run: `./mvnw -pl engine -Dtest=CodingBenchmarkPlanFactoryTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation engine/src/test/java/com/wish/rd/engine/evaluation/CodingBenchmarkPlanFactoryTest.java
git commit -m "feat: preplan balanced coding benchmark trials"
```

## Task 4: Persist campaign trials, append-only events, leases, and CAS

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkTrialStore.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkTrialEvent.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/CodingBenchmarkTrialRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/CodingBenchmarkTrialEventRow.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/CodingBenchmarkTrialMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/CodingBenchmarkTrialEventMapper.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresCodingBenchmarkTrialStore.java`
- Modify: `bootstrap/src/main/resources/sql/postgres/p4_web_evaluation_console.sql`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresCodingBenchmarkTrialStoreTest.java`

- [ ] **Step 1: Write the failing store-contract test**

```java
@Test
void should_claim_exactly_one_queued_trial_with_a_lease() {
    store.createAll("100", Fixtures.plan().trials());

    Optional<CodingBenchmarkTrial> first = store.claimNext("worker-a", 1_000L, 2_000L);
    Optional<CodingBenchmarkTrial> second = store.claimNext("worker-b", 1_001L, 2_000L);

    assertThat(first).isPresent();
    assertThat(second).isPresent();
    assertThat(second.orElseThrow().trialId()).isNotEqualTo(first.orElseThrow().trialId());
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./mvnw -pl bootstrap -am -Dtest=PostgresCodingBenchmarkTrialStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because the store and tables do not yet exist.

- [ ] **Step 3: Implement SQL and transactional store operations**

Create `rd_evaluation_trials` with unique `(campaign_id, case_id, arm, replicate_no)`, status/version/lease fields, frozen snapshots, verdict/error, patch hashes and metrics JSON. Create `rd_evaluation_trial_events` with immutable state timeline. `claimNext` must use a conditional SQL update over `QUEUED` or expired `PREPARING`, return the claimed row, and never create a second logical trial. All status changes must check both expected status and version, then append the event in the same `@Transactional` method.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `./mvnw -pl bootstrap -am -Dtest=PostgresCodingBenchmarkTrialStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS; add the existing real PostgreSQL atomic smoke to the same command group once its test fixture registers the new tables.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation bootstrap/src/main/java/com/wish/rd/bootstrap/persistence bootstrap/src/main/resources/sql/postgres/p4_web_evaluation_console.sql bootstrap/src/test/java/com/wish/rd/bootstrap/persistence
git commit -m "feat: persist coding benchmark trials"
```

## Task 5: Add trusted benchmark manifest discovery and readiness gates

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkCatalogPort.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/model/CodingBenchmarkSnapshot.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/impl/FileSystemCodingBenchmarkCatalog.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/EvaluationProperties.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/impl/LocalPythonEvaluationExecutor.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation/impl/FileSystemCodingBenchmarkCatalogTest.java`

- [ ] **Step 1: Write the failing readiness test**

```java
@Test
void should_only_expose_snapshot_when_all_required_manifests_share_a_digest() throws Exception {
    writeSnapshot("ready", true);

    assertThat(catalog.readySnapshots()).extracting(CodingBenchmarkSnapshot::snapshotId).containsExactly("ready");
}

@Test
void should_reject_snapshot_with_a_floating_image_tag() throws Exception {
    writeSnapshotWithImage("latest");

    assertThat(catalog.readySnapshots()).isEmpty();
}
```

- [ ] **Step 2: Run the test to verify RED**

Run: `./mvnw -pl bootstrap -am -Dtest=FileSystemCodingBenchmarkCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because the catalog does not exist.

- [ ] **Step 3: Implement allowlisted manifest loading**

The catalog must read only beneath `rd.evaluation.coding-benchmark-root`, reject symlinks and path traversal, require `dataset-manifest.json`, `environment-manifest.json`, `knowledge-manifest.json`, `analysis-plan.json`, `readiness-report.json`, and `benchmark-provenance.json`, verify SHA-256 references, and reject image tags without an immutable digest. It exposes snapshot IDs and redacted labels only; no Web client can submit paths, commands, images, model endpoints, or keys.

- [ ] **Step 4: Run the test to verify GREEN**

Run: `./mvnw -pl bootstrap -am -Dtest=FileSystemCodingBenchmarkCatalogTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation
git commit -m "feat: discover ready coding benchmark snapshots"
```

## Task 6: Implement safe offline preparation, patch extraction, and Oracle verification scripts

**Files:**

- Create: `scripts/evaluation/rd_eval_prepare_coding_benchmark.py`
- Create: `scripts/evaluation/rd_eval_oracle.py`
- Create: `scripts/evaluation/rd_eval_patch.py`
- Create: `scripts/evaluation/tests/test_prepare_coding_benchmark.py`
- Create: `scripts/evaluation/tests/test_oracle.py`
- Create: `scripts/evaluation/tests/test_patch.py`

- [ ] **Step 1: Write the failing Python tests**

```python
def test_extract_patch_rejects_runtime_withheld_test_path(tmp_path: Path) -> None:
    repo = init_repo_with_changed_file(tmp_path, "tests/runtime_withheld/test_issue.py")

    with pytest.raises(PatchContractError, match="protected path"):
        extract_patch(repo, protected_paths={"tests/runtime_withheld"})

def test_oracle_fails_when_expected_test_id_is_not_collected(tmp_path: Path) -> None:
    result = verify_result(test_output="1 passed", expected_test_ids=["test_issue::test_regression"])

    assert result.verdict == "TEST_FAIL"
```

- [ ] **Step 2: Run the tests to verify RED**

Run: `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'`

Expected: import failures because the new scripts do not exist.

- [ ] **Step 3: Implement deterministic contracts**

`rd_eval_patch.py` must generate a Git binary-safe patch from a trial-private repo, exclude configured build/cache/output paths, reject path traversal, symlinks, submodules and protected test/runner/config paths, then round-trip apply to a clean base. `rd_eval_oracle.py` must apply only that patch into a fresh verifier repo, inject the protected test bundle after patch application, hash bundle/runner/config before and after, execute the fixed command in Docker `--network=none`, and fail if expected IDs are missing, duplicated, skipped, or the test bundle cannot be injected. `prepare ... verify` must execute BASE/TEST/FIX three times and emit an immutable readiness report.

- [ ] **Step 4: Run the tests to verify GREEN**

Run: `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/evaluation
git commit -m "feat: add offline coding benchmark verifier"
```

## Task 7: Add a bounded coding-benchmark runner and runtime attestation

**Files:**

- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/impl/DockerCodingBenchmarkExecutor.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation/impl/CodingBenchmarkRuntimeAttestor.java`
- Create: `engine/src/main/java/com/wish/rd/engine/evaluation/CodingBenchmarkExecutionPort.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation/impl/DockerCodingBenchmarkExecutorTest.java`

- [ ] **Step 1: Write the failing executor tests**

```java
@Test
void should_send_only_patch_metadata_to_verifier() {
    executor.execute(Fixtures.trial());

    assertThat(processRunner.commands()).anySatisfy(command ->
            assertThat(command.mountSources()).doesNotContain(Fixtures.agentWorkspace(), Fixtures.agentCache()));
}

@Test
void should_record_network_and_image_attestation() {
    RuntimeAttestation attestation = executor.execute(Fixtures.trial()).attestation();

    assertThat(attestation.oracleNetworkMode()).isEqualTo("none");
    assertThat(attestation.agentImageDigest()).startsWith("sha256:");
}
```

- [ ] **Step 2: Run the test to verify RED**

Run: `./mvnw -pl bootstrap -am -Dtest=DockerCodingBenchmarkExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation failure because the executor does not exist.

- [ ] **Step 3: Implement container construction through a narrow process port**

Build Agent containers with a private per-trial Docker network, non-root user, read-only rootfs, caps dropped, no-new-privileges, `--init`, fixed resource limits, no Docker socket, and an injected one-time relay token. Build Verifier containers separately with `--network=none`. Create and inspect only label-scoped resources; cleanup failure returns `INFRA_ERROR` and quarantines the exact trial path. Do not modify `DockerPiAgentExecutor` in this task: integration waits for the user’s existing uncommitted changes to settle.

- [ ] **Step 4: Run the test to verify GREEN**

Run: `./mvnw -pl bootstrap -am -Dtest=DockerCodingBenchmarkExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/evaluation bootstrap/src/main/java/com/wish/rd/bootstrap/evaluation bootstrap/src/test/java/com/wish/rd/bootstrap/evaluation
git commit -m "feat: add isolated coding benchmark executor"
```

## Task 8: Extract reusable production stage orchestration and map A/B/C/D workflow plans

**Files:**

- Create: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/model/AgentWorkflowPlan.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementExecutorAdapter.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestratorTest.java`
- Test: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`

- [ ] **Step 1: Write the failing workflow-plan tests**

```java
@Test
void should_map_arm_c_to_reviewer_architect_and_coding_with_role_retrieval() {
    AgentWorkflowPlan plan = AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.C);

    assertThat(plan.roles()).containsExactly(REQUIREMENT_REVIEWER, SOLUTION_ARCHITECT, CODING_AGENT);
    assertThat(plan.retrievalEnabled()).isTrue();
    assertThat(plan.qaRemediationEnabled()).isFalse();
}

@Test
void should_keep_production_plan_equal_to_existing_d_chain() {
    assertThat(AgentWorkflowPlan.production()).isEqualTo(AgentWorkflowPlan.codingBenchmark(CodingBenchmarkArm.D));
}
```

- [ ] **Step 2: Run the test to verify RED**

Run: `./mvnw -pl engine -Dtest=RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest test`

Expected: compilation failure because no workflow plan/orchestrator exists.

- [ ] **Step 3: Extract without changing production behavior**

Move the existing `executeAgentStages` loop into `RequirementAgentStageOrchestrator` unchanged first. Make `RequirementDeliveryEngine` delegate its current D plan to it. Then let the plan restrict roles, retrieval, QA and one remediation pass; use existing `AgentStageRun` persistence and `RequirementExecutionProfileResolverPort` for every role. Run the existing production regression suite before enabling benchmark-only A/B/C plans.

- [ ] **Step 4: Run the tests to verify GREEN**

Run: `./mvnw -pl engine -Dtest=RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/com/wish/rd/engine/requirement engine/src/test/java/com/wish/rd/engine/requirement bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl
git commit -m "refactor: reuse requirement stages for benchmark arms"
```

## Task 9: Generate frozen RAG documentation and analysis/report artifacts

**Files:**

- Create: `scripts/evaluation/rd_eval_generate_benchmark_docs.py`
- Create: `scripts/evaluation/rd_eval_coding_score.py`
- Create: `scripts/evaluation/rd_eval_coding_report.py`
- Create: `scripts/evaluation/tests/test_coding_score.py`
- Create: `scripts/evaluation/tests/test_generate_benchmark_docs.py`

- [ ] **Step 1: Write the failing tests**

```python
def test_formal_gold_file_hit_is_reported_but_never_a_readiness_failure() -> None:
    report = score_trials(formal_trials_with_low_gold_hit())

    assert report["readiness"]["passed"] is True
    assert report["rag"]["formalGoldFileHitAt5"] == 0.0

def test_missing_pair_bounds_mark_contrast_inconclusive() -> None:
    contrast = compare_arms(trials_with_one_infra_pair(), "D", "A")

    assert contrast["validity"] == "INCONCLUSIVE"
```

- [ ] **Step 2: Run the tests to verify RED**

Run: `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'`

Expected: import failures because the scorer/report generator does not exist.

- [ ] **Step 3: Implement frozen-doc and scoring rules**

Generate the four per-case documents from base repositories only, scan for Gold/test leakage, hash every chunk, and allow tuning only for the two probe cases. Score `replicateNo=0` separately for fresh/public/full; report Wilson intervals, paired Newcombe CI, exact McNemar for fresh D-A, Holm-adjusted exploratory contrasts, missing-pair bounds, aggregate token/time ratios, and sentinel flips without selecting the better repeat.

- [ ] **Step 4: Run the tests to verify GREEN**

Run: `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/evaluation
git commit -m "feat: score coding benchmark ablations"
```

## Task 10: Expose only ready benchmark snapshots in the management console

**Files:**

- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/evaluation/EvaluationController.java`
- Modify: `frontend/src/services/evaluationService.ts`
- Modify: `frontend/src/pages/admin/evaluation/EvaluationPage.tsx`
- Modify: `frontend/src/pages/admin/evaluation/evaluationPresentation.ts`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/evaluation/EvaluationControllerTest.java`
- Test: `frontend/test/evaluationConsole.test.ts`

- [ ] **Step 1: Write the failing API and UI contract tests**

```java
@Test
void should_reject_coding_benchmark_request_without_ready_snapshot() throws Exception {
    mvc.perform(post("/admin/evaluations/coding-benchmarks")
                    .contentType(APPLICATION_JSON)
                    .content("{\"snapshotId\":\"missing\"}"))
            .andExpect(status().isConflict());
}
```

```ts
test("coding benchmark page posts only snapshot id", () => {
  assert.match(service, /createCodingBenchmarkCampaign\(snapshotId: string\)/);
  assert.doesNotMatch(page, /agentImageDigest|oracleTestCommands|modelApiKey/);
});
```

- [ ] **Step 2: Run the tests to verify RED**

Run: `./mvnw -pl bootstrap -am -Dtest=EvaluationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test && node --experimental-strip-types --test frontend/test/evaluationConsole.test.ts`

Expected: controller route and service helper are missing.

- [ ] **Step 3: Implement the fixed-selector UI**

Add a dedicated `POST /admin/evaluations/coding-benchmarks` endpoint that accepts only an allowlisted snapshot ID. Add a “编码消融评测” tab that displays readiness, 20×4 matrix, A/B/C/D paired deltas, active/queued counts, ETA, runtime-attestation references and read-only trial details. Keep existing fixture/RAG/task-run form and routes unchanged.

- [ ] **Step 4: Run the tests to verify GREEN**

Run: `./mvnw -pl bootstrap -am -Dtest=EvaluationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test && node --experimental-strip-types --test frontend/test/evaluationConsole.test.ts && cd frontend && npm run typecheck && npm run build`

Expected: all commands PASS/build succeeds.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/evaluation bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/evaluation frontend/src/services/evaluationService.ts frontend/src/pages/admin/evaluation frontend/test/evaluationConsole.test.ts
git commit -m "feat: add coding benchmark admin console"
```

## Task 11: Build images, create 20 cases, and run real readiness/probes

**Files:**

- Create: `scripts/evaluation/coding-benchmark-v2.yaml`
- Create: `scripts/evaluation/docker/Dockerfile.java21`
- Create: `scripts/evaluation/docker/Dockerfile.node`
- Create: `scripts/evaluation/docker/Dockerfile.relay`
- Create: `docs/qa/coding-benchmark-v2-readiness.md`

- [ ] **Step 1: Write configuration validation fixtures first**

```python
def test_verify_rejects_manifest_without_ten_fresh_and_ten_public_cases(tmp_path: Path) -> None:
    config = write_config(tmp_path, fresh=9, public=11)

    assert verify(config).exit_code == 2
```

- [ ] **Step 2: Run the test to verify RED**

Run: `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'`

Expected: the readiness validator rejects or does not yet parse the fixture.

- [ ] **Step 3: Build only the declared shared/case layers and verify offline**

Build immutable platform-pinned images, use content-addressed dependency bundles, construct private base-history repositories, run BASE/TEST/FIX three times per case, generate RAG docs and all manifests, measure actual Docker/disk capacity with 20% headroom, and record every image digest. The ten fresh cases require user-owned/private or provably post-cutoff tasks; do not fabricate freshness from public cases.

- [ ] **Step 4: Run readiness and two real probes**

Run: `python3 scripts/evaluation/rd_eval_prepare_coding_benchmark.py verify --config scripts/evaluation/coding-benchmark-v2.yaml`

Expected: 20 cases + 2 probes, offline dependencies, future-history checks, RAG leakage checks, relay/Oracle negative network checks, and resource gate all PASS.

Then run the 8 probe trials, including one service restart, one cancel and one pause/resume. Record results in `docs/qa/coding-benchmark-v2-readiness.md`.

- [ ] **Step 5: Commit configuration and readiness evidence**

```bash
git add scripts/evaluation docs/qa/coding-benchmark-v2-readiness.md
git commit -m "chore: prepare coding benchmark readiness assets"
```

## Task 12: Full verification and final evidence

**Files:**

- Modify: `docs/qa/coding-benchmark-v2-readiness.md`

- [ ] **Step 1: Run module and protocol verification**

Run:

```bash
./mvnw -pl engine,exec,bootstrap -am test
cd bootstrap/src/main/resources/executor/pi && npm test
python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'
node --experimental-strip-types --test frontend/test/*.test.ts
cd frontend && npm run typecheck && npm run build
```

Expected: all commands pass.

- [ ] **Step 2: Run the PostgreSQL state/lease smoke and one HTTP campaign flow**

Run the repository’s `PostgresRdTaskStateAtomicRealSmokeTest` plus the coding-benchmark store equivalent, then create a campaign through `POST /admin/evaluations/coding-benchmarks`, inspect its trial matrix/timeline, pause/resume it, and cancel one disposable probe. Record request, response status, key response fields and artifact IDs in the QA report without secrets.

- [ ] **Step 3: Commit final QA evidence**

```bash
git add docs/qa/coding-benchmark-v2-readiness.md
git commit -m "test: verify coding benchmark campaign"
```

## Plan self-review

- Spec coverage: Tasks 1–5 implement mode/campaign/trial persistence and immutable inputs; Tasks 6–7 enforce offline Oracle and container contracts; Task 8 preserves the real production stage path; Task 9 implements RAG/statistics; Task 10 exposes the safe UI; Tasks 11–12 create/run the actual benchmark and acceptance evidence.
- Scope: Docker/Pi integration is deliberately sequenced after the control plane because `RequirementDeliveryEngine`, `DockerPiAgentExecutor`, bridge and config currently have unrelated uncommitted edits. The plan does not overwrite them; integration begins only after their current changes are inspected and passing.
- Consistency: `replicateNo=0` is the 20×4 formal matrix; only four pre-registered cases create A/D `replicateNo=1`; all persistence uniqueness, scoring and UI references use that same definition.
