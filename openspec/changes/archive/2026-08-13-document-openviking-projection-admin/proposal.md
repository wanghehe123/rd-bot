## Why

RD-Bot 已经落地 OpenViking 知识投影的账本、管理 API 和前端数据模型，但当前仓库还没有对应的 OpenSpec 当前态能力说明。现在补上这一块真实、可验证的行为契约，可以让后续删除收敛、死信恢复、对账和管理页迭代都围绕同一份 spec 演进，避免把 HTTP 成功或异步任务完成误判为投影已同步。

## What Changes

- 新增 OpenViking 投影管理能力的行为 spec，覆盖本地真值与外部投影边界、文档状态查询、管理操作、错误脱敏和默认关闭约束。
- 将最近已实现并有测试证据的 `/admin/knowledge-base/{kbId}/openviking/**` 管理 API 与前端 service/presentation 行为纳入当前 spec。
- 不修改 Java、TypeScript、SQL 或运行时行为；这是对现有能力的 OpenSpec brownfield 建档。
- 通过 OpenSpec delta → validate → archive 流程把该能力写入 `openspec/specs/`，后续行为变更必须从新的 change 开始。

## Capabilities

### New Capabilities

- `knowledge/openviking-projection-admin`: OpenViking 知识投影的账本可观测性、管理操作和前端展示契约。

### Modified Capabilities

- 无。当前仓库还没有同路径的 OpenSpec 主 spec。

## Impact

- 证据来源：`RULE.md` §3.5.6、`docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md`、最近的 OpenViking controller/engine/frontend service 代码及其测试。
- 主要代码范围：`rag/.../knowledge/projection/**`、`bootstrap/.../KnowledgeProjectionAdminController.java`、`frontend/src/services/openVikingKnowledgeService.ts` 和 `frontend/src/services/openVikingKnowledgePresentation.ts`。
- 主要验证：OpenViking 协议 fixture/本地基线测试、projection admin controller 测试、前端 Node contract tests、typecheck 和 production build。
