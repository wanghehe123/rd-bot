## Why

RD-Bot 已把阶段结果沉淀为 `rd_experience_entries` 并在后续任务中复用，但现有条目仍由单个任务拥有，缺少稳定逻辑身份、版本/冲突生命周期、端到端幂等和可靠补偿，无法安全地充当跨任务、长期存在的项目级 Agent Memory。现在需要把不可变执行证据与可演进项目记忆分开，在保持当前需求交付链路兼容的前提下，为每个 RD 项目建立可追溯、可治理、可回滚的持久记忆能力。

## What Changes

- 新增项目级 Agent Memory 领域，以 `project_id` 作为强制隔离边界，保存项目事实、交付事件和经验证的程序经验；本期不建设 User Memory，也不允许默认跨项目复用。
- 将 task/stage/event/artifact 继续作为不可变证据账本；新增项目记忆逻辑头、不可变 revision、独立来源快照和持久化 consolidation operation，支持 `ADD / UPDATE / SUPERSEDE / NOOP`、CAS、lease/fencing、崩溃恢复与冲突隔离。
- 新增独立的项目记忆检索通道。所有候选必须先在 PostgreSQL 中按项目、角色、生命周期、有效期、脱敏和质量条件过滤，再参与有界排序；新记忆与 legacy workflow experience 双读期间共用上下文配额和跨通道去重。
- 把项目记忆注入标记为不可信参考数据。它不得授予权限、改变工具策略、覆盖当前用户要求、数据库/代码/运行时事实、`RULE.md` 或现行 spec，也不得单独满足验收标准。
- 为 legacy `rd_experience_entries` 提供清点、隔离、映射、shadow、按项目切换和即时回滚流程；不把全部历史经验直接提升为 ACTIVE 记忆。
- 提供项目级查看、来源追踪、确认、纠正、失效、软删除和显式 purge 的治理面。任务删除不得级联删除已批准的项目记忆来源快照。
- 本 change 仅冻结 OpenViking 作为后续可选、可重建细节投影的边界，不实现或启用远端 memory projection；通过本地 shadow 门槛后必须另建 OpenSpec change。PostgreSQL 始终保存 canonical/desired 状态，远端未知结果不得盲重放。

## Capabilities

### New Capabilities

- `requirement/project-agent-memory`: 定义项目级 Agent Memory 的隔离、证据来源、版本与冲突生命周期、可靠巩固、检索注入、迁移、治理和可选外部投影合同。

### Modified Capabilities

无。现有 `requirement/delivery-platform` 的需求任务与 Pi-only 执行合同保持不变；本 change 通过新的独立 capability 增加项目记忆行为。

## Impact

- 领域与编排：`engine` 中需求交付最终化、项目记忆 consolidation/reconciliation、角色上下文构建和检索编排。
- 基础领域：`rag` 中项目记忆模型、Store/Port、检索 channel、上下文 evidence 与项目生命周期集成。
- PostgreSQL：`bootstrap/src/main/resources/sql/postgres/` 下新增独立迁移，以及对应 Row、Mapper、Store、事务适配和调度配置；不回写历史迁移文件。
- 管理面：后续阶段新增项目记忆查询/治理 API 与管理台页面；若新增前端路由，必须同时增加 Vite proxy contract test。
- 兼容性：保留现有 `WorkflowExperienceStore` 和 `rd_experience_entries` 作为迁移期 legacy 通道；新记忆不伪装成 `WorkflowExperienceEntry`。
- 外部系统：本 change 不引入新向量数据库，也不实现 OpenViking memory outbox/adapter/worker；只保留默认关闭和后续独立 change 必须遵循的 owned-root、desired/observed、发送边界和 `UNKNOWN_REMOTE_RESULT` 规则。

### 证据边界

- 当前代码锚点：`RequirementDeliveryEngine`、`RequirementAgentStageOrchestrator`、`WorkflowExperienceStore`、`PostgresWorkflowExperienceStore`、`ProjectScopedRequirementKnowledgeSearchAdapter`、`RoleContextBuilder`、`PostgresRequirementStageFinalizationAdapter`、`PostgresAgentStageArtifactStore`，以及 `p1_multi_agent_orchestration.sql`。
- 当前测试锚点：`RequirementDeliveryEngineTest`、`RequirementAgentStageOrchestratorTest`、`PostgresWorkflowExperienceStoreTest`、`ProjectScopedRequirementKnowledgeSearchAdapterTest`、`RoleContextBuilderTest`、`PostgresAgentStageArtifactStoreTest`。
- 历史/理论资料仅作 change context，不作为当前实现证明：`docs/superpowers/specs/2026-07-10-project-delivery-console-retrieval-and-traces-design.md`、`docs/superpowers/specs/2026-07-11-rd-bot-requirement-lifecycle-atlas-design.md`、`docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown`、`docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`；以及 Codex task `codex://threads/01a0494c-0c3f-7830-ba06-7bd09b143233` 中引用的 `/Volumes/WishDisk/xiaoshuo/企业级 MultiAgent 的记忆系统：短期上下文与四层记忆架构实现｜得物技术.html` 与 `/Volumes/WishDisk/codes/ai-agent-book/book/chapter3.md`。
- 四层来源观点与本项目综合明确分开：原文的 Working/Session/User/Agent Memory 是生命周期模型；RD-Bot 把 Working 映射为不持久化的单次 Pi/role context，把 Session 映射为既有 task/stage/artifact 证据链，把 User Memory 因身份、同意、隐私和跨 Agent 授权问题排除在本期外，只把 Agent Memory 收窄为项目强隔离的 Project Agent Memory。这一收窄是 RD-Bot 设计决策，不是原文结论。
- 本轮规划实际运行了 `openspec list --json`、`openspec status --change project-scoped-agent-memory --json`，并使用 `rg`/`sed` 静态追踪上述代码、SQL、现行 spec、`RULE.md` 与 `docs/openspec/historical-spec-provenance-audit.md`；尚未运行 Maven、Node、PostgreSQL、HTTP 或浏览器验证，因此所有新行为均为待实施、待验证要求。

### 实现验证（2026-08-30）

- 证据文件：`openspec/changes/project-scoped-agent-memory/verification-evidence.md`
- 基线 SHA：`65babda1a3e97aa7b4f35f6d3eec1be3e594441d`（工作副本，未提交）
- 主要锚点：`p19_project_agent_memory.sql`、`ProjectMemoryConsolidationEngine`、`ProjectScopedRequirementKnowledgeSearchAdapter`、`ProjectMemoryAdminController`、`ProjectMemoryPage.tsx`
- OpenSpec：`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict` → 11/11 passed
