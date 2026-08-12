# Interview Scenario Acceptance Plan (WP-0)

Status: **WP-1 through WP-6 implementation and acceptance complete; independent Sol review pending**

Run date: 2026-08-09 (Asia/Shanghai)

Scope: WP-1 through WP-6 from `2026-08-03-rd-bot-interview-remediation-execution-plan.md`. WP-7/WP-8 and their benchmark or resume claims are explicitly out of scope.

This report records repository-backed evidence. It does not turn database CAS into an "Exactly Once" claim, screenshots into business truth, or focused tests into production-load claims.

Machine-readable run evidence: `docs/superpowers/qa/evidence/2026-08-09-p0-p1-acceptance-evidence.json`.

## Run metadata

| Field | Recorded value |
|---|---|
| Base and current HEAD | `6116570c3b6c86e53ed9beff66a34ae3e100d22b` |
| Branch / repository state | `main`; completion changes after the base SHA are uncommitted in a dirty worktree |
| Runtime | Maven 3.9.11; OpenJDK 23.0.2; Node v24.12.0; Docker 29.1.3 |
| Real infrastructure | PostgreSQL 16 at `127.0.0.1:55432/rdbot_acceptance`; Redis at `127.0.0.1:6379`; local HTTP app at port 18081 for the recorded run |
| Secrets | Injected through runtime environment only; values are not recorded in repository files or this report |
| Model / temperature / dataset | Not applicable to these deterministic P0/P1 integration, fault, and virtual-time runs |
| Final dirty-tree digests | Recorded in `2026-08-09-rd-bot-interview-remediation-p0-p1-completion-review.md` after documentation and final review |

## Completion evidence

| Gate | Result | Direct evidence |
|---|---:|---|
| Focused WP-1..WP-6 Maven regression | 239/239, 0 failure/error/skip | rag 11, engine 80, exec 45, bootstrap 103 |
| Pi bridge protocol | 80/80 | `cd bootstrap/src/main/resources/executor/pi && npm test` |
| Real Docker isolation | 8/8, 0 skipped | `PiRealDockerIsolationAcceptanceTest` with `-Drd.integration.pi-docker-isolation.enabled=true` |
| Real PostgreSQL | 4/4 | stage-command claim 1; task atomic CAS 1; publication atomic commit/continuation 2 |
| Real Redis | 3/3 | configuration 2; two-instance HALF_OPEN integration 1 |
| Real HTTP and DB read-back | pass after one TDD fix | POST requirement 200; GET 200; pause 200; GET 200; timeline 200; PostgreSQL task/event/material rows verified |
| Full reactor, final run | 1769 tests; 2 failures, 26 errors, 45 skipped | 0 in-scope failure; every residual is WP-7/WP-8 and remains visible below |

## Q1-Q12 claim matrix

| Q | Claim theme | Verified scoped fact | Evidence | Status |
|---|---|---|---|---|
| Q1 | Requirement delivery | Admin requirement creation, read-back, state-changing pause, timeline, and PostgreSQL side effects work through a real local app | HTTP task `7492061439520280576`; DB snapshot/events | Verified for the recorded lifecycle, not a full external PR delivery |
| Q2 | Multi-agent stage orchestration | Durable stage commands carry role/attempt/deadline/resource data and execute through bounded dispatch | `RequirementDeliveryStageExecutionTest`, dispatch/store tests | Verified |
| Q3 | Idempotent remote publication | Operation and patch markers prevent ambiguous reuse; unknown remote results reconcile without blind replay | publication crash-window, bare-Git, atomic PostgreSQL continuation tests | Verified; described as idempotent/reconciled, not universal Exactly Once |
| Q4 | Container isolation | Real containers enforce internal-network egress, read-only/rootfs/role mounts, PID/memory/timeout cleanup | 8-probe real Docker suite | Verified on the recorded images |
| Q5 | Secret handling | Pi receives an opaque bounded lease; raw Provider secret is absent from env, inspect, logs, artifacts, and metadata | real relay-sidecar probe plus executor/relay tests | Verified on the recorded images |
| Q6 | Host-owned Oracle | Host compiles/freezes bundles and evaluates CURRENT and REGRESSION in separate clean verifier workspaces | compiler, frozen-bundle, clean-workspace, runner tests | Verified |
| Q7 | QA evidence protocol | Host, bridge, and in-container validation require referenced console/network/trace/desktop/mobile evidence | Pi Node 80/80 and Host validator tests | Verified |
| Q8 | Task CAS/fencing | Stale version/fencing writes fail; snapshot and event commit atomically; real pause increments version/fencing and records one event | CAS tests, real PostgreSQL smoke, HTTP/DB evidence | Verified for in-scope task transitions |
| Q9 | Provider routing/degradation | Capability and side-effect preflight fail closed; Redis shares OPEN/HALF_OPEN and grants one probe | provider policy tests and real Redis 3/3 | Verified |
| Q10 | Fair scheduling/backpressure | Persistent commands use bounded claims, project fairness, aging, quotas, retry/dead-letter, and a 100-task simulation | stage-command tests and `RequirementFairSchedulingSimulationTest` | Verified in deterministic simulation and PostgreSQL claim smoke |
| Q11 | Iterative retrieval | No completion claim in this run | WP-7 removed by user | Out of scope |
| Q12 | Benchmark/resume metrics | No new metric or resume claim in this run | WP-8 removed by user | Out of scope |

## Reproduction commands

```bash
# Pi protocol
cd bootstrap/src/main/resources/executor/pi
npm test

# Real Docker isolation
./mvnw -pl bootstrap -am \
  -Dtest=PiRealDockerIsolationAcceptanceTest \
  -Drd.integration.pi-docker-isolation.enabled=true \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Real PostgreSQL (runtime values injected; secrets redacted)
./mvnw -pl bootstrap -am \
  -Dtest=PostgresRequirementStageCommandRealSmokeTest,PostgresRdTaskStateAtomicRealSmokeTest,PostgresRequirementPublicationContinuationRealSmokeTest \
  -Drd.integration.postgres.enabled=true \
  -Drd.executor.docker.circuit-breaker.state-store=memory \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Real Redis
./mvnw -pl bootstrap -am \
  -Dtest=RedisModelHealthStateStoreIntegrationTest,ModelHealthStoreConfigurationTest \
  -Drd.bot.redis.test=true \
  -Dsurefire.failIfNoSpecifiedTests=false test

# Full reactor
./mvnw test
```

For the HTTP run, the packaged app used PostgreSQL 55432, Redis 6379, `RD_KNOWLEDGE_STORE=postgres`, `RD_STORAGE_MODE=memory`, an explicit memory delivery queue for the isolated local acceptance process, Docker execution disabled, and port 18081. Exact sanitized HTTP/DB evidence is summarized in the completion review.

## Failure found and fixed

The first real `POST /admin/rd-tasks/{id}/pause` persisted `paused=true` but returned 500. The stack trace showed `RagStreamTaskRegistry.pause()` casting `RdRequirementTask` to `RdBugFixTask`. A new controller regression test reproduced the failure before production code changed. The controller now calls the existing generic `pauseTask()` method; the focused test, the 33-test controller class, and the real HTTP/DB lifecycle pass.

The first full-reactor run executed 1769 tests and reported 4 failures, 26 errors, and 45 skips. It exposed a mixed-clock WP-1 retry assertion, a WP-6 test configuration that contradicted the new fail-closed store rule, and package-policy violations. The production retry deadline now advances monotonically; the scheduler-coexistence test explicitly selects memory mode without weakening production; all WP-1 through WP-6 records/implementations now follow RULE `.model`/`.impl` boundaries.

A later full run exposed a separate flaky concurrent artifact-store test: the fixture named `first` was not guaranteed to win a simultaneous atomic insert. The product store was unchanged; the test now identifies the actual committed hash and proves that the different hash is rejected without overwrite. It passed 5/5 repeated runs and 6/6 for the class.

The final full run still exits non-zero, but its only two failures are the unchanged package-policy violations for WP-7 iterative retrieval and WP-8 benchmark types. Its 26 errors are 25 Spring-context cascades rooted at the missing WP-8 `CodingBenchmarkTrialStore` memory bean plus one WP-7 LocalPython `rag-retrieval.jsonl` fixture error. The user explicitly cancelled both work packages; no P0/P1 test fails in the final run, and the residuals are not hidden or relabeled as success.

## Explicit non-claims

- No universal external-system Exactly Once guarantee; the supported claim is marker-backed idempotency plus fail-closed reconciliation.
- No claim that screenshots alone prove business correctness; Host assertions remain authoritative.
- No real external Provider invocation or GitHub production write was performed in this acceptance run.
- No WP-7 retrieval uplift, WP-8 benchmark package, or resume-number claim is part of this completion.
