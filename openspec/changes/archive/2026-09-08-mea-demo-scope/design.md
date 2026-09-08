# MEA Demo 范围 change 设计说明

## 背景决策

- **为什么只做范围合同而不做行为 spec delta**：本 change 收窄的是「验收范围」而非系统行为。既有行为合同（`requirement/fresh-executor-episode` 的 Prompt 合同、`requirement/manager-decision-command` 的 Manager 决策、`requirement/audit-only-writeback` 的完成门）全部保持不变；拆除已运行的核验门反而会引入协议改动与误报风险（方案 §2.2）。
- **组合候选策略**：单一集成分支 `codex/mea-demo` 替代「后端实验 jar + 前端 dirty worktree」的组合验证状态。后端基点 `9da44a5c` 是 `main(3fcd7db6)` 的直接后代（+9 commits），前端 M 文件 diff 相对同一基点，patch 可干净应用（已验证 `git diff 3fcd7db6..9da44a5c -- frontend/ static/` 为空）。
- **P2 恢复实验不带入**：来源 worktree 的 8 个 tracked dirty 文件（command reopen、deadline 刷新、立即重调度、Pi lifecycle 自动开新 Attempt）彼此强耦合（`RequirementAgentStageOrchestrator.isPiLifecycleIncompleteFailure` 被 Engine 新逻辑跨类调用），整体保留在原工作区；只有 `RequirementReviewProtocol` 的 false-ASK 修复是方法级、零耦合的，可单独迁入（D02 3.3）。

## 迁入清单（D00 实际执行记录）

新增文件（15）：`frontend/src/components/admin/rdtask/{CodingMeaPanel,RoleAgentStateCard,RoleDeliverablesPanel}.tsx`、`frontend/src/pages/admin/rdtask/{codingMeaModel,roleDeliverableModel}.ts`、`frontend/src/services/{codingMeaService,stageResultService}.ts`、`frontend/test/{codingMeaLoading,codingMeaModel,codingMeaPresentation,codingMeaService,meaWorkbenchStates,roleDeliverableModel,stageResultService,taskEvidenceNavigation}.test.ts`。

patch 文件（9）：`frontend/src/services/api.ts`（VITE_API_BASE_URL 兼容 node 测试）、`frontend/tsconfig.json`（allowImportingTsExtensions）、`frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`（loadCodingMea + guard）、`frontend/src/components/admin/rdtask/{TaskRoleWorkbench,RoleEffectiveContextCard,HostVerificationCard}.tsx`、`frontend/test/{rdTaskRolePromptPresentation,roleWorkbenchPresentation,viteProxy}.test.ts`。

明确不迁：`bootstrap/src/main/resources/static/admin/*`（vite build 产物，组合候选统一重新 build）、`frontend/node_modules`、`docs/superpowers/qa/2026-09-06-mea-task-workbench-acceptance.md`（历史验收报告，留在来源工作区）、`openspec/changes/mea-task-workbench/`（来源工作区已闭环）。

## 验证命令

```bash
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-demo status --porcelain
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-demo log --oneline -1  # 9da44a5c 基点
```
