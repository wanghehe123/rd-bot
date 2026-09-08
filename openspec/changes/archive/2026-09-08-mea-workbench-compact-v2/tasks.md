## 1. P00 基线

- [x] 1.1 保存来源工作区 tracked patch 与 untracked 清单，创建 `codex/mea-workbench-compact` worktree 并恢复基线（patch 应用 + 逐文件复制校验 SHA）。
- [x] 1.2 前端基线测试 247/247 与 typecheck 通过；确认继承 d707 构建对应的当前源码修复。
- [x] 1.3 保存同一真实任务四个视口的“修改前”截图（1280×720、1440×900、900×900、390×844），另补 Coding 两桌面视口。

## 2. P01 顶部收口

- [x] 2.1 合并页头摘要/操作，移除空快速操作容器；`TaskRoleWorkbench` 移除重复进度头；等待/阻断提示保留（TaskHeaderNotice）。
- [x] 2.2 真实成功任务与取消历史任务分别打开验证：无空操作条、单套任务摘要、操作绑定正确（7502920392433078272 与 7502759980530012160）。

## 3. P02 角色卡与 QA 摘要

- [x] 3.1 新增 `workbenchSummaryModel.ts`（`summarizeChecks`、`roleCardSummary`）及边界测试（占位摘要、截断预览、缺阶段、PASS/PASSED/SKIPPED/BLOCKED/未知）。
- [x] 3.2 `roleDeliverableModel.ts` 增加 QA 简短指标并补测试（真实形状 20/23 条证据、keyEvidence=5、allEvidence 完整、跨阶段不混入）；View 增加 `allDeliverables`/`allEvidence`。
- [x] 3.3 四角色卡接入一句摘要；真实页面确认可用字段显示、缺数据诚实降级（截断预览不计算完整数量）。

## 4. P03 产物页顺序与折叠

- [x] 4.1 `RoleDeliverablesPanel` 按“问题 → 三项摘要 → 快捷入口 → MEA → 五项证据 → 折叠明细 → 折叠长文/原始结果”重排；执行概述两行预览 + 展开按钮（aria-expanded），Attempt 切换重置展开状态。
- [x] 4.2 `CodingMeaPanel` 当前轮单行三职责摘要；`HostVerificationCard` 增加 `compact`，roles 成功态一行概要。
- [x] 4.3 任务 head 阻断移出折叠成功区；完整 JSON 读取保持自动；展开操作不触发 audit-content/日志下载。

## 5. P04 响应式收口

- [x] 5.1 主区/状态栏单份响应式 DOM：桌面 grid 右列，窄屏短状态在页签前；移除 `ROLE result json` 副标题占位，增加当前/历史 Attempt 徽标。
- [x] 5.2 `RoleAgentStateCard` 短状态/详情分层；四个视口检查无水平溢出（390px overflow=0），首屏可见状态摘要与证据入口。

## 6. P05 验收与打包

- [x] 6.1 前端全量测试 261/261、typecheck、build 通过；OpenSpec `--strict` 22/0；`git diff --check` 干净。
- [x] 6.2 本地 Vite 经项目代理（RD_BOT_BACKEND_TARGET=127.0.0.1:18080 SSH 隧道）连真实后端，完成 V01–V08 同视口前后截图与点击记录。
- [x] 6.3 生成组合 jar（SHA `82bcb02d…a06eb58`）并与 d707 递归比较：非前端内容源码一致（类字节差异为 JDK 微版本）；发布项已写入 QA 报告，未授权不部署。

## 7. P06 交接

- [x] 7.1 QA 报告分类列出既有完成/V2 完成/静态测试/真实验证/未验证范围，记录旧 FE-01/03/11 证据不足与新证据（`docs/superpowers/qa/2026-09-08-mea-workbench-compact-v2-acceptance.md`）。
- [x] 7.2 对照 P00 基线清单检查差异；确认主工作区与来源工作区未被改动；未 commit/push/archive。
