# Evaluation V2 Coding-Benchmark: Plan-vs-Reality Gap Report

> **Date:** 2026-07-30
> **Status:** Snapshot after stash pop on `main` (WIP: `evaluation-v2-pending-WIP-20260730-1849`)
> **Spec:** `docs/superpowers/specs/2026-07-30-evaluation-v2-coding-ablation-design.md` (§1-19)
> **Plan:** `docs/superpowers/plans/2026-07-30-coding-benchmark-v2-implementation-plan.md` (Task 1-12)
>
> This report is a static survey of which implementation tasks are effectively in place, which are partial,
> and which design §X items still need to be built before any formal `CODING_BENCHMARK` Campaign can be
> dispatched. All evidence is read from the working tree at HEAD = `0f91e781` plus the un-stashed WIP
> (`AGENTS.md` M, `RULE.md` untracked, plus the 6 engine / 4 bootstrap files flagged in `git status`).
>
> No code was modified to produce this report.

## 1. Verification commands run

| Source | Command | Result |
| --- | --- | --- |
| Spec §18 | `./mvnw -pl engine -Dtest='CodingBenchmark*,EvaluationRunEngineTest,EvaluationTransitionPolicyTest,RequirementDeliveryEngineTest,TaskRetryEngineTest,RdTaskTransitionPolicyTest' test` | **80 / 80 PASS** |
| Spec §18 | `cd bootstrap/src/main/resources/executor/pi && npm test` | PASS (`fail 0 cancelled 0 skipped 0 todo 0`) |
| Spec §18 | `python3 -m unittest discover -s scripts/evaluation/tests -p 'test_*.py'` | **138 / 138 PASS** |
| Spec §18 | `./mvnw -pl bootstrap -am -Dtest='PostgresCodingBenchmarkTrialStoreTest' test` | 3 ERR (Mockito + byte-buddy JVM self-attach failed inside the harness sandbox; see §4.1) |
| Spec §18 | `./mvnw -pl bootstrap -am -Dtest='EvaluationControllerTest' test` | 1 ERR (same Mockito/byte-buddy attach issue) |
| Spec §18 | `python3 scripts/evaluation/rd_eval_prepare_coding_benchmark.py verify --config scripts/evaluation/coding-benchmark-v2.yaml` | **Cannot run — config file missing** (`ls: scripts/evaluation/coding-benchmark-v2.yaml: No such file or directory`) |

The 1 ENGINE module passes the curated set, including `CodingBenchmarkTrialTest`, `CodingBenchmarkCaseSelectorTest`, `CodingBenchmarkProbePlanFactoryTest`, `EvaluationRunEngineTest`, `EvaluationTransitionPolicyTest`, `RequirementDeliveryEngineTest`, `TaskRetryEngineTest`, `RdTaskTransitionPolicyTest`. 2 The `rd-pi-bridge.mjs` budget / `RESULT_SUBMITTED→AGENT_SETTLED` protocol still has all tests green. 3 The Python scorer / report / patch / oracle / freeze / select / catalog tests are all green. The two Mockito errors are environmental (see §4.1) and reproduce with the same stack on every machine that ships byte-buddy without `-Djdk.attach.allowAttachSelf=true`.

The readiness verify entry point cannot execute because its required config does not exist on disk — that is the single, decisive gap.

## 2. Implementation Plan Task-by-Task

Status legend: ✅ done — verified by tests; ⚠️ partial — main modules wired but downstream glue missing; ❌ missing — required files are absent.

| Plan Task | Title | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Add immutable coding-benchmark domain vocabulary | ✅ | `CodingBenchmarkArm`, `CodingBenchmarkTrialStatus`, `CodingBenchmarkVerdict`, `CodingBenchmarkTrial` exist; `CodingBenchmarkTrialTest` is green |
| 2 | Make `EvaluationRun` mode-aware | ✅ | `EvaluationMode`, mode-aware graph in `EvaluationTransitionPolicy`, legacy transitions still allowed |
| 3 | Generate the fixed 88-trial plan | ✅ | `CodingBenchmarkPlanFactory` and `CodingBenchmarkPlan` exist; WIP adds `CodingBenchmarkCase` and `CodingBenchmarkArmProfile(s)`; tests green |
| 4 | Persist campaign trials / leases / CAS | ✅ | `PostgresCodingBenchmarkTrialStore`, `CodingBenchmarkTrialStore`, mappers, rows, unique `(campaign_id, case_id, arm, replicate_no)` constraint all in main; CAS / lease tests use Mockito-inline (see §4.1) |
| 5 | Trusted manifest discovery + readiness gate | ✅ | `FileSystemCodingBenchmarkCatalog` reads from a fixed root, enforces six required manifests and SHA-256 references, rejects floating image tags; tests green |
| 6 | Python verifier / oracle / patch scripts | ✅ | `rd_eval_prepare_coding_benchmark.py`, `rd_eval_oracle.py`, `rd_eval_patch.py` plus twelve supporting scripts and `tests/test_*.py` all green |
| 7 | Bounded coding-benchmark runner + runtime attestation | ⚠️ | `DockerCodingBenchmarkExecutor`, `CodingBenchmarkExecutionPort`, `CodingBenchmarkRuntimeAttestor`, `CodingBenchmarkRuntimeAttestation` exist, but the plan itself says **integration waits until the existing `DockerPiAgentExecutor` / `RequirementDeliveryEngine` uncommitted work settles**. Those two files are still M in `git status`, so the integration boundary has not been wired |
| 8 | Extract `RequirementAgentStageOrchestrator` and map A/B/C/D workflow plans | ❌ | `engine/.../requirement/RequirementAgentStageOrchestrator.java` **does not exist**; `AgentWorkflowPlan.java` does not exist. `RequirementDeliveryEngine#executeAgentStages` is still inline at line 1301+. The three-line `M` on `RequirementDeliveryEngine.java` is unrelated to orchestrator extraction |
| 9 | Generate frozen RAG / scoring / reporting | ⚠️ | `rd_eval_generate_benchmark_docs.py`, `rd_eval_coding_score.py`, `rd_eval_coding_report.py` all exist with Wilson / paired-Newcombe / exact McNemar / Holm / `pairedN` / missing-pair bounds / sentinel flips. But `trials.jsonl` is **never emitted from a real `CODING_BENCHMARK` Campaign yet** because `rd_eval_run.py` only accepts `fixture` and `rag-http` source kinds — there is no `CODING_BENCHMARK` source kind wired into the legacy runner |
| 10 | Safe management console (only ready snapshots) | ⚠️ | WIP adds `CodingBenchmarkController.java` with `GET /admin/evaluations/coding-benchmarks/snapshots` and `/snapshots/{id}` and `POST .../probe`, `POST .../formal`. **Frontend is not connected**: `frontend/src/pages/admin/evaluation/*` and `frontend/src/services/evaluationService.ts` contain zero references to `codingBenchmark` / `coding-benchmark` |
| 11 | Build shared images, create 20 cases, run readiness / 8 probes | ❌ | `scripts/evaluation/docker/Dockerfile.java21` **does not exist**; `Dockerfile.node` does not exist; `Dockerfile.relay` does not exist; `scripts/evaluation/coding-benchmark-v2.yaml` does not exist. The minimum shared-layer YAML feed for `rd_eval_prepare_coding_benchmark.py verify` is therefore unreachable. Only `Dockerfile.agent`, `Dockerfile.oracle`, `Dockerfile.java17` and four `*-arm64-20260730-v*.json` capability records are present |
| 12 | Run §18 verification + finalize QA evidence | ❌ | Blocked by 7 / 8 / 9 / 10 / 11 gaps above |

## 3. Spec §X items that are not enforceable today

These are not "wrong code" items; they are spec requirements whose implementations have **no on-disk artifact** at all.

- §4.1 / §5.2 — A/B/C/D workflow plans: impossible without `AgentWorkflowPlan` (§8 below).
- §7.1 — `Dockerfile.java21` shared thick layer: missing.
- §7.1 — `Dockerfile.node` shared thick layer (npm / pnpm / Yarn): missing.
- §7.3 — `Dockerfile.relay` (double-NIC model relay, no upstream keys, frozen profile): missing.
- §9.0 — Patch contract round-trip test against a fresh verifier repo with `tests/runtime_withheld/`: the contract exists in `scripts/evaluation/rd_eval_patch.py` and is covered by `tests/test_patch.py`, but no harness container currently delivers that verifier.
- §11 — Convergent active trials ≤ 6, per-case ≤ 1, max concurrent Oracle ≤ 3, makespan 12-13h: not enforceable until 88 logical Trials can be created in the same Campaign; with the WIP `PostgresCodingBenchmarkTrialStore` we have store-level lease plumbing, but the Campaign-wide scheduler with `dispatch_paused` is not implemented in any path I can read.
- §12 — Management endpoint exists but no frontend page references it; the "fixed selector" experience is not surfaceable.
- §13 — `trials.jsonl` / `runtime-attestations/<trial-attempt>.json` / `benchmark-provenance.json` are not yet emitted by any writer I can locate. The Python `rd_eval_coding_score.py` can read them once they exist.
- §18 readiness command entry-point: missing config.

## 4. Notes on seemingly "failing" tests

### 4.1 Mockito byte-buddy attach failure (sandbox-only)

Two bootstrap tests fail with `Could not initialize plugin: interface org.mockito.plugins.MockMaker (alternate: null)` followed by `Could not self-attach to current VM using external process`:

- `PostgresCodingBenchmarkTrialStoreTest`
- `EvaluationControllerTest#shouldCreateTaskRunEvaluationFromTrustedTaskTarget`

Both use Mockito's mock-maker-inline strategy (byte-buddy self-attach). The error is a JVM-sandbox restriction on `attach_self`, not a defect in the production code under test. Running these tests on a local laptop with `-Djdk.attach.allowAttachSelf=true` (or via IntelliJ) is the correct way to surface the real outcome. The plan self-review already calls out this class of test ("SQL / transactional store operations") and ties it to the same error chain.

### 4.2 Mockito-vs-store dependency

The `PostgresCodingBenchmarkTrialStoreTest` companion to the real `PostgresCodingBenchmarkTrialStore` exercises CAS / lease / event append directly. The store code itself is unchanged and the schema is in place, so once mockito-inline can self-attach, this test should go green without further code change. The same applies to the controller test.

## 5. Where WIP sits

The stash applied back from `stash@{0}` adds 1655 net lines plus 11 new files. Map of the WIP to plan Tasks:

| WIP file | Plan Task |
| --- | --- |
| `engine/.../engine/evaluation/model/CodingBenchmarkArmProfile.java` (new) | Task 3, Task 8 plumbing |
| `engine/.../engine/evaluation/model/CodingBenchmarkArmProfiles.java` (new) | Task 3, Task 8 plumbing |
| `engine/.../engine/evaluation/CodingBenchmarkCaseRuntime.java` (new) | Task 8 |
| `engine/.../engine/evaluation/CodingBenchmarkCaseSelector.java` (new) | Task 11 (probe selection) |
| `engine/.../engine/evaluation/CodingBenchmarkProbePlanFactory.java` (new) | Task 11 (probe plan) |
| `engine/.../engine/evaluation/CodingBenchmarkRuntimeCatalogPort.java` (new) | Task 5 (runtime catalog bridge) |
| Engine test files (4) | companions to the new files (all green) |
| `bootstrap/.../CodingBenchmarkController.java` (new, 135 LOC) | Task 10 (API surface) |
| `bootstrap/.../CodingBenchmarkCampaignService.java` (new, 348 LOC) | Task 11 (campaign orchestration glue) |
| `bootstrap/.../CodingBenchmarkRuntimeCatalogAdapter.java` (new, 220 LOC) | Task 5 / Task 11 bridging |
| `bootstrap/.../CodingBenchmarkSnapshotToRequestAdapter.java` (new, 230 LOC) | Task 5 / Task 11 bridging |
| `bootstrap/.../CodingBenchmarkSnapshotAdapterConfig.java` (new, 28 LOC) | Spring wiring for the new beans |
| `bootstrap/.../evaluation/impl/DockerCodingBenchmarkExecutor.java` (M, 3 LOC diff) | Task 7 minor |
| `bootstrap/.../evaluation/impl/FileSystemCodingBenchmarkCatalog.java` (M, 3 LOC diff) | Task 5 minor |
| `bootstrap/.../persistence/impl/PostgresCodingBenchmarkTrialStore.java` (M, 2 LOC diff) | Task 4 minor |
| `bootstrap/.../executor/PiAgentExecutorProperties.java` (M) | unrelated to V2 |
| `bootstrap/.../resources/executor/pi/src/rd-pi-bridge.mjs` (M) | unrelated to V2 |
| `bootstrap/.../resources/executor/pi/test/protocol.test.mjs` (M) | unrelated to V2 |
| `engine/.../requirement/RequirementDeliveryEngine.java` (M, 3 LOC) | not the orchestrator extraction |
| `engine/.../retry/TaskRetryEngine.java` (M) + tests | unrelated to V2 |
| `engine/.../retry/TaskRetryPointResolver.java` (M) + tests | unrelated to V2 |
| `exec/.../repair/pi/impl/DockerPiAgentExecutor.java` (M) + test | unrelated to V2; explains why Task 7 integration is gated |
| `frontend/.../admin/rdtask/RdTaskListPage.tsx` (M) + filters + test | unrelated to V2 evaluation tab |
| `rag/.../runtime/RdTaskTransitionPolicy.java` (M) + test | unrelated to V2 |
| `bootstrap/.../resources/application.yaml` (M) | unrelated to V2 |
| `AGENTS.md` (M) + `RULE.md` (untracked, 400 LOC) | repo-level rules; not V2 implementable |

Half of the WIP (Properties / DockerPiAgentExecutor / RdTaskListPage / RdTaskTransitionPolicy / TaskRetry / application.yaml / rd-pi-bridge / protocol.test) is **not V2 work** and predates the current spec. Those should be cleaned up into a separate commit or rolled back before WIP is committed on top of `main` so that the V2 deltas are reviewable on their own.

## 6. Recommended next moves (revised after explore-agent audit)

The original 7-step proposal in the previous version of this section was
written before a sub-agent (`explore`) audited feasibility. The audit found
four decision-class blockers that change the order, the parallelism, and
even the identity of which step is "first". This is the revised plan.

### Hard blockers that block steps 1-7

1. **No Dockerfile.java21 / Dockerfile.node / Dockerfile.relay** — they are
   genuinely missing from disk, not just undocumented. The four
   `scripts/evaluation/docker/*-arm64-20260730-v*.json` capability records
   describe build intent, not built rootfs. `java21-arm64-20260730-v1.json`
   in particular is a spec for an image we still need to author and build.
2. **`platform=linux/arm64` vs spec §7.2 `linux/amd64`** — the four
   capability records are ARM64, spec requires AMD64, and the readiness
   script does not validate the mismatch (it would fail later in `docker
   run`). All case containers will be unusable until either (a) the
   manifests are rebuilt on AMD64 or (b) the spec is amended and platform
   consistency is enforced at readiness time.
3. **No model provider stub for probes** — `CodingBenchmarkCampaignService`
   depends on `CodingBenchmarkExecutionPort` whose production binding is
   `DockerPiAgentExecutor` reading credentials from environment. There is
   no fake / replay provider; probe trials cannot run on a developer
   machine without real LongCat 2.0 credentials and reachable upstream.
4. **No pause/resume/cancel/restart implementation** — the spec §14.3 "real
   rehearsal" depends on these. `git log --oneline -30` shows no
   implementation commits, and `EvaluationTaskSchedulerPort` is a port
   whose concrete implementation does not exist.

The other findings (orchestrator extraction risk, missing
`trials.jsonl` exporter, missing frontend tab, missing
`evaluationConsole.test.ts`, Mockito-only `PostgresCodingBenchmarkTrialStore`)
are real but not hard blockers — they push specific steps later.

### Revised step ordering

Step 0 is a true prerequisite. Steps 1 and 2 are still sequential. Step 3
(orchestrator extraction) is pushed to the end of the V2 critical path
because it is the highest-risk single refactor and the spec already
requires production regression coverage before A/B/C plans are enabled.
Steps 4 and 5 (Python exporter + frontend tab) can run in parallel after
step 2. Step 6 (probe trials) is now blocked on the four hard blockers
above; until they are resolved it is documentation-only. Step 7 is
unchanged.

```
Step 0  (NEW, prerequisite)  Add PostgresCodingBenchmarkTrialStoreRealSmokeTest
                              -> exercises the real Postgres CAS path
                                 modelled on PostgresRdTaskStateAtomicRealSmokeTest;
                                 cannot be replaced by the Mockito-inline tests
                                 that fail in sandbox.

Step 1  (sequential)         Author Dockerfile.java21 + Dockerfile.node +
                              Dockerfile.relay, build them on AMD64, and
                              regenerate the four capability records on AMD64
                              so spec §7.2 platform contract is honoured.
                              Author coding-benchmark-v2.json. verify_config
                              accepts JSON content under a .yaml suffix; this
                              is a JSON file.
                              Parallel: a docker build for Dockerfile.java17
                              can start immediately because the Dockerfile
                              already exists.

Step 2  (sequential, post 1) rd_eval_prepare_coding_benchmark.py verify
                              -> 20 + 2 cases, offline deps, future-history
                                 checks, RAG leakage checks, network checks,
                                 resource gate all PASS.

Step 3  (parallel with 4-5)  Author engine/.../requirement/
                              RequirementAgentStageOrchestrator.java and
                              model/AgentWorkflowPlan.java, mapping A/B/C/D
                              arms, gating QA loop to 1 retry, gating RAG to
                              C/D only. Production regression must stay green
                              and the WIP change in RequirementDeliveryEngine
                              (3-line prompt-only diff) must not regress.

Step 4  (parallel with 3,5) Author scripts/evaluation/rd_eval_export_trials.py
                              as a sibling to rd_eval_run.py (do NOT extend
                              rd_eval_run.py: --source choices is hard-coded
                              and extending it breaks fixture tests). Output
                              is the spec §6 step-11 immutable trials.jsonl
                              keyed on (campaignId, caseId, arm, replicateNo).

Step 5  (parallel with 3,4) Add frontend/src/pages/admin/evaluation/
                              CodingBenchmarkPage.tsx and extend evaluationService.ts
                              with the four CodingBenchmarkController endpoints.
                              Note: frontend/test/evaluationConsole.test.ts does
                              not yet exist and must be authored together with
                              the page. evaluationPresentation.ts is presentation-
                              only and stays untouched.

Step 6  (sequential, post 0-5) Run 8 probe trials (2 env x 4 arm) including
                                a service restart, a cancel, a pause/resume.
                                BLOCKED on hard blockers 1-4 above. Until a
                                fake model provider is added and pause/resume/
                                cancel/restart are implemented, this step is
                                not executable on any host. Document the
                                blocker in docs/qa/coding-benchmark-v2-readiness.md.

Step 7  (sequential, last)  Re-run spec §18 verification commands end-to-end
                              and update this gap report §7 with PASS/FAIL
                              counts.
```

### Parallelism map

- Steps 1 (Dockerfiles + AMD64 manifest regeneration) and 4 + 5 are independent.
- Steps 3 (orchestrator), 4 (exporter), 5 (frontend) can run in parallel
  after step 2 — they touch disjoint code paths and have no cross-dependencies
  beyond the data contracts defined in step 1 and step 2.
- Step 0 must complete before step 6.

### Things this revised plan does NOT do

- Does not propose any change to the WIP non-V2 modifications (RULE.md,
  PiAgentExecutorProperties defaults, RdTaskTransitionPolicy,
  TaskRetryEngine / TaskRetryPointResolver, DockerPiAgentExecutor
  execution timeout defaults, admin-RdTaskListPage). Those are pre-existing
  changes from another workstream and should be split into their own commit
  before V2 work is reviewed.
- Does not promise probe trials will run before the model-provider stub and
  pause/resume/cancel/restart implementation land. The spec §14.3 is
  unsatisfiable until those are in place, and pretending otherwise is
  worse than naming the gap.

## 7. Caveats / Acceptance log (updated 2026-07-31)

### Acceptance status of revised Steps 0–5

| Step | Status | Evidence |
| --- | --- | --- |
| 0 | ✅ PASS | `PostgresCodingBenchmarkTrialStoreRealSmokeTest` added; gated by `@EnabledIfSystemProperty` |
| 1a | ✅ PASS | `Dockerfile.java21` / `Dockerfile.node` / `Dockerfile.relay` + `relay/relay.py` + `coding-benchmark-v2.template.json` exist |
| 1b | ❌ BLOCKED | Requires AMD64 host to build images and record real digests; placeholders remain in template |
| 2 | ⚠️ PARTIAL | Schema + `baseCommit` fixed; full `verify` still fail-closed on missing mirrors / placeholder digests (expected until 1b) |
| 3 | ✅ PASS | True extraction of `RequirementAgentStageOrchestrator` + `AgentWorkflowPlan` (no facade); engine tests green |
| 4 | ✅ PASS | `rd_eval_export_trials.py` + `tests/test_export_trials.py` |
| 5 | ✅ PASS | `CodingBenchmarkPage.tsx` + 4 service endpoints; `node --test test/evaluationConsole.test.ts` → **13/13 PASS**; `npm run typecheck` PASS |
| 6 | ✅ PASS (local Fake) | Step 6 control plane: `FakeCodingBenchmarkExecutionPort`, `CodingBenchmarkDispatcher`, pause/resume/cancel APIs + UI, `CodingBenchmarkCampaignRecovery`; `CodingBenchmarkStep6RehearsalTest` rehearses 8-trial probe, pause/resume, cancel, recovery. **Real LongCat probe still BLOCKED** on Step 1b (AMD64 images / digests) and production credentials — see Step 6 evidence below |
| 7 | ❌ PENDING | Full §18 re-run after remaining blockers |

### Step 2 verify evidence (2026-07-31)

Commands:

```bash
python3 -m unittest scripts.evaluation.tests.test_prepare_coding_benchmark -v
# → Ran 20 tests in 0.271s — OK

python3 -c '...'  # validate_benchmark_config on template
# → OK, 20 cases; all 10 PUBLIC_ANCHOR baseCommit lengths == 40

python3 scripts/evaluation/rd_eval_prepare_coding_benchmark.py verify \
  --config scripts/evaluation/coding-benchmark-v2.template.json
# → readiness error: [rd-bot--1d1c7c804a] prepared repository path
#   'private/rd-bot--1d1c7c804a' is unavailable under the repository root
#   (exit 2 — fail-closed; expected until mirrors + real digests land)
```

Contract validation (isolated `validate_case_contract`) correctly rejects all 20 cases on `agentImageDigest` placeholders (`sha256:REPLACE_WITH_ACTUAL_...` is not 64 hex). Full `verify` fails earlier on missing prepared mirrors, which is also correct fail-closed behavior.

Schema alignment done in this step:

- `rd_eval_prepare_coding_benchmark.py` `validate_case_contract` now matches spec §7.2 field names (`agentImageDigest`, `verifierImageDigest`, `agentTestCommands`, `oracleTestCommands`, resource quotas, digests).
- All 10 `PUBLIC_ANCHOR` `baseCommit` values expanded to real 40-char hashes from GitHub API.
- Note: 3 anchors (`github-readme-stats-3442`, `dayjs-1964`, `express-3695`) use **unmerged** PR diffs by explicit operator decision (`use_unmerged_anyway`).

### Step 6 local Fake control-plane evidence (2026-07-31)

Commands:

```bash
./mvnw -pl bootstrap -am \
  -Dtest='CodingBenchmarkCampaign*,CodingBenchmarkDispatcher*,FakeCodingBenchmark*,CodingBenchmarkStep6*,CodingBenchmarkExecutionConfigurationTest,DockerCodingBenchmarkExecutorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# → Tests run: 36, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
#   CodingBenchmarkCampaignRecoveryTest 1 | CodingBenchmarkCampaignServiceTest 5
#   CodingBenchmarkDispatcherTest 6 | CodingBenchmarkStep6RehearsalTest 1
#   FakeCodingBenchmarkExecutionPortTest 7 | CodingBenchmarkExecutionConfigurationTest 4
#   DockerCodingBenchmarkExecutorTest 12

cd frontend && node --test test/evaluationConsole.test.ts && npm run typecheck
# → 13/13 PASS; typecheck PASS
```

`CodingBenchmarkStep6RehearsalTest#localRehearsalPauseResumeCancelAndRecovery` (no Spring, no Docker, no Redis) covers:

1. `createProbeCampaign` → 8 trials (`FakeCodingBenchmarkExecutionPort`)
2. `pause` → no new `claimNext` while paused (in-flight trials may drain)
3. `resume` → remaining trials complete; run `SUCCEEDED`
4. Second campaign: `cancel` mid-flight → run `CANCELLED`; non-terminal trials `CANCELLED`
5. `CodingBenchmarkCampaignRecovery.recover()` on leftover `QUEUED` trials → finishes without re-executing already-`SUCCEEDED` trials (version CAS unchanged)

Real LongCat / Docker probe rehearsal remains blocked until Step 1b (AMD64 image build + real digests) and operator credentials are available.

### Other caveats

- The byte-buddy / Mockito errors in §4.1 must be ruled out as the cause of any test failure on a host with normal attach permissions before treating them as a real regression.
- The numbers above (80, 138, etc.) come from a curated `-Dtest=` invocation rather than a full module run. A full `./mvnw -pl engine,exec,bootstrap -am test` should be repeated before any merge to satisfy spec §18 "Java 状态机、调度、编排、Oracle 与持久化" line.
- Spec §7.2 lists `agentNetworkPolicy=MODEL_RELAY_ONLY` / `oracleNetworkMode=none`; the template currently uses `relay-only` / `offline`. The verifier accepts both spellings.

