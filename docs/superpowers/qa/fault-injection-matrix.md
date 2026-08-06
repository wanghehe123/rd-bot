# Fault Injection Matrix (WP-0)

Status: **stub**  
Date: 2026-08-04  
Purpose: map each remediation work package to at least one **fail** sample and one **success** sample. Fill “fixture / command” as experiments land.

| ID | Fault | When | Expected system behavior | Success sample | Owning WP | Fixture / command |
|---|---|---|---|---|---|---|
| F-PUB-01 | Process crash after local commit, before push | PREPARED | Resume may push once; no duplicate patch apply when head matches | Branch confirmed without re-apply | WP-1 | TBD (needs workspace apply fixture) |
| F-PUB-02 | Process crash after successful push | BRANCH_CONFIRMED | Skip push; allow create/reuse PR | Resume creates or reuses PR | WP-1 | RequirementPublicationCrashWindowAcceptanceTest#fPub02 |
| F-PUB-03 | GitHub POST 201 then DB write fails | after PR create | Ledger/PR lookup reuses open PR with markers | `findOpenPullRequest` + markers | WP-1 | CrashWindowAcceptanceTest#fPub03 + publisher adapter tests |
| F-PUB-04 | GitHub POST timeout / 5xx (PR may or may not exist) | BRANCH_CONFIRMED | `UNKNOWN_REMOTE_RESULT`; no blind replay | Scheduler/resume reconciles | WP-1 | timeout → UNKNOWN tests |
| F-PUB-05 | Push timeout (branch may or may not exist) | PREPARED | UNKNOWN; Present→BRANCH_CONFIRMED; Absent→PREPARED; Unavailable→WAIT | `findBranchHead` / resolveRemoteBranchHead | WP-1 | CrashWindowAcceptanceTest#fPub05* |
| F-PUB-06 | Open PR exists with mismatched markers | create PR | Ledger+task → NEEDS_HUMAN; do not overwrite | mismatch → FAILED_NEEDS_HUMAN | WP-1 | EngineTest#shouldEscalateNeedsHumanWhenOpenPullRequestMarkersConflict |
| F-PUB-07 | Dual instance same `operation_id` | prepare | Unique constraint / idempotent insert | single publication row | WP-1 | Postgres insertPrepared test |
| F-SEC-01 | Malicious repo reads host env secrets | Pi coding container | Long-lived key not injected when relay enabled | opaque lease + fail-closed without issuer | WP-2 | DockerPiAgentExecutorTest lease + fail-closed |
| F-SEC-02 | Agent writes outside `/work` | container run | read-only root + tmpfs | hardened docker args | WP-2 | ProcessContainerRunnerTest |
| F-SEC-03 | Reviewer/Architect/QA mutates repo | role mount | `/work/repo:ro` (Pi+Claude) | DockerPi/Claude executor tests | WP-2 | existing |
| F-SEC-04 | Default egress / model call | network mode | Review/Architect always `none`; Coding/QA `none` when relay ON, else configured (`bridge`) | role + relay network tests | WP-2 | DockerPiAgentExecutorTest network cases |
| F-ORACLE-01 | Agent self-reports pass without host assert | QA result | Host rejects missing/mutated AssertionSpec hash; missing runner UNSUPPORTED | AssertionSpecIntegrityTest + HostOwnedAssertionGateTest | WP-3 | gate + File/HTTP/SQL runners |
| F-ORACLE-02 | Missing QA evidence refs | result submit | Host + bridge pre-validation reject | protocol tests | WP-2/3 | protocol.test.mjs |
| F-ORACLE-03 | Tampered hostAssertionBundle hash | QA success path | HostOwnedAssertionGate integrity fail → QA protocol invalid | HostOwnedAssertionGateTest#shouldRejectTamperedHash | WP-3 | unit |
| F-CAS-01 | Stale task writer after concurrent update | task status write | DB version CAS rejects | PostgresRdTaskStoreCasTest + InMemoryRdTaskStoreCasTest + RagStreamTaskRegistryCancelCasTest | WP-4 | cancel path on CAS; other transitions still blind upsert |
| F-LOCK-01 | Redis lock lease expiry mid-publish | delivery lock | No silent double publication (ledger absorbs) | ledger + lock interaction TBD | WP-1/4 | TBD |
| F-PROV-01 | Provider 429 / 5xx mid-stage | role execute | Retry/degrade policy audited; high-risk never silent | ProviderFallbackGateTest + ProviderFallbackSideEffectSafetyTest + RequirementAgentStageOrchestratorTest#host_rejects_coding_fallback_onto_generation_only_provider | WP-5 | Host gate wired post-attempt; Redis half-open / clean workspace switch still TBD |
| F-SCHED-01 | 100 long tasks, mixed priority | dispatch | No permanent project starvation | FairScheduleSelectorTest + FairRequirementDeliveryClaimPlannerTest + RequirementDeliveryDispatchServiceTest#recoverSkipsProjectWhenInFlightAlreadyAtCap | WP-6 | recover() plans with listInFlight project caps |
| F-RAG-01 | Retrieval loop without stop | deep retrieval | Bounded rounds + stop condition | IterativeRetrievalStopGateTest + IterativeRetrievalLoopTest + DeepRetrievalOrchestratorTest#iterativeRetrievalStopsAtMaxRoundsWhenGateNeverSatisfied | WP-7 | stop gate + retrieveIterative; default retrieve remains single-shot |

## How to extend

1. Add a row before claiming a resume metric that depends on that fault.
2. Link “Fixture / command” to a test class or script path that is green on a recorded commit.
3. Keep fail and success samples paired for every WP acceptance report.
