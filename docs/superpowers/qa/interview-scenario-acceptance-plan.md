# Interview Scenario Acceptance Plan (WP-0)

Status: **baseline stub (trackable)** — WP-1/WP-2 partial evidence linked below  
Date: 2026-08-04  
Sources: remediation plan §4; shared interview Q1–Q12 (session transcript); do **not** invent resume metrics until experiments land.

This document freezes *what must be proven* for each interview claim. Percentages and absolute counts stay empty until linked to reproducible commands and raw artifacts.

## Run metadata (required on every experiment report)

| Field | Source |
|---|---|
| git commit | `git rev-parse HEAD` |
| config hash | hash of effective `rd.*` props used for the run |
| model / temperature | provider profile snapshot |
| dataset version | path + digest under `benchmarks/` or fixture set |
| wall time / sample n / repeats | report JSON |

## Q1–Q12 claim matrix

| Q | Claim theme (freeze wording carefully) | Fact to prove | Denominator / method | Risk if overstated | Linked WP |
|---|---|---|---|---|---|
| Q1 | End-to-end requirement delivery | Happy-path task reaches `COMPLETED` with PR URL | fixture task count | Claiming “fully automatic” before WP-1/2/3 | WP-1 |
| Q2 | Multi-agent stage orchestration | Stage runs persist with role ordering | stage-run rows per task | Hiding recovery gaps | WP-1 |
| Q3 | Idempotent remote publication | Crash after push/PR does not duplicate | fault samples in matrix | Claiming Exactly-Once without ledger | WP-1 |
| Q4 | Container / sandbox isolation | Hardened docker args + read-only mounts | `ProcessContainerRunner` / Pi executor tests | Claiming “strong sandbox” with bridge + long-lived keys | WP-2 |
| Q5 | Secret handling | Credentials not durable in container when relay on | flag + fail-closed tests | Claiming host relay before implementation | WP-2 |
| Q6 | Host-owned QA oracle | Business assertions run on host, not agent self-report | AssertionSpec + HostOwnedAssertionGate on QA path | Claiming 100% detection | WP-3 |
| Q7 | QA evidence protocol | Manifest refs console/network/trace/desktop+mobile | Host + bridge validators | Protocol crack (pass in container, fail on host) | WP-2/3 |
| Q8 | Task concurrency / CAS | Stale writers rejected | `rd_tasks.version` + cancel via `advanceStatusWithExpectedVersion` (other writers still upsert) | Claiming fencing via Redis lock only | WP-4 |
| Q9 | Provider routing / degradation | Fallback is audited and side-effect safe | routing decision logs | Silent model swap | WP-5 |
| Q10 | Fair scheduling under load | No permanent project starvation | 100 long-task scenario | “Works in demo” only | WP-6 |
| Q11 | Retrieval quality | Multi-round retrieval has stop + gain | offline eval set (TBD) | Inflated RAG claims | WP-7 |
| Q12 | Resume / metrics reproducibility | One-command reproduction of cited numbers | `benchmarks/interview-claims/` + `runners/verify-package.sh` | Editing resume before evidence | WP-8 |

## Current verified baseline (update only with commands)

| Item | Value | Command / pointer |
|---|---|---|
| Targeted publication/GitHub/Pi tests | green on remediation branch slices | see recent Maven `-Dtest=...` invocations in loop checkpoints |
| Publication ledger | PREPARED→COMMITTED + UNKNOWN reconcile + scheduler | engine + bootstrap publication packages |
| Pi container security policy | `--read-only`, cap-drop, no-new-privileges, role repo:ro | `ProcessContainerRunner` / `DockerPiAgentExecutor` |
| Credential relay | flag default OFF; ON fail-closed; yaml documented | `rd.executor.pi.credential-relay-enabled` |
| Pi/Claude network isolation | Reviewer/Architect `network=none` | `DockerPiAgentExecutor` / `DockerClaudeCodeExecutor` |
| Publication UNKNOWN reset | Absent branch → PREPARED; marker conflict → NEEDS_HUMAN | ledger + engine tests |
| Host Oracle domain + adapters | AssertionSpec hash, File/HTTP/SQL runners, Spring wiring, QA gate | `HostOwnedAssertionGateTest`, `HostAssertionOracleWiringTest` |
| Task version column + CAS API | DDL + mapper CAS; blind upsert still present | `PostgresRdTaskStoreCasTest` |
| Interview claims package | Layout + metrics rules; numeric claims stay 待复现 until raw runs | `benchmarks/interview-claims/`, `InterviewClaimsPackagePolicyTest` |
| Iterative retrieval loop | Stop gate + multi-round `retrieveIterative` (callers still single-shot) | `IterativeRetrievalLoopTest`, `DeepRetrievalOrchestratorTest#iterativeRetrievalStopsAtMaxRoundsWhenGateNeverSatisfied` |
| Provider fallback Host gate | Capability/risk gate rejects silent weak fallback after attempts | `ProviderFallbackSideEffectSafetyTest`, orchestrator `PROVIDER_FALLBACK_POLICY` |
| Task cancel CAS | Operator cancel advances status with version fencing | `RagStreamTaskRegistryCancelCasTest`, `InMemoryRdTaskStoreCasTest` |
| Fair recover in-flight | `listInFlight` feeds project caps on recovery claim batch | `RequirementDeliveryDispatchServiceTest#recoverSkipsProjectWhenInFlightAlreadyAtCap` |

## Explicit non-claims (until WP done)

- No “Exactly Once” publication without crash-window acceptance samples.
- No “strong sandbox / no secret leak” while Coding/QA still use `bridge` and relay is unimplemented (Review/Architect already `network=none`).
- No “Host Oracle / 100%检出” — gate is optional (`hostAssertionBundle`); not all criteria compiled yet.
- No full task fencing until status writers stop using blind `upsertTask`.
- Do not add unverified uplift numbers to `resume_optimized.md`.
