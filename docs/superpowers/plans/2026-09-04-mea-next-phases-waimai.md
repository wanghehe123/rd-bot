# MEA Next Phases (Waimai Live) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close MEA phase 1 so Host `auditQa` actually promotes AC/QA/fingerprint records, prove it on the live `codex-run-test-waimai` project, flip `SHADOW` → `ENFORCE`, archive the change, then implement phases 2–7 as separate OpenSpec changes.

**Architecture:** Executor JSON stays UNTRUSTED. The only completion authority is Host `DeterministicAuditor` writing `AuditedTaskState` on `recordOutcome`/`finalize`. Phase 1 already has the store, HOST_VERIFY command, completion gate, and admin APIs. Production QA currently calls `recordClaims` only, so a task can `COMPLETED` in SHADOW with `AC-*` still `PENDING`. Later phases replace failure-text prompts with audited gaps, add `MANAGER_DECIDE`, then contract / budget / memory / model routing.

**Tech Stack:** Java 21, Spring Boot bootstrap, engine ports, Pi Docker images `rd-bot/pi-agent:local` / `rd-bot/pi-agent-qa:local`, PostgreSQL `rdbot` on `106.55.13.166`, OpenSpec, live repo `rd-bot-waimai-acceptance-20260624-141045`.

---

## 0. Paste this to the next agent

```text
你在仓库 /Users/wish233/Documents/RD-Bot（默认已在 main）继续 DreamX MEA 改造。

先读：
1. 本计划 docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md
2. RULE.md（3.5.3 任务状态/派发、Pi 段、6.x 测试）
3. AGENTS.md
4. openspec/changes/mea-audit-only-writeback/（不要从零重做 tasks 0–11）
5. docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md（阶段 2–7 决策卡）

硬约束：
- 阶段 1 未 ENFORCE 真机证明前，禁止 openspec archive mea-audit-only-writeback
- 未完成阶段 1 收口前，不要开工阶段 2–7 的业务代码
- 每个行为变化先 OpenSpec change（delta spec + design + tasks），禁止直接改 openspec/specs/
- 不要删除/改名/清空 RULE.md
- 不要提交 git，除非用户明确要求
- 不要 rsync 笔记本上的 .env.opencode.local 到云端
- 不要 GIT_SSL_NO_VERIFY
- 重启云端 JVM 用 deploy/cloud-server/start-backend.sh（按 /proc cmdline 杀 java *bootstrap-0.1.0-SNAPSHOT.jar*）。禁止 ssh 里 pkill -f 该 jar 名
- 真实验证项目固定：codex-run-test-waimai / projectId 7499721648870920192
  仓库 https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git
  云端 ubuntu@106.55.13.166 ，管理台经 nginx → 127.0.0.1:8080
- 对照任务（SHADOW 下错误完成）：7501460334566313984 COMPLETED v41 PR #31
  GET /admin/rd-tasks/7501460334566313984/audited-state
  AC-001/002/003、GATE-QA-EVIDENCE、GATE-WORKSPACE-INTEGRITY 仍为 PENDING
  GATE-BUILD/STATIC 与 ART-PR 为 COMPLETED
  10 条 audit_runs 全是 completion=INCOMPLETE（含 QA_AGENT 那条，因为它走了 recordClaims 而不是 auditQa）
- 阶段 1 没有动态 Manager。下一步由 RequirementDeliveryDispatchService.nextStageFor
  + AgentRole.requirementDeliveryOrder + roleContinuation(CODING_AGENT)→HOST_VERIFY
  Auditor 是 Host DeterministicAuditor，不是模型角色，也不是 QA 改名

当前仓库锚点（2026-09-04 main d96b3c7d）：
- 生产 QA 成功路径 RequirementDeliveryEngine.planRoleExecutionStage → attachRoleClaims
  → DeterministicAuditor.recordClaims。auditQa 只有 DeterministicAuditorTest 调用
- rd_task_audit_runs.UNIQUE(command_id)：一条 command 只能持久化一次 AuditRun
- finish() 把 stateVersion 固定 +1，store 要求连续版本。禁止 recordClaims 后再 auditQa 各 persist 一次
```

---

## 1. Live facts the next agent must not re-discover

| Item | Value |
|---|---|
| GitHub `main` | `d96b3c7d` Merge `codex/improve-requirement-pr-description` |
| Cloud VM | `ubuntu@106.55.13.166` repo `~/RD-Bot` (rsync, usually no `.git`) |
| Start | `bash ~/RD-Bot/deploy/cloud-server/start-backend.sh` log `/tmp/rd-bot-backend.log` |
| Overlay | `deploy/cloud-server/application-local.server.yaml` → `~/RD-Bot/application-local.yaml` |
| Gate | `rd.requirement-delivery.audited-writeback.gate-mode: SHADOW` (keep until Task G) |
| Submit helper | `deploy/cloud-server/run-codex-memory-live-task.py` (hardcoded one title; copy the POST body, do not reuse the same title blindly) |
| Waimai fetch | VM uses `~/mirror/waimai.git` `insteadOf` HTTPS clone; **push** still GitHub + PAT |
| Git timeout | `RD_EXECUTOR_DOCKER_GIT_TIMEOUT_SECONDS=600`, host `http.version=HTTP/1.1`, `http.postBuffer=524288000` |
| Laptop GitHub SSH | `github.com` may resolve to Clash fake-ip `198.18.0.121`; push via `https://github.com/wanghehe123/rd-bot.git` |

What “Manager / Auditor” actually did on `7501460334566313984`:

- **No Manager.** Static continuation. Operator retries via checkpoint, not `MANAGER_DECIDE`.
- **Auditor** ran for HOST_VERIFY (`auditHostVerify` → `GATE-BUILD`/`GATE-STATIC` COMPLETED).
- QA attempt persisted 18 `rd_qa_evidence_objects` and a `QA_AGENT` AuditRun that only contains UNTRUSTED `CLAIM-*` rows (`status=SUCCESS`, `testStatus=PASSED` as claims).
- SHADOW log: `audited completion gate would reject … gaps=[AC-001, AC-002, AC-003, GATE-QA-EVIDENCE, GATE-WORKSPACE-INTEGRITY]` then still published and `COMPLETED`.
- Under **ENFORCE** this same head would be **REJECTED** at `DETERMINISTIC_REVIEW` / `COMPLETION`. That is the desired phase-1 exit, but only after `auditQa` is wired so an honest QA pass can promote those records.

---

## 2. File map

| Path | Responsibility |
|---|---|
| `engine/src/main/java/com/wish/rd/engine/requirement/audit/DeterministicAuditor.java` | Pure auditor. Add one-finish QA+claims API. Do not persist two revisions per command. |
| `engine/src/main/java/com/wish/rd/engine/requirement/audit/QaSubject.java` | Fingerprints + CURRENT acceptances |
| `engine/src/main/java/com/wish/rd/engine/requirement/audit/QaCurrentAcceptance.java` | `criteriaId` / evidence refs |
| **Create** `engine/.../audit/QaSubjectExtractor.java` | Parse result JSON `acceptanceResults[]` (CURRENT only) + `dockerMetadata.qaWorkspaceFingerprint*Json` |
| `engine/.../RequirementDeliveryEngine.java` | `planRoleExecutionStage` QA success: one `auditQa` mutation; other roles stay `recordClaims` |
| `exec/.../qa/QaExecutionMetadataKeys.java` | Fingerprint key names (do not invent new keys) |
| `bootstrap/.../executor/pi/Dockerfile` + `Dockerfile.qa` | Rebuild after any `result-tool.mjs` / prompt contract edit |
| `deploy/cloud-server/application-local.server.yaml` | SHADOW overlay; delete `gate-mode` line to return to default ENFORCE |
| `openspec/changes/mea-audit-only-writeback/` | Finish 8.5 / 12.x then archive |
| Later changes | `mea-fresh-executor-episode`, `mea-manager-decision-command`, `mea-stable-contract-and-backcheck`, `mea-budget-and-recovery-governance`, `mea-audited-memory-promotion`, `mea-role-model-routing`, `mea-baseline-evaluation` |

---

## 3. Hard sequence

```text
A 接线 auditQa（本计划 Task 1–4）
B 重建两个 Pi 镜像（Task 5）
C 云端仍 SHADOW，外卖 ≥5 真任务含 1 个故意破构建（Task 6）
D 核对 SHADOW「本应拒绝」vs 人工判定，误拒率 < 20% 才继续
E 去掉 SHADOW 覆盖 → ENFORCE（Task 7）
F 再跑破构建 + 一条诚实任务（Task 7）
G unaudited_claim_promoted_to_completed = 0 后才 archive（Task 8）
H 阶段 0 基线可与 C 并行，但不阻塞 A–G
I 阶段 2 起每个阶段独立 OpenSpec change，阶段退出条件必须再用外卖真任务证明
```

Do not start phase 2 because “HOST_VERIFY already works”. Phase 2’s gap prompt is useless until AC/QA records can become `COMPLETED`.

---

### Task 1: Failing test — QA success plan calls `auditQa`, not only `recordClaims`

**Files:**
- Modify: `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java` (or the existing stage-execution test that already stubs a QA_AGENT success; prefer extending that over a new umbrella)
- Test also: `engine/src/test/java/com/wish/rd/engine/requirement/audit/DeterministicAuditorTest.java`

- [ ] **Step 1: Write the failing production-path test**

Assert a successful `ROLE_EXECUTION:QA_AGENT` plan’s `auditedStateMutation`:

1. `auditRun.subjectRole == "QA_AGENT"`
2. `nextState.record("AC-001").status() == COMPLETED` when CURRENT `acceptanceResults` has `criteriaId=AC-001`, `status=PASSED`, `exitCode=0`, non-empty resolvable evidence
3. `GATE-QA-EVIDENCE` and `GATE-WORKSPACE-INTEGRITY` COMPLETED when before/after fingerprint JSON match
4. Executor `testStatus=PASSED` still appears only as `CLAIM-*` UNTRUSTED
5. Mutation count for that command is **one** (`UNIQUE(command_id)` still holds)

Fixture result JSON must include both the QA report **and** docker metadata keys from `QaExecutionMetadataKeys`:

```json
{
  "status": "PASSED",
  "testStatus": "PASSED",
  "acceptanceResults": [
    {
      "criteriaId": "AC-001",
      "criteria": "client 生产构建通过",
      "scope": "CURRENT",
      "status": "PASSED",
      "exitCode": 0,
      "logArtifactId": "qa-evidence/console/ac001.log",
      "evidenceArtifactIds": ["qa-evidence/console/ac001.log"]
    }
  ],
  "dockerMetadata": {
    "qaWorkspaceFingerprintBeforeJson": "{\"headSha\":\"abc\",\"trackedTreeSha256\":\"sha256:aaa\",\"trackedFileCount\":3}",
    "qaWorkspaceFingerprintAfterJson": "{\"headSha\":\"abc\",\"trackedTreeSha256\":\"sha256:aaa\",\"trackedFileCount\":3}",
    "qaWorkspaceIntegrity": "CLEAN"
  }
}
```

Use the same nested-envelope handling as `PiQaRemediationPlanner.authoritativeQaResultJson` (`{"stages":[{"role":"QA_AGENT","resultJson":"..."}]}`).

- [ ] **Step 2: Write the failing auditor composition test**

In `DeterministicAuditorTest`:

```java
@Test
void qaAuditFoldsUntrustedClaimsInOneRevision() {
    // given HOST_VERIFY already completed GATE-BUILD/STATIC
    // when auditQaWithClaims(head, qaSubject, claimSubject, resolver)
    // then stateVersion == head + 1
    // and AC-001 COMPLETED
    // and CLAIM-* UNTRUSTED present
    // and auditRun.commandId equals the QA command id exactly once
}
```

Do **not** implement by calling `recordClaims` then `auditQa` and persisting both. `finish()` always does `head.stateVersion()+1`; the store requires contiguous versions; `rd_task_audit_runs.UNIQUE(command_id)` forbids two runs.

- [ ] **Step 3: Run the new tests and confirm they fail**

```bash
./mvnw -pl engine -am -Dtest=DeterministicAuditorTest,RequirementDeliveryEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because `planRoleExecutionStage` still goes through `attachRoleClaims` only, and `auditQaWithClaims` does not exist.

---

### Task 2: Extract `QaSubject` from executor JSON

**Files:**
- Create: `engine/src/main/java/com/wish/rd/engine/requirement/audit/QaSubjectExtractor.java`
- Create: `engine/src/test/java/com/wish/rd/engine/requirement/audit/QaSubjectExtractorTest.java`

- [ ] **Step 1: Write extractor tests first**

Cover:

- CURRENT + PASSED + exitCode 0 + `criteriaId` in `{AC-001,…}` → one `QaCurrentAcceptance`
- REGRESSION rows ignored for promotion (do not complete AC-* from REGRESSION)
- missing fingerprints → `fingerprintBefore/After == null` (auditor then `SUSPECT`, no promotion)
- mismatched fingerprints parsed as two receipts (auditor `VIOLATION`)
- evidence URIs: prefer persisted object form `qa-evidence://objects/{id}` when the Host already stored `rd_qa_evidence_objects`; otherwise keep the artifact path and let the resolver fail closed
- nested aggregate envelope via `authoritativeQaResultJson`

- [ ] **Step 2: Implement the extractor**

Parse `dockerMetadata` keys **only** from `QaExecutionMetadataKeys` (`qaWorkspaceFingerprintBeforeJson`, `qaWorkspaceFingerprintAfterJson`, `qaWorkspaceIntegrity`). Map fingerprint JSON fields `headSha`, `trackedTreeSha256`, `trackedFileCount` onto `WorkspaceFingerprintReceipt`.

For evidence refs, stamp `EvidenceSourceKind.QA_EVIDENCE` and a sha256 if the object row has one; Host `InMemoryEvidenceRefResolver.put(...)` every ref you emit, same pattern as `RequirementDeliveryEngine.hostVerifyEvidence`.

- [ ] **Step 3: Run extractor tests**

```bash
./mvnw -pl engine -am -Dtest=QaSubjectExtractorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS.

---

### Task 3: One-revision `auditQaWithClaims` + production wiring

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/audit/DeterministicAuditor.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java` (`attachRoleClaims` / `planRoleExecutionStage`)
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java` only if the legacy `executeAgentStages` path also writes audited state for QA (it already calls `auditHostVerify`; keep the same one-mutation rule)

- [ ] **Step 1: Implement `auditQaWithClaims`**

Sketch (must be a **single** `finish()`):

```java
public AuditMutation auditQaWithClaims(
        AuditedTaskState head,
        QaSubject qaSubject,
        RoleClaimSubject claims,
        EvidenceRefResolverPort resolver
) {
    requireHead(head);
    List<AuditedRecord> records = new ArrayList<>(head.records());
    List<String> untrusted = new ArrayList<>();
    appendUntrustedClaims(records, claims, untrusted); // same CLAIM-{stageRunId}-{n} rule as recordClaims
    // then the existing auditQa body against `records`, not a second finish()
    // integrityOf / fingerprint / AC-* / GATE-QA-EVIDENCE unchanged
    return finish(head, qaSubject.auditRunId(), qaSubject.commandId(), qaSubject.qaStageRunId(),
            "QA_AGENT", records, verified, untrusted, blockers, integrity, now);
}
```

Reuse `recordClaims` claim-id format so admin UI and the live task’s existing `CLAIM-*` rows stay comparable.

- [ ] **Step 2: Wire production**

In `RequirementDeliveryEngine.planRoleExecutionStage`, after a **successful** QA execution, replace `attachRoleClaims(...)` with a QA-specific attach that:

1. Builds `RoleClaimSubject` via existing `RoleClaimExtractor.fromResultJson`
2. Builds `QaSubject` via `QaSubjectExtractor`
3. Registers evidence + fingerprint refs on an `InMemoryEvidenceRefResolver` (same as `attachHostVerifyAudit`)
4. Sets `plan.withAuditedStateMutation(auditor.auditQaWithClaims(...).toPlanMutation())`

Non-QA roles stay on `recordClaims`.

QA **product-remediation** success path (`PiQaRemediationIntent`) must also attach an audit mutation (even if ACs stay PENDING). Missing mutation on a succeeded command is a protocol crack.

- [ ] **Step 3: Re-run Task 1 tests**

```bash
./mvnw -pl engine -am -Dtest=DeterministicAuditorTest,QaSubjectExtractorTest,RequirementDeliveryEngineTest,RequirementDeliveryStageExecutionTest,RequirementAgentStageOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. A test that previously showed QA `testStatus=PASSED` promoting completion must still fail closed without `auditQa` evidence.

---

### Task 4: Source guard — `auditQa` has a production call site

**Files:**
- Modify or create: `bootstrap/src/test/java/com/wish/rd/bootstrap/RequirementCompletionWriterPolicyTest.java` (or a sibling `AuditedAuditorProductionCallSiteTest`)

- [ ] **Step 1: Write a source-scan test**

Assert `engine/src/main/java/**/*.java` contains `new DeterministicAuditor` / `.auditQa` or `.auditQaWithClaims` **outside** `audit/` itself. Today only `auditHostVerify` and `recordClaims` have production callers (`RequirementDeliveryEngine`, `RequirementAgentStageOrchestrator`).

- [ ] **Step 2: Run it**

```bash
./mvnw -pl bootstrap -am -Dtest=RequirementCompletionWriterPolicyTest,AuditedAuditorProductionCallSiteTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS after Task 3.

- [ ] **Step 3: Focused regression suite from the change design**

```bash
./mvnw -pl engine -am -Dtest='AuditedTaskState*Test,DeterministicAuditorTest,AuditedCompletionGateTest,RequirementStageExecutionPlanCodecTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl exec -am -Dtest='DockerPiAgentExecutorTest,QaEvidenceBundleValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest='PostgresAuditedTaskStateStoreTest,AuditedStateFinalizationWriterTest,PostgresRequirementStageFinalizationAdapterTest,RequirementDeliveryDispatchServiceTest,RdTaskAuditedStateControllerTest,RequirementCompletionWriterPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd bootstrap/src/main/resources/executor/pi && npm test
```

Expected: PASS.

---

### Task 5: Rebuild both Pi images on the cloud VM (change task 8.5)

**Files:**
- `bootstrap/src/main/resources/executor/pi/Dockerfile`
- `bootstrap/src/main/resources/executor/pi/Dockerfile.qa`
- Images: `rd-bot/pi-agent:local`, `rd-bot/pi-agent-qa:local` (`application.yaml` `rd.executor.pi.image` / `qa-image`)

Prompt / `result-tool.mjs` / `QaEvidenceBundleValidator` must stay lockstep on CURRENT `criteriaId`. If you touched any of those in Task 3, rebuild **before** live tasks. If you did not touch them, still rebuild once so the VM is not serving a stale in-container contract from an older `rsync`.

- [ ] **Step 1: Sync `main` to the VM and rebuild**

From the laptop (do **not** copy `.env.opencode.local`):

```bash
rsync -az \
  --exclude '.git' --exclude 'node_modules' --exclude 'target' \
  --exclude 'frontend/node_modules' --exclude 'application-local.yaml' \
  --exclude '.env.opencode.local' --exclude '.env.*.local' \
  --exclude 'tmp-codex-memory-run' \
  ./ ubuntu@106.55.13.166:~/RD-Bot/

./mvnw -pl bootstrap -am -DskipTests package
rsync -az bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar \
  ubuntu@106.55.13.166:~/RD-Bot/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar
```

On the VM:

```bash
cd ~/RD-Bot/bootstrap/src/main/resources/executor/pi
docker build -t rd-bot/pi-agent:local .
docker build -f Dockerfile.qa -t rd-bot/pi-agent-qa:local .
docker images --digests | grep 'rd-bot/pi-agent'
bash ~/RD-Bot/deploy/cloud-server/start-backend.sh
# wait for: Started RdBotApplication
curl -sf -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8080/admin/rd-tasks/7501460334566313984/audited-state
```

Expected: HTTP 200. Record both image digests in the change `tasks.md` 8.5 checkbox.

Keep `gate-mode: SHADOW` in `application-local.server.yaml` until Task 7.

---

### Task 6: Waimai SHADOW battery (change task 12.1) — five real tasks

Project is **always** `codex-run-test-waimai` / `7499721648870920192`. Submit via `POST /admin/rd-tasks/requirements` then `POST /admin/rd-tasks/{id}/submit` (copy `deploy/cloud-server/run-codex-memory-live-task.py` `submit_requirement`, change title/AC/materials). Poll `GET /admin/rd-tasks/{id}` and `GET /admin/rd-tasks/{id}/audited-state`. Save JSON under `tmp-mea-waimai-shadow/` (gitignored; do not commit).

Acceptance criteria for **this** task (SHADOW, after `auditQa` wiring):

| # | Title (create new; do not retry 7501460334566313984) | Hidden / Host check | Pass when |
|---|---|---|---|
| W1 | 顾客首页展示 SHADOW-AC-WIRE 标记 | 诚实小改：顾客首页静态文案 + client build + `/health` | `COMPLETED` **and** head `AC-001/002/003=COMPLETED`, `GATE-QA-EVIDENCE=COMPLETED`, `GATE-WORKSPACE-INTEGRITY=COMPLETED`, `GATE-BUILD/STATIC=COMPLETED`, `ART-PR=COMPLETED`. QA `AuditRun.completion=COMPLETE` (or INCOMPLETE only if a real AC failed — then task must not reach COMPLETION under the would-reject log). |
| W2 | 购物车页展示 CART-LIVE-RUN 标记 | 另一页面，避免和 W1 PR 冲突 | same record-promotion rule as W1 |
| W3 | server 健康检查文案保持可探活 | 尽量 docs/health 小改 | same |
| W4 | **故意破构建**：acceptance 仍写「client 生产构建通过」，材料要求 Coding 在 `client` 引入必然 `npm run build` 失败的语法错误且不得修复 | HOST_VERIFY must fail | 任务 **不得** `COMPLETED`。存在 `HOST_VERIFY` command + `AuditRun` `subjectRole=HOST_VERIFY`。`GATE-BUILD` 非 COMPLETED。无成功 QA command（或 QA 未跑）。SHADOW 日志含 `audited completion gate would reject` 若有人误点完成。 |
| W5 | QA 只覆盖部分 AC：三条 AC，其中第三条是「管理后台不存在的路由也必须有截图」这类当前外卖前端做不到、QA 应 FAILED/PENDING 的项 | 制造缺口 | SHADOW 下旧完成权可能仍 `COMPLETED`（记下来）。Head 必须留下至少一条 `AC-*=PENDING`。这是阶段 3 Manager 的对照基线，阶段 1 不修调度。 |

Also re-read the historical task (not a new run):

```bash
curl -sS http://127.0.0.1:8080/admin/rd-tasks/7501460334566313984/audited-state
curl -sS http://127.0.0.1:8080/admin/rd-tasks/7501460334566313984/audit-runs
```

That head is the **negative fixture**: ENFORCE must reject it. Do not “fix” it by rewriting history.

SHADOW pass bar:

- ≥5 submitted live tasks on this project
- ≥1 intentional build break (W4) never `COMPLETED`
- ≥1 honest UI task (W1/W2/W3) whose head actually shows AC/QA/fingerprint **COMPLETED** (this is the proof `auditQa` is wired; without it you are not allowed to flip ENFORCE)
- Save taskIds, PR numbers, `state_version`, `state_hash`, and the would-reject log lines
- If SHADOW would-reject rate on tasks you judge truly done is **> 20%**, stop and fix the auditor (criteriaId lockstep, fingerprint false VIOLATION, evidence URI mismatch). Do not flip ENFORCE

Tick `openspec/changes/mea-audit-only-writeback/tasks.md` 12.1 only after the table is filled with real ids.

---

### Task 7: Flip ENFORCE and repeat W4 + one honest task (change task 12.2)

**Files:**
- Modify: `deploy/cloud-server/application-local.server.yaml` — remove the `gate-mode: SHADOW` override so code default `ENFORCE` applies (`RequirementDeliveryAuditedWritebackProperties`)

- [ ] **Step 1: Restart with ENFORCE**

```bash
# on VM, after editing server yaml (or rsync)
grep -n gate-mode ~/RD-Bot/deploy/cloud-server/application-local.server.yaml \
  ~/RD-Bot/application-local.yaml
bash ~/RD-Bot/deploy/cloud-server/start-backend.sh
```

Expected: overlay no longer forces SHADOW; backend log on a would-reject task does **not** continue to PUBLICATION.

- [ ] **Step 2: Live ENFORCE battery**

| # | Task | Pass when |
|---|---|---|
| E1 | Repeat W4 (new task, same intentional build break) | Never `COMPLETED`. Status `EXECUTING` / `FAILED_NEEDS_HUMAN` / `FAILED_RETRYABLE` with HOST_VERIFY failure. **No** QA success command. `rd_task_completion_bindings` empty. |
| E2 | Repeat W1-style honest small UI change (new title) | `COMPLETED`. Binding row exists. PR body first section is the audit checklist (`RequirementPullRequestBodyRenderer`). Head blocking records all `COMPLETED`. `GET /admin/rd-tasks/{id}/audited-state` matches `rd_task_completion_bindings.state_hash`. |
| E3 | Optional: replay “would the historical 7501460… have completed?” | Do not mutate that row. Mentally / by unit test: `AuditedCompletionGate` on a fixture cloned from its PENDING ACs returns reject. |

Metric: `unaudited_claim_promoted_to_completed = 0` (Executor `testStatus=PASSED` alone never writes `COMPLETED`).

- [ ] **Step 3: Tick 12.2** only with real taskIds in `tasks.md` or the PR/plan notes.

---

### Task 8: Archive phase 1 (change tasks 12.3–12.4)

Do this **only** after Task 7 E1+E2 pass.

```bash
# 12.3 design「验证命令」全集（本机）
./mvnw -pl engine -am -Dtest='AuditedTaskState*Test,DeterministicAuditorTest,AuditedCompletionGateTest,RequirementStageExecutionPlanCodecTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,TaskRetryPointResolverTest,AgentRemediationCoordinatorTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl exec -am -Dtest='DockerPiAgentExecutorTest,QaEvidenceBundleValidatorTest,AgentRoleResultValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest='PostgresAuditedTaskStateStoreTest,AuditedStateFinalizationWriterTest,PostgresRequirementStageFinalizationAdapterTest,RequirementDeliveryDispatchServiceTest,RequirementStageCommandFactoryTest,RdTaskAuditedStateControllerTest,RequirementCompletionWriterPolicyTest,TransactionalProxyPolicyTest,PiAgentRemediationSqlPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test
cd bootstrap/src/main/resources/executor/pi && npm test
cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

Then:

```bash
# follow .claude/skills/openspec-archive-change/SKILL.md
openspec archive mea-audit-only-writeback
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

`openspec/specs/requirement/audit-only-writeback/spec.md` may contain only behavior you just proved. Do not copy phase 2–7 wishes into the archived spec.

---

### Task 9: Phase 0 baseline (parallel with Task 6, does not change delivery)

**Change:** `mea-baseline-evaluation` (docs + eval scripts only).

- [ ] Create the OpenSpec change (`openspec new change mea-baseline-evaluation`).
- [ ] Freeze thresholds from transformation-plan §6.4 **before** looking at ENFORCE results.
- [ ] Task set: ≥12 waimai requirements on `codex-run-test-waimai`, each with a **hidden** checker (script or Host assertion) the agent cannot see. Cover: multi-file edit, late hidden constraint, easy false-complete (copy-only), approval, kill JVM mid-run, stale project memory, checkpoint resume, wrong branch, non-goal preservation.
- [ ] Run the same set ≥3 times. Classify failures: product / agent / infra / harness.
- [ ] Exit: the set is repeatable; `false_complete_rate` of R0 (shape-review era) vs R1 (ENFORCE) can be computed. W4/E1 are the false-complete probes.

Do not change dispatcher or auditor here.

---

### Task 10: Phase 2 — Fresh Executor episode (`mea-fresh-executor-episode`)

Depends on archived (or at least ENFORCE-proven) phase 1.

**Files:**
- Modify: `engine/.../RequirementAgentStageOrchestrator.java` `previousFailureFeedbackSection` (~2601)
- Modify: `engine/.../RequirementDeliveryEngine.java` `previousFailureFeedbackSection` (~5127) — keep both in lockstep
- Modify: compact handoff builder (`environmentNotes` / `facts[]` wording around `RequirementAgentStageOrchestrator` ~1751–1812, 2083–2085)
- Enable QA provider-attempt isolation: un-`@Disabled` the two tests in `exec/.../DockerPiAgentExecutorTest` and wire `createProviderAttempt`

- [ ] OpenSpec change first. Delta: new episode prompt contains audited gaps only.

Replacement section (bound: ≤16 gap ids, ≤2000 chars, no raw `errorMessage`):

```text
# 已审计缺口（Host）
state_version: {n} hash: {sha256:…}
missing: AC-003, GATE-QA-EVIDENCE
blockers: (empty)
untrusted: CLAIM-{stageRunId}-1
evidence: qa-evidence://objects/…   # URIs only
禁止把 UNTRUSTED 声明当已验证事实。原始 errorMessage 只在 RESULT_JSON / AGENT_EVENTS。
```

Delete prompt text `已实测验证，直接沿用`. Verified facts may be labeled `VERIFIED(auditRunId=…)`.

**Waimai acceptance (P2):**

| Id | Experiment | Pass when |
|---|---|---|
| P2-W1 | Start an honest waimai UI task. During CODING, `kill` the jar via `/proc` cmdline (same as `start-backend.sh`), restart, let dispatcher reclaim | New CODING `PROMPT_SNAPSHOT` artifact does **not** contain the previous `errorMessage` substring (grep the snapshot). Task still completes under ENFORCE. |
| P2-W2 | HOST_VERIFY_FIX retry after a real build failure you then allow Coding to fix | Prompt gap list contains `GATE-BUILD`, not the truncated javac dump. |
| P2-W3 | QA protocol retry | Snapshot has no previous QA stderr wall; hash-bound remediation JSON may remain. |

Unit: `RequirementAgentStageOrchestratorTest` asserts the section builder; `RequirementDeliveryEngineTest` same.

---

### Task 11: Phase 3 — Dynamic Manager (`mea-manager-decision-command`)

Depends on phase 1 state + preferably phase 2 prompts.

Frozen decisions (do not reopen):

- New durable stage `MANAGER_DECIDE`, role `REQUIREMENT_DELIVERY`
- Engine pure function + store port only (no `RepairExecutorPort`, no workspace)
- Decision persisted `rd_task_manager_decisions UNIQUE(task_id, round_no)`, idempotent on `state_version`
- `ASK` → **new** `RdTaskStatus.WAITING_USER_INPUT` (not `WAITING_APPROVAL`)
- BugFix graph must not gain this status (RULE.md 3.5.3)
- 3.0 = deterministic replay of today’s order + remediation whitelist; 3.1 = may insert diagnostic/repair/re-audit

**Waimai acceptance (P3):** the reason this phase exists, taken from live W5:

| Id | Experiment | Pass when |
|---|---|---|
| P3-W1 | New task with AC-001 build, AC-002 homepage marker, AC-003 **an extra CURRENT criterion QA can fail** (route that does not exist). Do **not** pre-implement AC-003 | After QA, Manager must enqueue a **bounded** `ROLE_EXECUTION:CODING_AGENT` whose contract names `AC-003`, then `HOST_VERIFY` + QA + `auditQa`. Must **not** jump to `DETERMINISTIC_REVIEW` while `AC-003` is PENDING. |
| P3-W2 | `NEED_INFO` mid-run (omit a required material, or Manager ASK) | Status `WAITING_USER_INPUT`. `POST /admin/rd-tasks/{id}/answer` then `USER_ANSWER_RESUME`. Dispatcher does not claim role commands while waiting. Front-end badge ≠ `WAITING_APPROVAL`. |
| P3-W3 | `paused=true` | Dispatcher does not claim. |

3.0 may ship first if 3.1 is not ready, but **P3-W1 is the phase exit**. A Manager that only echoes `requirementDeliveryOrder()` is not done.

---

### Task 12: Phase 4 — Stable contract + backcheck (`mea-stable-contract-and-backcheck`)

**Waimai acceptance (P4):**

| Id | Experiment | Pass when |
|---|---|---|
| P4-W1 | Task expectedResult lists homepage marker **and** “下单主路径不可回归”. Coding only changes copy; unit/HOST_VERIFY green; QA CURRENT misses checkout | `contractAudit` not ALIGNED **or** AC for checkout PENDING → not `COMPLETED`. |
| P4-W2 | While `EXECUTING`, `POST` extra material that adds a new AC | Contract version +1; Manager `REPLAN` or `ASK`; old plan digest must not complete against new contract. |
| P4-W3 | `AiDeliveryReviewEngine` inputs | Test whitelist: original request + contract + audited head + executor **summary**. Fail the test if `PROMPT_SNAPSHOT` or `AGENT_EVENTS` appear in the model payload. |

`ART-PR` must include GitHub PR exists + branch contains the commit + body marker (already partially true for PR #31; encode as gate evidence, not shape review).

---

### Task 13: Phase 5 — Budget and recovery (`mea-budget-and-recovery-governance`)

**Waimai acceptance (P5):**

| Id | Experiment | Pass when |
|---|---|---|
| P5-W1 | Kill JVM after QA container exited but before finalize | Reclaim reconciles persisted result; no second QA container; no duplicate GitHub PR. |
| P5-W2 | Kill during `git push` / PUBLICATION (GnuTLS -110 already seen on this VM) | Publication ledger replay; one PR; task can still `COMPLETED` under ENFORCE if gates pass. |
| P5-W3 | Tiny token/turn budget on Reviewer | `BUDGET_EXHAUSTED` provenance; head retained; not `COMPLETED`. |
| P5-W4 | N consecutive rounds with identical `state_hash` and no new `verified[]` | Manager `BLOCKED` or `ASK`, not infinite HOST_VERIFY_FIX. |

---

### Task 14: Phase 6 — Audited memory promotion (`mea-audited-memory-promotion`)

Production finalize still uses `ProjectMemoryOperationDraft.none()`; worker handler is stub; live export for this project was empty. INSERT defaults (`redacted=FALSE`, confidence 0) contradict search predicates.

**Waimai acceptance (P6):**

| Id | Experiment | Pass when |
|---|---|---|
| P6-W1 | After E2-style COMPLETED | `rd_project_memories` non-empty; revision has `auditRunIds`; export JSON via the existing `export_memory()` in `run-codex-memory-live-task.py` |
| P6-W2 | Seed a false ACTIVE memory “QA 可跳过构建” | Next waimai task still runs HOST_VERIFY + QA; cannot mark `AC-*` COMPLETED from memory alone |
| P6-W3 | Retrieval hits | Injected as `UNTRUSTED_PROJECT_MEMORY` / hint, never satisfy the role evidence gate by themselves |

---

### Task 15: Phase 7 — Role model routing (`mea-role-model-routing`)

Only after phase 0 numbers exist.

**Waimai acceptance (P7):** same ≥12-task set as phase 0. Exit: end-to-end pass rate ≥ R0, `false_complete_rate` ≤ 50% of R0, median cost/success ≤ 1.8× R0, `unaudited_claim_promoted_to_completed=0`.

---

## 4. Ablation mapping (do not skip)

| Group | Meaning | When you may claim it |
|---|---|---|
| R0 | Shape review, no auditQa | Historical `7501460334566313984` + pre-wiring SHADOW |
| R1 | Phase 1 ENFORCE | After Task 7 |
| R2 | + fresh episode | After Task 10 P2-W1 |
| R3 | + Manager | After Task 11 P3-W1 |
| R4 | + contract backcheck | After Task 12 P4-W1 |

Budget-limited compare R0 / R1 / R3 as in the transformation plan.

---

## 5. Commands cheat sheet (cloud)

```bash
# audited head
curl -sS http://127.0.0.1:8080/admin/rd-tasks/$TASK/audited-state | python3 -m json.tool | head
curl -sS http://127.0.0.1:8080/admin/rd-tasks/$TASK/audit-runs | python3 -m json.tool | head

# postgres on VM
docker exec rd-bot-postgres psql -U postgres -d rdbot -c \
  "SELECT id, status FROM rd_task_audited_state_heads WHERE task_id=$TASK;"
docker exec rd-bot-postgres psql -U postgres -d rdbot -c \
  "SELECT subject_role, completion, integrity, command_id FROM rd_task_audit_runs WHERE task_id=$TASK ORDER BY created_at;"
docker exec rd-bot-postgres psql -U postgres -d rdbot -c \
  "SELECT * FROM rd_task_completion_bindings WHERE task_id=$TASK;"

# SHADOW would-reject
grep -E 'audited completion gate would reject' /tmp/rd-bot-backend.log | tail
```

Submit body skeleton (replace title/AC):

```json
{
  "title": "…",
  "projectId": "7499721648870920192",
  "priority": "P1",
  "repositoryUrl": "https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045.git",
  "repoOwner": "wanghehe123",
  "repoName": "rd-bot-waimai-acceptance-20260624-141045",
  "baseBranch": "main",
  "expectedResult": "…",
  "acceptanceCriteria": [
    "client 生产构建通过",
    "…可见标记…",
    "server 健康检查仍可用"
  ],
  "autoExecute": false,
  "tokenBudgetOverride": 0
}
```

Then `POST /admin/rd-tasks/{taskId}/submit`.

---

## 6. Out of scope / do not do

- Re-implement `AuditedTaskState`, p20 SQL, HOST_VERIFY command, admin audited-state UI (already on `main`)
- Treat QA container as the Auditor
- Add `AUDITOR_AGENT` or `MANAGER` as a Pi role in phase 1 closeout
- Archive `mea-audit-only-writeback` because unit tests are green
- Flip ENFORCE before W1-style head shows AC/QA/fingerprint COMPLETED
- Change observability SUCCESS dictionary (`COMMITTED`/`MERGED`) — separate later delta
- Increase `RD_EXECUTOR_PI_MAX_RAW_EVENT_BYTES`
- Bulk-delete Pi workspaces without measuring open files
- Force-push `main`

---

## 7. Self-review

- Spec coverage: transformation-plan stages 0–7 each have a task (9–15) plus phase-1 closeout (1–8).
- Placeholder scan: no TBD; waimai tables name the exact APIs and the historical task id.
- Type consistency: `QaSubject`, `QaCurrentAcceptance`, `WorkspaceFingerprintReceipt`, `RoleClaimSubject`, `AuditMutation`, `UNIQUE(command_id)`, `finish()` +1 version.
- Live contradiction captured: `7501460334566313984` COMPLETED with PENDING ACs under SHADOW because `auditQa` had no production caller.
