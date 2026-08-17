## Context

见 `proposal.md` 的动机。当前管理端把执行器选择和 Claude Dockerfile 拆成 `ProjectListPage` 上的两个 Dialog。调度真源仍是 `rd_agent_execution_profiles` + `rd_project_agent_profile_bindings`：`AgentExecutionProfileService.resolve` 为任务覆盖 → 项目角色默认 → 空（由 `EngineRequirementExecutionProfileResolver` 填兼容默认），再冻结 `rd_agent_execution_profile_snapshots`。`AgentRuntimeRouter` 只信任 snapshot 的 `runtimeType`。Pi 镜像来自全局 `rd.executor.pi.image` / `qa-image`；Claude 角色镜像来自 `rd_project_runtime_profiles`，且仅在 `rd.executor.agent-runtime.enabled=false` 时注入 `policyJson.runtimeImage`。

历史资料按 `docs/openspec/historical-spec-provenance-audit.md` 记为 `PLAN_OR_DECISION`：`docs/superpowers/specs/2026-07-26-pi-agent-runtime-integration-design.md`、`docs/superpowers/plans/2026-07-23-runtime-profile-and-handoff.md`。本设计以当前代码为准，不以它们为合同。

## Goals / Non-Goals

**Goals:**

- 策略聚合作为管理端真源；保存时同一调用栈投影到现有 per-role 表，resolver 不改查找顺序。
- HTTP 只做校验/翻译；编排放 `rag`。
- 一个写令牌覆盖策略与 Claude 上传。
- 独立 SPA 页与列表入口替换。

**Non-Goals:**

- 不改 snapshot JSON 必填字段、Router、Pi 选镜像、任务覆盖 UI。
- 不启用 `RD_EXECUTOR_AGENT_RUNTIME_ENABLED`。
- 不删除旧 API/表。

## Decisions

### 1. 聚合表 + 投影，而不是改 resolver

新增 `rd_agent_strategy_profiles`、`rd_agent_strategy_role_slots`、`rd_project_agent_strategy_bindings`。策略 ID 在项目内唯一：表主键为 `(project_id, strategy_id)`，槽位主键为 `(project_id, strategy_id, role)`。`strategy_id` 上限 64 字符。保存时为每个槽位 `register` 一条 `AgentExecutionProfile`，`profileId` 稳定为 `{projectId}:{strategyId}:{role}`（适配 `rd_agent_execution_profiles.profile_id` 全局唯一且 VARCHAR(128)），`name` 为策略名；设默认时调用现有 `bindProjectDefault` 四次。不同项目可以各自使用相同的 `strategyId`（例如 `default`）而互不覆盖。

备选：只改前端一次发四个旧 API。否决：无法表达「一份策略」、绑定易不一致、镜像仍分裂。

备选：resolver 直接读策略槽位。否决：本轮要复用调度，且任务覆盖外键仍指向 per-role profile。

### 2. 无策略行时合成只读视图

`GET` 若策略表为空，则按四个角色读取项目默认 Profile 合成 `strategyId=legacy-current`。该 ID 不可作为写入目标；首次 `POST` 真实 `strategyId`（`[A-Za-z0-9._-]+`）后落表。缺失角色槽位用 `PI` + 空 Provider 展示，保存仍要求配齐。

### 3. 镜像：Claude 走现有 builder，Pi 只落库

`image_mode` 为 `LOCAL_DEFAULT` | `CUSTOM`。Claude `CUSTOM` 由 bootstrap 控制器在同一 `X-RD-Agent-Runtime-Token` 下调用 `ProjectRuntimeProfileUploadService`（不再要求 `X-RD-Runtime-Profile-Token`），再把已验证 `image` 写回槽位。Claude `LOCAL_DEFAULT` 调用 `ProjectRuntimeProfileService.delete`。Pi `CUSTOM` 只写槽位的 `dockerfile_name` / `sha256` / `dockerfile_text`，不调用 Docker builder，也不改 `DockerPiAgentExecutor`。

### 4. 页面与 API

- 路由：`/admin/projects/:projectId/agent-strategy`；`AdminFrontendController` 增加该映射。
- API：`/admin/projects/{projectId}/agent-strategies` 的 GET 列表（含 `defaultStrategyId`、`synthesizedFromLegacy`、`agentRuntimeEnabled`、默认镜像展示名）、GET/POST/PUT 详情、PUT default、PUT/DELETE `.../roles/{role}/image`。
- 列表入口：`navigate`；删除 `ProjectRuntimeProfileDialog` 与 `AgentExecutionProfileDialog`。

### 5. 分层与事务

`AgentStrategyProfileService`（rag）依赖 `AgentStrategyProfileStore` 与 `AgentExecutionProfileService`。Postgres 适配在 bootstrap；保存顺序为先写聚合再投影绑定。内存 store 用于单测。生产不以 JVM 为真值。

## Risks / Trade-offs

- [Risk] 投影与聚合短暂不一致 → Mitigation：服务内先校验四槽位再写入；失败抛错不绑默认。
- [Risk] 操作者以为保存 Pi 即切换执行器 → Mitigation：GET/页面横幅暴露 `agentRuntimeEnabled`。
- [Risk] Pi 自定义镜像造成「已上传即生效」预期 → Mitigation：槽位与文案标明未接线。
- [Risk] `{projectId}:{strategyId}:{role}` 与旧自由 profileId 冲突 → Mitigation：strategyId 字符集与 64 长度限制；冲突时 400。
- [Risk] 投影 `name` 与旧 per-role profile 的 `(project_id, role, name)` 唯一约束冲突 → Mitigation：策略表已有 `(project_id, name)` 唯一；与手工旧 Profile 同名时保存失败并返回 400。
- [Risk] 启用 agent-runtime 后 Claude 自定义镜像仍被 `appendProjectRuntimePolicy` 跳过 → Mitigation：本轮不修；文案不承诺开关打开后 Claude 自定义镜像生效。

## Migration Plan

1. 部署 `p14_agent_strategy_profiles.sql`（`CREATE TABLE IF NOT EXISTS`，可重复执行）。
2. 旧绑定继续工作；打开新页看到合成当前默认。
3. 回滚：保留旧表与旧 API；新页可关，调度不受策略表缺失影响（无行则合成/走旧绑定）。

## Open Questions

无。Pi 自定义镜像接线与 agent-runtime 开关属后续独立 change。
