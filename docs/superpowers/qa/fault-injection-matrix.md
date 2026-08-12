# Fault Injection Matrix (WP-0)

Status: **WP-1 through WP-6 evidence recorded; Sol review pending**

Run date: 2026-08-09

Base/current HEAD: `6116570c3b6c86e53ed9beff66a34ae3e100d22b`; completion changes are uncommitted. WP-7/WP-8 are out of scope.

Every in-scope work package has at least one success case and one fault case. Test method names below are the durable repository pointers; aggregate commands and environment are in `interview-scenario-acceptance-plan.md`.

| ID | Injected fault or boundary | Expected behavior | Paired success evidence | Owning WP | Fixture / test pointer |
|---|---|---|---|---|---|
| F-PUB-01 | Push succeeds, caller sees timeout, concurrent replay follows | One remote commit/operation; exact markers permit reuse, mismatch fails closed | Reconcile resumes the operation-keyed continuation once | WP-1 | `RequirementPublicationBareGitCrashWindowAcceptanceTest#timeoutAfterPushAndConcurrentReplaysLeaveOneRemoteCommitAndOneLedgerOperation` |
| F-PUB-02 | GitHub create succeeds before local DB finalization | Open PR is found by operation/patch markers; no blind POST replay | Task snapshot, event, and publication finalize atomically | WP-1 | `RequirementPublicationCrashWindowAcceptanceTest#fPub03Github201ThenDbMissReconcileReusesOpenPr`; `PostgresRequirementPublicationContinuationRealSmokeTest#publicationCommitRollsBackTaskEventWhenLedgerFinalizationFails` |
| F-PUB-03 | Branch/PR markers conflict or remote lookup is unavailable | Conflict becomes `NEEDS_HUMAN`; unavailable lookup remains unknown and waits | Matching operation and patch markers advance reconciliation | WP-1 | `RequirementPublicationReconciliationTest` marker-conflict/unavailable cases |
| F-PUB-04 | Two instances enqueue the same reconciled operation | Unique operation-keyed continuation admits one durable command | Exactly one fenced command is claimable | WP-1 | `PostgresRequirementPublicationContinuationRealSmokeTest#concurrentOperationKeyedContinuationsPersistOneFencedStageCommand` |
| F-SEC-01 | Pi tries direct public egress on its task network | Direct egress fails; only the trusted relay sidecar has outbound access | Valid opaque lease reaches the configured upstream through the real sidecar | WP-2 | `PiRealDockerIsolationAcceptanceTest` network/relay probes |
| F-SEC-02 | Reviewer writes repo, rootfs, or unmounted Host path | Writes fail while the bounded output path remains writable | Structured output survives for collection | WP-2 | `PiRealDockerIsolationAcceptanceTest` reviewer mount probe |
| F-SEC-03 | Fork, memory pressure, or timeout exceeds limits | PID/memory boundary terminates work; timeout cleans Pi, relay, and network | Normal bounded reviewer execution settles | WP-2 | `PiRealDockerIsolationAcceptanceTest` PID/OOM/timeout probes |
| F-SEC-04 | Lease is reused across task or stage; secret is searched in diagnostics | Cross-scope request returns 401; raw secret is absent from env/inspect/log/artifact/metadata | Correct task-stage-provider lease is accepted once within policy | WP-2 | `PiRealDockerIsolationAcceptanceTest` lease-scope and secret probes |
| F-ORACLE-01 | Agent omits or tampers with frozen bundle hash or changes workspace/spec | Host rejects missing/mismatched echo and Agent-controlled execution context | Host loads canonical frozen CURRENT and REGRESSION bundles | WP-3 | `HostOwnedAssertionGateFrozenBundleTest` |
| F-ORACLE-02 | Command exits 0 but HTTP JSONPath or SQL semantic value is wrong | Assertion fails based on semantic result | Matching HTTP/SQL value passes through the same Host runner | WP-3 | `HttpJsonPathAssertionRunnerTest`; `SqlSemanticAssertionRunnerTest` |
| F-ORACLE-03 | Candidate patch digest, SQL grammar, selector, route, or probe output is hostile | Clean verifier/runner rejects before unsafe execution | Verified patch replays in separate scope workspaces; hardened browser probe returns visible route state | WP-3 | `CleanHostVerifierWorkspaceFactoryTest`; SQL/browser runner tests |
| F-CAS-01 | Worker A writes after worker B advanced version/fencing | PostgreSQL predicate rejects stale write; no stale timeline event commits | Fresh CAS updates snapshot/event and increments version/fencing | WP-4 | `RdTaskFencingConcurrencyTest`; `PostgresRdTaskStateAtomicRealSmokeTest` |
| F-CAS-02 | Requirement pause uses legacy bug-fix-only API | Regression reproduces ClassCastException after persistence, then generic API returns one coherent 200 response | Real HTTP pause returns 200; DB shows `paused=t`, version 1, fencing 2, CREATED+PAUSED events | WP-4 | `RdTaskControllerTest#shouldPauseRequirementTaskThroughAdminApi`; task `7492061439520280576` |
| F-PROV-01 | 429/5xx opens shared circuit and two instances probe HALF_OPEN | Redis grants only one HALF_OPEN lease; the other instance is denied | Success closes shared circuit | WP-5 | `RedisModelHealthStateStoreIntegrationTest#shouldAllowOnlyOneHalfOpenProbeAcrossInstances` |
| F-PROV-02 | Fallback Provider lacks tools/capability, work is high risk, or remote side effect is unknown | Return policy wait/human decision; do not silently degrade or replay | Capable Provider with explicit clean attempt and safe side-effect state may run | WP-5 | `ProviderFallbackPolicyEnforcerTest`; `ProviderFallbackSideEffectSafetyTest`; orchestrator tests |
| F-PROV-03 | Production Redis authority is unavailable | Application context fails closed instead of registering an implicit JVM state store | Explicit memory mode is allowed only for isolated tests/local memory mode | WP-5 | `ModelHealthStoreConfigurationTest` |
| F-SCHED-01 | Two workers claim the same due command; a lease expires | `FOR UPDATE SKIP LOCKED` admits one claimant; retry/dead-letter is bounded | Fresh command completes and enqueues the next bounded stage | WP-6 | `PostgresRequirementStageCommandRealSmokeTest`; `RequirementStageCommandStoreTest` |
| F-SCHED-02 | 100 mixed long tasks span five projects/priorities under 429/5xx pressure | Every active project receives service; P0 is bounded; aging serves P2; queue/retries remain bounded | Resource and queue metrics report accepted service | WP-6 | `RequirementFairSchedulingSimulationTest#servesEveryProjectWithBoundedPriorityLatencyAndBackpressure` |
| F-SCHED-03 | Project/provider/Docker/browser quota is exhausted or a local executor rejects work | Work is delayed/requeued without sleeping in a worker or losing the durable command | Recovery and another instance can complete the shared command/future | WP-6 | `RequirementDeliveryDispatchServiceTest`; `FairScheduleSelectorTest` |

## Image and protocol identity

| Artifact | Recorded identity |
|---|---|
| `rd-bot/pi-agent:local` | `sha256:65dbcfb3ccccf771b973dad541a387b7c0b34cd28dc2d168bebda7edfa1dff76` |
| `rd-bot/pi-agent-qa:local` | `sha256:4cab5c27e85fb21169ef47cdf402b97bf8b4450de5b52a3f50d504132ffd927a` |
| `rd-pi-bridge.mjs` | `332329f013825d164c48e5e60493238106cec7829d8b56d6608cd33bffc7fb8e` |
| `host-browser-probe.mjs` | `ca31db17210e908ede0b7ff535365d5cda38cfc33fafaa74df238c0bb6b232a0` |
| `protocol.mjs` | `084a4e7036cc96f0f10338429783cfb310bc8ef1588df1c9587fde93728024b3` |
| `result-tool.mjs` | `639a4af9cc1959ef5351e502350d64d278d973fb8ef60eb06274c8be7ffd5371` |

## Evidence rules

1. Re-run the referenced class against the recorded infrastructure before changing a claim to production-wide wording.
2. Keep raw credentials out of commands and reports; record variable names and redacted injection only.
3. Add a new fail/success pair before expanding an in-scope behavior.
4. Do not add WP-7/WP-8 or resume metrics to this matrix without a separately authorized run.
