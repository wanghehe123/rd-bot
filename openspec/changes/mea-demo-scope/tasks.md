# D00–D05 Demo 收口任务

## 1. D00 组合候选

- [x] 1.1 核对后端基点 `9da44a5c` 含 Coding MEA / Manager 全文 / stage result 读取 API、B09/B10 代码与 env 预算冻结；记录来源 dirty 清单（10 tracked M + 2 untracked，整体保留在 `mea-fresh-executor-episode` 工作区，不带入）
- [x] 1.2 从 `9da44a5c` 创建 `codex/mea-demo` 分支与 `.worktrees/mea-demo` 工作区；不 reset/stash/清理来源分支
- [x] 1.3 逐文件迁入前端工作台：7 个新 src 文件 + 8 个新测试文件 + 9 个 M 文件 patch（`api.ts`、`tsconfig.json`、`RdTaskDetailPage.tsx`、`TaskRoleWorkbench.tsx`、`RoleEffectiveContextCard.tsx`、`HostVerificationCard.tsx`、3 个 M 测试）；不迁 `static/admin` bundle（由本候选重新 build）、`node_modules`、旧验收报告
- [x] 1.4 建立 `openspec/changes/mea-demo-scope/` 范围记录；P2 kill 矩阵、B12–B24、12×3、移动专项记为移出项，不标为已完成

## 2. D01 前端接口修复

- [x] 2.1 `codingMeaService.ts`：query 统一为 `codingStageRunId`；Manager 全文独立 wire type 读 `boundedContract`；审计记录适配 `id/text/evidenceRefs`（EvidenceRef 原样保留 uri/sha256 结构）
- [x] 2.2 `RdTaskDetailPage.tsx`：`loadCodingMea` 传 `{ codingStageRunId: stageRunId }`，保留 generation guard
- [x] 2.3 `codingMeaModel.ts`：移除 role+attempt / round / 第一条 decision 等无证据回退，使用 `sourceCommandId` / `links` / `remediations` 实际字段；无法准确关联显示「当前记录暂无法关联」
- [x] 2.4 `RoleDeliverablesPanel.tsx`/`roleDeliverableModel.ts`：QA 证据 URL 带 taskId（`/admin/rd-tasks/${taskId}/qa-evidence/${artifactId}/content`）
- [x] 2.5 对应测试更新并通过（codingMeaService / codingMeaModel / roleDeliverableModel / viteProxy 等）

## 3. D02 后端失败底线

- [x] 3.1 `DockerPiAgentExecutor.validateRoleProtocolResult` 透传 bridge `failureCategory`（BUDGET_EXCEEDED 不再被改写为 PI_BRIDGE_PROTOCOL）；executor 级断言
- [x] 3.2 Host 窄断言：预算失败不晋升 COMPLETED、不派发无限 Coding 修复，输出明确失败/需人工原因
- [x] 3.3 迁入 `RequirementReviewProtocol` false-ASK 修复（仅 disposition 方法 + isApproved helper + 单测）；不带入 command reopen / deadline / 重调度实验

## 4. D03 组合回归

- [x] 4.1 前端：聚焦测试 + `npm run typecheck` + `npm run build`（一次生成静态 bundle）
- [x] 4.2 后端：聚焦测试（读取 Controller、engine 失败处理、executor 归类）；组合候选 jar 构建一次并记录 hash
- [x] 4.3 `deploy/cloud-server/mea-live/verify_demo.py`：普通 HTTP 读取断言脚本已建（列表/shell/overview/role-prompts/coding-mea/stage result + 两个负例）；云端候选上的实际执行归 D04

## 5. D04/D05 真机演示与交接

- [ ] 5.1 启动候选服务，一个桌面视口核对：切角色/Attempt、开 Prompt、开一条证据、刷新不串
- [ ] 5.2 一条普通真实需求走到完成，打开真实 PR；用一个已有失败/空状态样本确认错误显示
- [ ] 5.3 `docs/superpowers/qa/2026-09-08-mea-demo-acceptance.md`：候选 SHA、构建 hash、taskId/PR、命令结果、演示顺序、移出项明示
