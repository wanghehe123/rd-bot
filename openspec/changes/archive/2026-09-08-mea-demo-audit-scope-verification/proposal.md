## Why

Demo 复验发现两处未收口：选择历史 QA Attempt 时，任务当前 audited-state head 被呈现为该轮审计；读取验收脚本允许 HTTP 404 正文伪装成功，并将任意 4xx 视为跨任务归属校验通过。用户明确要求修复并执行真实业务测试。

## What Changes

- 保留现有任务级 head API，在工作台明确显示“任务最新审计”及每条记录的真实 sourceStageRunId；缺少来源时明确说明，不声称属于所选 Attempt。
- 任务 head 的其他轮次或无来源阻断不得进入所选 Attempt 的问题摘要。任务当前阻断可在任务最新审计区展示。
- 验收脚本正例要求 HTTP 200 及请求身份匹配；跨任务和不可下载内容要求 HTTP 404。错误响应不能转换为成功或“无数据”；SKIP 必须进入未验证清单。
- 真实分页列表不要求目标任务位于当前页；验证真实分页 DTO，避免历史任务误报失败。
- Manager 全文身份验证与有界 Coding 合同验证分开：QA/DONE/ASK/BLOCKED 的空合同合法；只对适用的 Coding 决策检查非空合同。无适用决策时明确记为未验证，不制造流程失败。
- 真实桌面复验追加修正：角色卡片的 CANCELLED 图标应标注“已取消”，与真实阶段及详情一致；不改变未创建角色的状态投影。
- 最终真实任务暴露默认页读取缺口：`issues` 页签没有加载已有 QA evidence，须按需请求并继续严格绑定 stageRunId；不能依赖用户先进入 Prompt 页签。
- 结构化摘要不能解析 `/result` 的截断预览。在选中有结果的 Attempt 时，沿相同 taskId/stageRunId 读取既有 `/result/content` 完整 JSON，保留过期响应保护，并明确显示读取失败。
- 补回归测试，重建组合候选，并以真实业务数据及一条普通需求记录验收结果。沿用原 Demo 范围，不恢复故障注入矩阵。

## Capabilities

### New Capabilities

- `requirement/mea-demo-audit-scope-verification`：任务审计 head 的展示归属与 Demo HTTP 验收断言。

### Modified Capabilities

- 无。无需修改既有状态机、任务审计写入、历史快照 API 或业务表。

## Impact

- `frontend/src/pages/admin/rdtask/roleDeliverableModel.ts`、`frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx` 及对应测试。
- `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx` 的取消图标语义。
- `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`、`frontend/src/services/stageResultService.ts` 的默认页证据和完整结果读取装配。
- `deploy/cloud-server/mea-live/verify_demo.py` 与聚焦 Python 回归测试。
- 新组合构建及 QA 记录；部署前核对实验占用，不中断他人任务。

## 来源与证据边界

- 原计划：主工作区 `docs/superpowers/plans/2026-09-08-mea-demo-delivery-plan.md`，属于本轮既定范围。
- 既有记录：`docs/superpowers/qa/2026-09-08-mea-demo-acceptance.md`，仅证明其记录版本；旧 `a93fd28b…` jar 不含本轮修复。
- 复现：所选 `qa-1` + head 来源 `qa-2` 仍显示 AC-2；HTTP 404 + source 的结果被旧脚本判成功。源代码与动态复现共同确认，不能由旧测试通过推导功能通过。
- 当前约束：`RULE.md` §3.5.15、Demo scope delta；历史文档分类方法参照主工作区 `docs/openspec/historical-spec-provenance-audit.md`。
- 本轮实际命令、构建身份及真实测试结果另记 QA 报告，不在 proposal 预先宣称 PASS。
- 真机数据锚点：Demo `7502920392433078272` 已 COMPLETED，QA stage `7502920712605274115` 有 23 条证据；默认页初次显示 0 条，切换 Prompt 后返回才出现 23 条。该 QA `/result` 为 20,000 字符截断预览，全文可从已存在的 `/result/content` 读取。
