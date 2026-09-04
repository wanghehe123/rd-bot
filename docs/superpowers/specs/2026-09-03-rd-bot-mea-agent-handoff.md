# RD-Bot MEA 改造：交给实现 Agent 的交接包

日期：2026-09-03
状态：规划已冻结，**尚未写业务代码**。本文件是给下一个 Agent 的入口；不要从聊天记录猜范围。

---

## 0. 直接可粘贴给实现 Agent 的任务说明

```text
你在仓库 /Users/wish233/Documents/RD-Bot 实现 OpenSpec change：mea-audit-only-writeback。

这是 DreamX MEA 改造的阶段 1（Audit-only writeback），不是完整 MEA。
不要做阶段 2–7（fresh episode、动态 Manager、WAITING_USER_INPUT、契约反查、任务级预算、项目记忆接线、观测 SUCCESS 词典）。

开工前必须按顺序阅读：
1. 本交接包 docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md
2. RULE.md（尤其 3.5.3 任务状态/派发、3.5.x Pi 段、6.x 测试）
3. AGENTS.md
4. openspec/config.yaml
5. openspec/specs/requirement/delivery-platform/spec.md
6. openspec/changes/mea-audit-only-writeback/ 全部 artifacts（proposal、design、delta spec、tasks）
7. docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md（背景与后续阶段；本轮只做阶段 1）

实现协议：
- 使用 .claude/skills/openspec-apply-change/SKILL.md
- 只做 tasks.md 未勾选项；每项先写失败测试再实现；做完把 `- [ ]` 改成 `- [x]`
- 从 tasks 0.1 开始。不要跳过 0.1（host-verification-pipeline 对齐）
- 行为变化先改 change 下 delta spec，再改代码
- 不要直接编辑 openspec/specs/（归档才同步）
- 不要提交 git，除非用户明确要求
- 不要删除、改名或清空 RULE.md；需要新强制条款时按 design 草案追加并附验证命令

冻结决策（2026-09-03 用户确认）：
1. gate-mode 默认 ENFORCE；云端首次 SHADOW 覆盖
2. HOST_VERIFY 是独立 durable command
3. QA 只读 = tracked-tree 指纹 fail-closed；repo/ 保持 rw；不自动重审计
4. WAITING_USER_INPUT 留阶段 3，本 change 不改 RdTaskStatus
5. 观测 SUCCESS 仅 COMPLETED，本 change 不改观测代码
6. 真机项目 codex-run-test-waimai

仍开放（按 design 默认做，不要再问用户）：
- Host 断言 → 独立 GATE-HOST-ASSERTION（bundle 存在才初始化）
- legacy QA 按规范化 criteria 文本匹配，匹配失败保持 PENDING

关键插入点（代码符号，不要凭行号）：
- 状态初始化：RequirementPolicyTransactionPort.consumePolicyApply(ALLOWED) 与 APPROVAL_RESUME，不是 planPolicyStage（dispatcher 已隔离 legacy POLICY）
- 写回：RequirementStageExecutionPlan schema v3 auditedStateMutation，finalize 事务内 CAS
- Coding 成功 continuation：HOST_VERIFY，禁止直接 ROLE_EXECUTION:QA_AGENT
- 完成：AuditedCompletionGate + rd_task_completion_bindings；Executor JSON 不能晋升 COMPLETED

验证命令见 change design.md「验证命令」。改 Pi 合同后重建 Dockerfile 与 Dockerfile.qa。
```

---

## 1. 本轮要做什么 / 不要做什么

**要做（阶段 1）：** 让未经 Host 审计的 Executor 声明不能把需求任务写成 `COMPLETED`。

具体能力见 delta spec，摘要：

- `AuditedTaskState` + `AuditRun`（PostgreSQL `p20_task_audited_state.sql`）
- `DeterministicAuditor`（Host 纯函数，不是新 Agent 角色）
- plan schema v3 写回，搭乘 `OUTCOME_RECORDED → FINALIZED`
- durable command `HOST_VERIFY` + `HOST_VERIFY_FIX(2)`
- QA tracked-tree 指纹写入 `dockerMetadataJson`
- PI-v2 QA `criteriaId` 与 `AC-%03d` 闭环（提示词 / `result-tool.mjs` / `QaEvidenceBundleValidator` 三处锁步）
- `DETERMINISTIC_REVIEW` / `PUBLICATION` / `COMPLETION` 读状态 head
- `rd_task_completion_bindings`；PR 描述审计清单；两个只读管理 API + 前端审计面板
- `gate-mode=SHADOW|ENFORCE`，默认 `ENFORCE`

**不要做：**

| 内容 | 归属 |
|---|---|
| 动态 Manager、`MANAGER_DECIDE`、`WAITING_USER_INPUT` | 阶段 3 |
| 用已审计状态替换失败 prompt、QA provider-attempt 隔离 | 阶段 2（tasks 7.4 可记为阶段 2，本 change 可不做隔离） |
| 稳定契约对象、AI 契约反查 | 阶段 4 |
| 任务级总 token/成本/无进展检测 | 阶段 5 |
| 项目记忆生产接线与审计晋升 | 阶段 6 |
| 改 `DeliveryObservabilityQueryService.SUCCESS` | 观测 change 归档后的新 delta |
| 删除 `RequirementDeliveryEngine.submit()` | 只保证它走同一完成门 |
| 把 QA 改名为 Auditor，或新增 `AUDITOR_AGENT` | 明确禁止 |
| 把 `/work/repo` 改成 `:ro`，或去掉 QA 的 `bash` | 明确禁止 |
| 直接改 `openspec/specs/` | 实现完成并测试通过后才 archive |

---

## 2. 阅读顺序（强制）

按这个顺序读，不要先翻 `docs/rd-task-management-*.md` 当现状（那是 BugFix 历史文档，不是需求交付真值）。

| 序 | 路径 | 为什么读 |
|---|---|---|
| 1 | `docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md` | 本交接包 |
| 2 | `RULE.md` | 仓库强制约束 |
| 3 | `AGENTS.md` | Pi QA、OpenSpec、镜像重建 |
| 4 | `openspec/config.yaml` | change 写法与 apply/archive 规则 |
| 5 | `openspec/specs/requirement/delivery-platform/spec.md` | 当前主 spec（写入面、Pi 唯一路径） |
| 6 | `openspec/changes/mea-audit-only-writeback/proposal.md` | Why / What / Impact |
| 7 | `openspec/changes/mea-audit-only-writeback/design.md` | D1–D12、插入点、验证命令 |
| 8 | `openspec/changes/mea-audit-only-writeback/specs/requirement/audit-only-writeback/spec.md` | 可验证行为合同 |
| 9 | `openspec/changes/mea-audit-only-writeback/tasks.md` | 唯一实现清单 |
| 10 | `docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` | 九步现状、差距矩阵、阶段 0–7；本轮只执行阶段 1 |
| 11 | `docs/openspec/historical-spec-provenance-audit.md` | 历史资料分级；计划文档不能当主 spec |

方法论原文（本机可能不可用，结论已写入总体方案，**不要把论文分数当验收**）：

- `/Volumes/WishDisk/llms/paper/longhorizon-repro/RD-Bot-DreamX-MEA改造指南.md`

实现技能：

- `.claude/skills/openspec-apply-change/SKILL.md`

相关但不要当本轮范围：

- `openspec/changes/host-verification-pipeline/`（代码已落地、tasks 未勾选；0.1 必须对齐）
- `openspec/changes/pi-agent-state-and-qa-remediation-v2/`（复用 `AC-%03d` 与 remediation 账本）
- `openspec/changes/upgrade-delivery-observability/`（SUCCESS 词典本轮不改）
- `openspec/changes/improve-requirement-pr-description/`（PR 模板要对齐审计清单首段）
- `openspec/changes/project-scoped-agent-memory/`（阶段 6）
- `openspec/changes/cloud-server-live-verification/`（真机流程可复用）
- `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md`
- `docs/superpowers/specs/2026-08-13-requirement-publication-preflight-and-retry-provenance-spec.md`

---

## 3. 规划文件清单（本轮产物）

全部未入库业务代码。Git 现状应类似：

```text
?? openspec/changes/mea-audit-only-writeback/
?? docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md
?? docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md
 M .gitignore   # 已为上述 specs 加白名单
```

| 文件 | 角色 |
|---|---|
| `openspec/changes/mea-audit-only-writeback/.openspec.yaml` | change 元数据，`created: 2026-09-03` |
| `openspec/changes/mea-audit-only-writeback/proposal.md` | 动机与影响面 |
| `openspec/changes/mea-audit-only-writeback/design.md` | 设计决策 D1–D12 + 已确认决策 + 剩余 Open Questions + 验证命令 |
| `openspec/changes/mea-audit-only-writeback/specs/requirement/audit-only-writeback/spec.md` | 10 条 ADDED Requirements，WHEN/THEN 场景 |
| `openspec/changes/mea-audit-only-writeback/tasks.md` | 13 组任务，全部未勾选，每项绑定文件/测试 |
| `docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` | 总体路线（阶段 0–7、消融实验、差距矩阵） |
| `docs/superpowers/specs/2026-09-03-rd-bot-mea-agent-handoff.md` | 本文件 |

校验：`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict` 在规划完成时为 13 passed（含本 change）。

---

## 4. 已冻结决策（2026-09-03 用户确认）

1. **`gate-mode` 默认 `ENFORCE`。** `application.yaml` 默认 ENFORCE。云端首次在 `deploy/cloud-server/application-local.server.yaml` 显式 `SHADOW`，项目 `codex-run-test-waimai` 跑 ≥5 个真实任务（含 1 个故意破坏构建）后再去掉覆盖。
2. **`HOST_VERIFY` 是独立 durable command**，不嵌入 Coding command。接受多一次干净构建墙钟。
3. **QA 只读 = tracked-tree 指纹。** `/work/repo` 保持 rw，保留 `bash`。`VIOLATION`/`SUSPECT` → 不晋升、任务转人工。阶段 1 不自动重审计。
4. **`RdTaskStatus.WAITING_USER_INPUT` 留给阶段 3。** 本 change 不改共享枚举与状态图。
5. **观测 SUCCESS 仅 `COMPLETED`。** 本 change 不改 `DeliveryObservabilityQueryService`。`COMMITTED` 为在途；`MERGED` 仅当历史含 `COMPLETED` 才计成功——那是后续 delta。
6. **真机与基线项目：`codex-run-test-waimai`**，脚本 `deploy/cloud-server/run-codex-memory-live-task.py`。

仍开放、按 design 默认执行：

- `GATE-HOST-ASSERTION` 独立门（有 `hostAssertionBundle` 才初始化）
- legacy QA 文本规范化匹配，失败则该 `AC-*` 保持 `PENDING`

---

## 5. 现状锚点（实现前再 `rg` 一次，不要信行号）

| 事实 | 符号 |
|---|---|
| 生产派发是逐条 durable command，不是 `submit()` 一次跑完 | `RequirementDeliveryDispatchService.submit` |
| legacy `POLICY` stage 已被隔离；策略走独立端口 | `runPolicyApplyCommand` → `RequirementPolicyTransactionPort.consumePolicyApply` |
| 生产路径关闭 orchestrator 内 host-verify / QA 内循环 | `RequirementDeliveryEngine.boundedRolePlan`：`hostVerifyRemediationEnabled=false`，`qaRemediationEnabled=false` |
| Coding 成功后当前直接续 QA | `roleContinuation(CODING_AGENT)` → `ROLE_EXECUTION:QA_AGENT`（本 change 改为 `HOST_VERIFY`） |
| 确定性复核只检查 Agent JSON 形状 | `RequirementDeliveryReviewer.review` |
| `COMPLETED` mutation 不带证据 | `planCompletionStage` |
| QA `repo/` 为 rw；只读挂载角色不含 QA | `DockerPiAgentExecutor.READ_ONLY_REPO_ROLES` |
| 内容指纹守卫未接线 | `RepairWorkspaceRepositoryPort.repositoryState` 无生产调用方 |
| 验收 ID 已有 | `PiAgentContextStateManager` 的 `AC-%03d` |
| QA `criteriaId` 目前只在 FAILED 打回闭环 | `QaEvidenceBundleValidator`、`result-tool.mjs` |
| 下一份 SQL 编号 | `p20_task_audited_state.sql`（现有到 `p19_project_agent_memory.sql`） |
| 观测 SUCCESS 含 COMMITTED | `DeliveryObservabilityQueryService.SUCCESS`（本轮不改） |
| `paused` 不阻止派发 | `RagStreamTaskRegistry.pauseTask`（阶段 3 再修） |

模块依赖：`bootstrap → engine/exec/skill/rag`；`engine/exec/skill → rag`。审计模型放 `engine`，Postgres 适配放 `bootstrap`，指纹在 `exec`，HTTP 控制器在 `bootstrap`。`@Transactional` 类不得 `final`。

---

## 6. 实现协议

1. 读 `.claude/skills/openspec-apply-change/SKILL.md`。
2. `openspec status --change mea-audit-only-writeback --json`
3. `openspec instructions apply --change mea-audit-only-writeback --json`
4. **从 `tasks.md` 0.1 起，按编号做。** 每项：失败测试 → 实现 → 聚焦测试绿 → 勾选。
5. 行为与 delta spec 不一致时，先改 change 下 spec，再改代码。
6. 改 QA 合同后：`cd bootstrap/src/main/resources/executor/pi && npm test`，并重建 `Dockerfile` 与 `Dockerfile.qa`。
7. 改任务状态/事件/CAS/派发后跑 `PostgresRdTaskStateAtomicRealSmokeTest`。
8. 新增 `/admin/*` 前端请求必须更新 Vite proxy contract test。
9. 不要 `git commit`，除非用户说提交。
10. `AGENTS.md` 里的 `model-escalation` skill 在规划机上不存在。遇到 schema/并发/完成权分叉时，停下来问用户，不要静默改 design 的信任边界。

`tasks.md` 分组对应：

- 0 前置（host-verification-pipeline、读链、gate-mode 配置）
- 1 领域模型
- 2 DeterministicAuditor / CompletionGate
- 3 plan v3 + finalize 写回
- 4 `p20` SQL + Postgres store
- 5 初始化（**在 policy adapter，不是 planPolicyStage**）与 UNTRUSTED claims
- 6 `HOST_VERIFY` command + `HOST_VERIFY_FIX`
- 7 QA 指纹
- 8 `criteriaId` 三处锁步 + 重建镜像
- 9 复核/发布/完成门 + 虚报成功测试
- 10 PR 清单、管理 API、前端
- 11 源码守卫 + RULE.md / AGENTS.md
- 12 真机 `codex-run-test-waimai` + archive

---

## 7. 验证命令（从 design.md 复制，实现后要真跑）

```bash
./mvnw -pl engine -am -Dtest='AuditedTaskState*Test,DeterministicAuditorTest,AuditedCompletionGateTest,RequirementStageExecutionPlanCodecTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementAgentStageOrchestratorTest,TaskRetryPointResolverTest,AgentRemediationCoordinatorTest' -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl exec -am -Dtest='DockerPiAgentExecutorTest,QaEvidenceBundleValidatorTest,AgentRoleResultValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl bootstrap -am -Dtest='PostgresAuditedTaskStateStoreTest,AuditedStateFinalizationWriterTest,PostgresRequirementStageFinalizationAdapterTest,RequirementDeliveryDispatchServiceTest,RequirementStageCommandFactoryTest,RdTaskAuditedStateControllerTest,RequirementCompletionWriterPolicyTest,TransactionalProxyPolicyTest,PiAgentRemediationSqlPolicyTest' -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl bootstrap -am -Dtest=PostgresRdTaskStateAtomicRealSmokeTest,PostgresTaskAuditedStateRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -pl bootstrap -am -Drd.integration.stage-finalization.enabled=true -Dtest=PostgresRequirementStageFinalizationRealSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test

cd bootstrap/src/main/resources/executor/pi && npm test

cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build

OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

退出条件（阶段 1）：构造「Coding `testStatus=PASSED` + QA `status=PASSED` 但 `GATE-BUILD=PENDING`」的测试，系统必须 `REJECTED`，不得 `COMPLETED`。`unaudited_claim_promoted_to_completed = 0`。

---

## 8. RULE.md 本轮会碰到的强制点（读原文，不要只靠本摘要）

- 3.5.3：快照与状态事件同事务；`AgentStageRun` CAS；command 租约；checkpoint 提交边界；需求任务不得走 BugFix 图
- 3.5.x Pi：角色只走容器；docs-only 键只写 `dockerMetadataJson`；`boundedRolePlan` 关闭 orchestrator 内 QA 修复循环；remediation 在 `recordOutcome/finalize` 内持久化
- 3.4：生产真值在 PostgreSQL；管理台新域要有 Store 端口 + SQL + 防退化测试
- 1.1：禁止 `engine` 依赖 `bootstrap`
- 6.x：同包 `XxxTest`；真实 HTTP 请求链；改状态后跑 Postgres 原子冒烟
- `@Transactional` 类不得 `final`

阶段 1 归档时按总体方案第 8 节把完成绑定、写回边界、`HOST_VERIFY` continuation、QA 指纹、`criteriaId` 三处锁步追加进 `RULE.md`。

---

## 9. 规划对话的探索记录（二手，关键结论已写入总体方案）

完整 JSONL：`/Users/wish233/.cursor/projects/Users-wish233-Documents-RD-Bot/agent-transcripts/cd9ae44b-9d7d-4d54-9c48-a62cc582ca76/`

只读子代理（不要把报告当 spec）：

| 范围 | ID |
|---|---|
| 原始任务 / 完成判定 / 写入权 | `82b81d9e-5ce6-4c29-9169-e4ef45877929` |
| attempt 上下文 / QA 独立性 | `32f23569-8d77-4ac2-886d-f8bcc64d0f5b` |
| 调度 / 持久化恢复 | `e231dc3e-57b6-4f77-877d-5814905c5292` |
| 记忆 / RAG / 预算 / 前端 IA | `f89d6102-87c4-4786-8906-b98f41516893` |

真机记忆未写入的旁证（证明巩固路径未接线，阶段 6 才修）：`tmp-codex-memory-run/`。

---

## 10. 阶段 1 做完之后（本轮不要做）

总体方案第 5 节的后续 change 名：

- `mea-baseline-evaluation`（阶段 0，可与阶段 1 并行，项目 `codex-run-test-waimai`）
- `mea-fresh-executor-episode`
- `mea-manager-decision-command`（含 `WAITING_USER_INPUT`）
- `mea-stable-contract-and-backcheck`
- `mea-budget-and-recovery-governance`
- `mea-audited-memory-promotion`
- `mea-role-model-routing`
- 观测 SUCCESS 词典：`upgrade-delivery-observability` 归档后的新 delta

阶段 1 全部 tasks 勾选且测试通过后，用 openspec-archive-change 归档，不要手改 `openspec/specs/`。
