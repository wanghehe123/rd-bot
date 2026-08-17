## Why

项目管理把同一件事拆成两个弹窗：「Agent 执行策略」一次只能配一个角色，「运行镜像」只给 Claude Code 传 Dockerfile 且与 Pi 无关。操作者注册 Pi Profile 后仍无法在运行镜像里切换执行器，配置路径不合理。需要一个项目级独立页：一个策略一次配齐四个交付角色的执行器、供应商和镜像。

## What Changes

- 项目列表去掉「运行镜像」入口；「Agent 执行策略」改为跳转独立页 `/admin/projects/:projectId/agent-strategy`，不再用小窗口。
- 新增项目级策略聚合：一个 Profile 必须包含四个角色槽位（需求评审、方案设计、编码、QA），每个槽位选择执行器（默认 Pi）、Provider、可选模型覆盖，以及镜像（本地默认或上传 Dockerfile）。
- 保存策略时投影为现有按角色 `rd_agent_execution_profiles` 与四条 role default binding，调度仍走 `resolve → snapshot → AgentRuntimeRouter`。
- 写操作统一使用 `X-RD-Agent-Runtime-Token`；策略页不再要求第二套运行镜像令牌。
- Claude 自定义镜像复用现有 Dockerfile 构建合同；Pi 自定义本轮只落库，不改 Pi 容器选镜像。
- 页面标明：`rd.executor.agent-runtime.enabled` 未打开时保存 Pi 不会改变实际执行器。
- 旧 REST `/agent-execution-profiles` 与 `/runtime-profiles` 保留给任务覆盖和测试，管理端项目列表不再调用。

非目标：不改任务状态机、公平调度、重试、Provider fallback、snapshot 必填字段、`AgentRuntimeRouter` / `DockerPiAgentExecutor` 选镜像；不自动打开 agent-runtime 开关；不修 `RoleAwareRepairExecutor` 按角色分流 Claude/仅模型；不删除旧表；不把任务覆盖收进本页。

## Capabilities

### New Capabilities

- `project/agent-strategy-console`: 项目级 Agent 执行策略控制台——四角色一次配置、策略聚合存储与投影、本地默认镜像、Claude Dockerfile 上传、单令牌写保护、独立管理页。

### Modified Capabilities

无。当前主 spec 只有与本 change 无关的 `knowledge/openviking-projection-admin`；本 change 不修改其行为合同。

## Impact

- 后端：`rag` 新增策略聚合模型/服务/端口；`bootstrap` 新增 Admin API、PostgreSQL `p14` 表、SPA 路由；保存时调用现有 `AgentExecutionProfileService` 投影。Claude 上传仍走 `ProjectRuntimeProfileUploadService`。
- 前端：新页 `AgentStrategyPage`；`ProjectListPage` 去掉两个弹窗；`App.tsx` / `AdminLayout` / `AdminFrontendController` 增加嵌套路由。
- 数据：新增 `rd_agent_strategy_profiles`、`rd_agent_strategy_role_slots`、`rd_project_agent_strategy_bindings`；继续写入 `rd_agent_execution_profiles` 与 `rd_project_agent_profile_bindings`。
- 历史资料（`PLAN_OR_DECISION`，不以它们为当前合同）：`docs/superpowers/specs/2026-07-26-pi-agent-runtime-integration-design.md`、`docs/superpowers/plans/2026-07-23-runtime-profile-and-handoff.md`；审计索引见 `docs/openspec/historical-spec-provenance-audit.md`。
- 当前代码锚点：`AgentExecutionProfileAdminController`、`EngineRequirementExecutionProfileResolver`、`ProjectRuntimeProfileController`、`ProjectListPage.tsx`。
