## Why

RD-Bot 已经把可执行主链收敛成「需求交付 + Pi Agent」，但 `openspec/specs/` 仍只有 OpenViking 投影管理，主产品面没有当前态契约。后续若按历史计划/Claude/工单/评测文档演进，会把已删除路径重新写回。现在需要把已经落地、有测试锚点的边界写成 OpenSpec，而不是再改运行时。

## What Changes

- 新增需求交付平台当前态 spec：写入面只接受 `REQUIREMENT`，执行面只跑 Pi，历史 `BUG_FIX` / `CLAUDE_CODE` / `MODEL_ONLY` 只读或失败关闭。
- 把已删除能力标成不得恢复：仅模型 HTTP 执行器、Claude Code 容器执行器、BugFix/工单队列、`repair_records` 写入、RAG 聊天栈、Coding Benchmark / 评测控制面。
- **不改运行时行为。** 本 change 是对 `chore/redundancy-audit` 已提交删除的 brownfield 建档。
- 不把仍存在的历史表、枚举值和 SPA 回退路由当成活功能；它们属于遗留存储或书签兼容，单独列为非目标。

## Capabilities

### New Capabilities

- `requirement/delivery-platform`: 需求交付作为唯一写入与执行主链的边界，包括任务摄入、Pi 运行时、以及已下线路径的失败关闭。

### Modified Capabilities

- 无。当前 `openspec/specs/` 只有 `knowledge/openviking-projection-admin`，本 change 不改它的需求。未归档 change 里的 `project/agent-strategy-console` 仍描述三角色执行器枚举，那是另一条未归档草案，不在本 delta 里改写。

## Impact

- **证据分类：** 本 worktree 没有 `docs/openspec/historical-spec-provenance-audit.md`（openspec config 仍要求查阅）。本轮按 AGENTS.md 自行分类：`RULE.md` §1.2 与本分支测试为 current verified；`docs/superpowers/plans/2026-07-26-pi-agent-runtime-integration.md` 是计划（BugFix 另删，现已落地）；评测/工单队列 superpowers 文档是 superseded，不得写入主 spec。
- **当前代码锚点：** `RdTaskController`、`FeishuImMessageController`、`RdTaskType.parse`、`EngineRequirementExecutionProfileResolver.compatibilityRuntime`、`AgentRuntimeRouter`、`DockerPiAgentExecutor`、管理台任务创建页。
- **当前测试锚点：** `RdTaskTypeTest`、`FeishuImMessageControllerTest`、`EngineRequirementExecutionProfileResolverTest`、`RdTaskControllerTest`、`frontend/test/rdTaskDraft.test.ts`。
- **验证命令（本轮将运行）：** `openspec validate --change converge-requirement-delivery-platform --strict`；上述聚焦测试；`openspec validate --all --strict`（归档后）。
- **明确不改：** PostgreSQL 历史 DDL（`repair_records`、评测表）、`AgentRuntimeType` 枚举值、`ProjectRuntimeProfile.agentType=CLAUDE_CODE` 的遗留镜像上传契约、`/admin/evaluations` SPA HTML 回退。
