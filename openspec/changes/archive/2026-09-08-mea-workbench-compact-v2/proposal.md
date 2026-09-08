## Why

Demo 交付后真实页面复验发现：任务页顶部存在无内容的快速操作占位（`RdTaskDetailPage.tsx:1423` 无条件渲染）与重复的任务级状态摘要（`TaskSummaryBand` 与工作台进度头各一份）；四角色卡只有状态没有产物信息；QA 的长执行概述全文渲染撑大页面，产物/证据/Coding MEA 排在检查与审计明细之后；390px 窄屏的所选 Attempt 状态排在全部正文之后。研发人员无法在首屏直接看到当前任务状态、所选角色产物与一个真实证据入口。

## What Changes

- 顶部合并为一套任务级摘要：删除空快速操作容器；等待材料、恢复中、阻断提示迁入页头；任务 ID、时间、Token、审计计数放次级或详情。现有操作条件与 handler 不变。
- `TaskRoleWorkbench` 移除与页头重复的阶段推进/当前角色/耗时；角色卡补一句产物摘要（`workbenchSummaryModel.ts` 纯函数，只消费已加载字段）。
- `RoleDeliverablesPanel` 重排：当前问题 → 最多三项产物摘要（每项两行）→ 一行产物/证据快捷入口 → Coding MEA → 五项关键证据 → 折叠的检查/审计明细 → 折叠的执行概述全文/原始结果。
- `HostVerificationCard` 增加可选 `compact`（仅 roles 成功态使用），成功验证压成一行概要。
- 响应式：窄屏时所选 Attempt 短状态先于内容 tabs 与正文；单份 DOM，无重复状态组件。
- 保持 taskId/stageRunId 身份、完整 `/result/content` 两阶段读取、审计 head 来源标注等既有守卫不变。

## Capabilities

### New Capabilities

- `requirement/mea-workbench-compact-v2`：工作台首屏密度、角色卡摘要真实性与长内容默认折叠的展示合同。

### Modified Capabilities

- 无。不改后端、协议、状态机或既有读取链路语义。

## Impact

- `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`、`frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx`、`RoleDeliverablesPanel.tsx`、`CodingMeaPanel.tsx`、`RoleAgentStateCard.tsx`、`HostVerificationCard.tsx`。
- `frontend/src/pages/admin/rdtask/roleDeliverableModel.ts` 增加 QA 简短指标；新增 `workbenchSummaryModel.ts` 及测试。
- 真实页面同视口前后对照截图与新 QA 报告（实施时生成）。
- 本期零后端修改；发布需重新打包静态资源并另行申请授权。

## 来源分级与读物

| 来源 | 分类 | 使用方式 |
| --- | --- | --- |
| 本 worktree 源码（继承自 `codex/mea-demo` 未提交基线，HEAD `f3e7bcc9` + patch） | 当前行为证据 | 修改起点；P00 已保存 `/tmp/mea-workbench-v2-baseline.patch` 与 untracked 清单 |
| 主仓库 `docs/superpowers/plans/2026-09-08-mea-workbench-compact-v2-plan.md` | 计划/决策 | 本 change 的需求来源；其第 3–5 节是展示合同 |
| 主仓库 `docs/superpowers/plans/2026-09-06-mea-task-workbench-frontend-plan.md` | 原设计/决策 | 四角色/MEA/身份约束的上级设计 |
| 来源工作区 `docs/superpowers/qa/2026-09-06-mea-task-workbench-acceptance.md` | 历史实施声明 | FE-01/03/11 视觉 PASS 证据不足，需本轮重新验证 |
| 本 worktree `docs/superpowers/qa/2026-09-08-mea-demo-closeout-and-live-verification.md` | 当前验证记录 | 业务/接口/读取真实性证据；不替代 V2 视觉验收 |
| `RULE.md` §3.5.15、§5、§6.2 | 仓库约束 | 身份、加载与验证规则必须保留 |
| `openspec/specs/requirement/delivery-platform/spec.md` | 当前主 spec | 平台边界（四角色、Pi、历史任务只读） |
| `docs/rd-task-management-requirements.md`、`docs/rd-task-management-design.md` | 未在核查目录找到 | 不伪称已读；以 RULE、主 spec 与实际代码链路为准 |

## 非目标

- 不修改 `codingMeaModel.ts`、后端 Java、bridge、QA skill、数据库迁移或部署脚本。
- 不新建后端接口、不引入全局状态系统、不为四张角色卡并行下载完整结果。
- 不重跑 P2 故障注入矩阵，不新造 WAITING_USER_INPUT/运行中任务。
- 不自动 commit、push、merge、archive；云端部署须单独授权。
