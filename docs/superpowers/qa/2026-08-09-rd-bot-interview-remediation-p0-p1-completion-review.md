# RD-Bot Interview Remediation P0/P1 Completion Review

Status: **WP-1 through WP-6 complete candidate; independent Sol review pending**

Review date: 2026-08-09

Frozen plan: `docs/superpowers/plans/2026-08-03-rd-bot-interview-remediation-execution-plan.md`

Completion plan: `docs/superpowers/plans/2026-08-08-rd-bot-interview-remediation-full-completion-plan.md`

## Scope decision

This completion review covers WP-1 through WP-6 only. The user removed WP-7 and WP-8 on 2026-08-09; retrieval evaluation, benchmark packaging, and resume-metric claims are neither completed nor blockers here. WP-0 contains only the evidence needed to assess WP-1 through WP-6.

Base and current HEAD are both `6116570c3b6c86e53ed9beff66a34ae3e100d22b`. There are no commits after that SHA. The completion work is present as uncommitted tracked and untracked files in the shared dirty worktree; no commit, push, reset, or cleanup was performed.

Machine-readable run evidence is stored at `docs/superpowers/qa/evidence/2026-08-09-p0-p1-acceptance-evidence.json`.

## Historical commit trace

| Commit | Contribution to the frozen plan |
|---|---|
| `851bbc81` | WP-0 baseline and interview-claims package skeleton |
| `6dea49b3` | WP-4 task status version CAS foundation |
| `1c39ea19` | WP-5 unsafe Provider fallback gate |
| `f49a9297` | WP-6 fair recovery claim foundation |
| `bce84a01` | WP-1 publication ledger, reconciliation, and branch preflight |
| `cde108ad` | WP-2 hardened Pi containers and opaque credential relay foundation |
| `3762d0d4` | WP-3 Host assertions wired into QA validation |
| `d2e73ccb` | WP-4 nullable PostgreSQL CAS parameter correction |
| `87610c4e` | WP-2 preserves required LLM egress when relay is explicitly off |
| `27a59b95` | WP-2/3 excludes QA work files from delivery artifact collection |
| `6116570c` | WP-3 QA evidence matching accepts AC-prefixed criteria |

These commits established the skeleton. The current dirty-tree implementation closes the remaining operation markers/atomic continuation, real relay isolation, frozen Oracle, full task fencing, shared Redis health, and durable fair stage-command gaps.

## Work-package result

| WP | Exit-gate result | Direct implementation and acceptance evidence | Residual wording boundary |
|---|---|---|---|
| WP-1 | Complete candidate | Operation + patch markers; unknown-remote reconciliation; atomic task/event/publication commit; operation-keyed continuation; bare-Git and real PostgreSQL tests | Claim idempotent reconciliation, not universal Exactly Once |
| WP-2 | Complete candidate | Internal task network + trusted relay sidecar; bounded opaque lease; hardened role mounts/resources; 8 real Docker probes; final image IDs | Evidence applies to the recorded images/configuration |
| WP-3 | Complete candidate | Host compiler/frozen store/hash; independent CURRENT/REGRESSION; clean replay; HTTP/SQL/file/log/browser runners; protocol parity | Screenshots remain evidence, not semantic truth |
| WP-4 | Complete candidate | Version/fencing across task lifecycle; transactional snapshot/event CAS; blind upsert restricted to creation; real PostgreSQL stale-write proof; real HTTP pause/DB proof | Ordinary metadata update of a requirement task was not part of the recorded HTTP sequence |
| WP-5 | Complete candidate | Capability/risk gate; side-effect-state preflight; clean Provider attempt; Redis shared health and single HALF_OPEN probe; fail-closed production wiring | No real external Provider call was made |
| WP-6 | Complete candidate | Persistent stage commands; `SKIP LOCKED`; deadlines/retry/dead-letter; unified submit/recovery; quotas/metrics; cross-instance future completion; 100-task simulation | Load evidence is deterministic simulation plus real claim smoke, not a wall-clock two-hour production soak |

Final status remains "candidate" until the full reactor and independent Sol review have no in-scope blocker.

## Verification ledger

| Layer | Command / surface | Result |
|---|---|---:|
| Focused P0/P1 Maven | rag, engine, exec, bootstrap slices | 239/239; 0 failure/error/skip |
| Pi protocol | `npm test` in Pi resources | 80/80 |
| Real Docker | enabled `PiRealDockerIsolationAcceptanceTest` | 8/8; 0 skipped |
| Real PostgreSQL | stage command, task atomic CAS, publication commit/continuation | 4/4 |
| Real Redis | `RedisModelHealthStateStoreIntegrationTest,ModelHealthStoreConfigurationTest` | 3/3 |
| HTTP + PostgreSQL | create/read/pause/read/timeline/material/DB snapshot and events | pass after the TDD fix below |
| Full Maven reactor, final run | `./mvnw test` | 1769 tests; 2 failures, 26 errors, 45 skipped; 0 P0/P1 failure, all residuals belong to cancelled WP-7/WP-8 |

### Real PostgreSQL

The acceptance database was `127.0.0.1:55432/rdbot_acceptance`. The real smokes proved one concurrent stage-command claimant with no reclaim after exhaustion, stale task CAS rollback/atomic event behavior, publication rollback when ledger finalization fails, and one operation-keyed continuation under concurrent enqueue.

The isolated tests explicitly selected `-Drd.executor.docker.circuit-breaker.state-store=memory`; this is a test-local lock-context setting and does not change the production Redis fail-closed default.

### Real Redis

The Redis run executed three tests with no skip. Two `ModelHealthStore` instances shared OPEN/HALF_OPEN state; the first instance acquired the probe lease and the second was denied. Configuration tests also proved Redis mode fails startup without the authoritative client and memory mode is explicit.

### Real HTTP and database side effects

The fixed lifecycle used task `7492061439520280576`:

| Check | Result |
|---|---|
| `POST /admin/rd-tasks/requirements` | 200, `status=CREATED`, `paused=false` |
| `GET /admin/rd-tasks/{id}` | 200, same task persisted |
| `POST /admin/rd-tasks/{id}/pause` | 200, `paused=true` |
| GET after pause | 200, `paused=true` |
| Timeline | 200, one CREATED and one PAUSED event |
| PostgreSQL snapshot | `status=CREATED`, `paused=t`, `version=1`, `fencing_token=2` |
| PostgreSQL artifacts | two status events and one material row with a SHA-256 content hash |

Runtime secrets were passed through environment variables and are redacted. The temporary app on port 18081 was stopped; the pre-existing app on 18080 and the shared PostgreSQL/Redis infrastructure were not touched.

## Acceptance-found defect and TDD fix

The first real pause returned HTTP 500 even though the database had committed `paused=true`. The full stack trace located the failure after persistence: `RdTaskController.pause()` called the legacy `RagStreamTaskRegistry.pause()`, which casts the result to `RdBugFixTask`. A requirement task therefore committed successfully and then threw `ClassCastException` while forming the response.

`RdTaskControllerTest#shouldPauseRequirementTaskThroughAdminApi` was added and observed failing for that exact cast. The minimal production change calls the existing generic `pauseTask()` method. The targeted test passed 1/1, the controller class passed 33/33, and the real HTTP/DB sequence above passed after rebuilding and restarting the isolated app.

## Full-reactor remediation record

The first full run reached every reactor module and executed 1769 tests: rag 155, engine 358, exec 252, skill 7, and bootstrap 997. It reported 4 failures, 26 errors, and 45 skipped tests. `RdTaskControllerTest` passed 33/33, including the new requirement-pause regression.

Three in-scope corrections followed:

1. UNKNOWN publication reconciliation mixed an injected scheduler clock with store wall time, so a repeated deadline could equal the prior deadline. Deferral now starts from `max(now, current.nextReconcileAt)`; scheduler 5/5 and the 53-test engine wave pass.
2. The scheduler-coexistence wiring test inherited PostgreSQL mode without an authoritative stage store. The test now explicitly selects memory mode; all four wiring tests pass, while the two PostgreSQL fail-closed tests remain intact.
3. All WP-1/WP-3/WP-5/WP-6 public models and implementations reported by repository policy were moved to `.model` and `.impl`. Policy output now lists only cancelled WP-7/WP-8 types.

An intermediate run then had 3 failures because `PostgresAgentStageArtifactStoreTest` assumed the fixture submitted first must win a simultaneous insert. Five isolated repetitions demonstrated the flake (3 fail, 2 pass). The test now records the actual atomic winner and proves the other hash conflicts without overwrite; repeated targeted 5/5 and class 6/6 pass. Product persistence code did not change.

The final run remains repository-red by explicit scope choice: 1769 tests, 2 failures, 26 errors, 45 skips. The two failures are package policy for `InMemoryRetrievalRoundAuditStore`, five iterative-retrieval model types, and one benchmark model type. Twenty-five errors share the missing WP-8 `CodingBenchmarkTrialStore` memory bean root; one is the WP-7 LocalPython `rag-retrieval.jsonl` fixture. No WP-1 through WP-6 test fails. These residuals are disclosed rather than fixed by expanding into the cancelled work packages.

## Image and protocol evidence

| Artifact | SHA-256 / image ID |
|---|---|
| Pi image | `sha256:65dbcfb3ccccf771b973dad541a387b7c0b34cd28dc2d168bebda7edfa1dff76` |
| Pi QA image | `sha256:4cab5c27e85fb21169ef47cdf402b97bf8b4450de5b52a3f50d504132ffd927a` |
| `rd-pi-bridge.mjs` | `332329f013825d164c48e5e60493238106cec7829d8b56d6608cd33bffc7fb8e` |
| `host-browser-probe.mjs` | `ca31db17210e908ede0b7ff535365d5cda38cfc33fafaa74df238c0bb6b232a0` |
| `protocol.mjs` | `084a4e7036cc96f0f10338429783cfb310bc8ef1588df1c9587fde93728024b3` |
| `result-tool.mjs` | `639a4af9cc1959ef5351e502350d64d278d973fb8ef60eb06274c8be7ffd5371` |

The hardened Host browser probe returned `exists=true`, `visible=true`, and `route=/` in the QA image.

## Remaining gates before final declaration

1. Compute final tracked/untracked dirty-tree digests after the evidence documents stop changing.
2. Run `git diff --check` and the final secret scan over the changed QA documents.
3. Give the frozen/completion plans, base SHA, complete diff, all acceptance evidence, and image/protocol identities to an independent read-only Sol reviewer.
4. Fix and re-test every Critical or Important finding before changing this report to complete.
