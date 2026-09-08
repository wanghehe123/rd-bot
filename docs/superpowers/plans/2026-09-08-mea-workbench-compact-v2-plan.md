# MEA 工作台首屏精简与视觉验收 V2 实施计划

> **For agentic workers:** 按 `superpowers:executing-plans` 逐项执行；若委派代码探索，使用 Luna。步骤使用 `- [ ]` 跟踪。本文件是计划，全部实施任务尚未开始。不得据计划生成时间、已有测试数量或旧报告的 PASS 推断实施完成。

**Goal:** 保持原有视觉风格，让研发人员在首屏直接看到当前任务状态、所选角色产物、一个真实证据入口；Coding 首屏同时看到 MEA 当前轮三职责摘要，Prompt 一次点击可达，所选 Attempt 状态持续可见。

**Architecture:** 保留 `RdTaskDetailPage → TaskRoleWorkbench → RoleDeliverablesPanel` 和现有服务/身份模型，调整顶部布局、内容顺序与默认展开。复用当前完整结果读取、QA evidence、Coding MEA 和审计数据，不新建后端接口或全局状态系统。

**Tech Stack:** 现有 React 18、TypeScript、Tailwind、Radix/shadcn、lucide、Vite、Node `node:test`；真实页面使用当前可用的 CUA 浏览器能力验收。

**Spec:** 本文第 3–5 节是 V2 的具体展示合同；继承原 `2026-09-06-mea-task-workbench-frontend-plan.md` 的四角色/MEA/身份约束，以及 `openspec/changes/mea-demo-audit-scope-verification/` 的读取真实性约束。实施前建立 `openspec/changes/mea-workbench-compact-v2/`，主 spec 不直接修改。

## 1. 主代理亲自核对的结论

### 1.1 代码、计划与页面交叉核对

核对日期：2026-09-08。不是仅复述 Luna 的结论：主代理亲自读取了下表源码、原计划、旧验收表、相关规则，并重新查看了真实页面的桌面与 390px 视口。

| 项目 | 亲自确认的当前事实 | 结论 |
| --- | --- | --- |
| 部署 | 云端 jar 为 `d707cdafa1d470a21f193131fef49323098bc3650459b53efd4243f1f0c56de4`；HTTP 返回的任务页 JS/CSS 与本地 static 文件逐字节哈希相同 | 未发现漏部署；新开页面也能复现布局问题 |
| 顶部 | `RdTaskDetailPage.tsx:1423` 无条件渲染快速操作容器，成功任务只有空标题；`:1448` 再放 `TaskSummaryBand` | 存在无内容占位和额外大卡 |
| 重复状态 | `TaskSummaryBand`（`:1874` 起）显示状态、项目、任务 ID、角色、耗时；`TaskRoleWorkbench.tsx:268` 再显示阶段推进、当前角色、耗时 | 原计划“唯一任务状态摘要”未达成 |
| 角色卡 | `TaskRoleWorkbench.tsx:290–328` 只有角色、Attempt、执行状态/关注数、阻断文案 | 缺一句产物摘要；“无当前阻断”不能代替产物 |
| 摘要长度 | `RoleDeliverablesPanel.tsx:275` 将 `executionSummary` 全文渲染，无默认长度限制 | 完整结果读取修复后，真实 QA 长文直接撑大页面 |
| QA 指标 | `roleDeliverableModel.ts:266` 起生成 reportedChecks，但未给 QA 添加简短 deliverables | “最多三项”不等于真实 QA 已有三项简明指标 |
| 证据位置 | `RoleDeliverablesPanel.tsx:301–419` 先显示检查/审计，`:425` 才显示关键证据，MEA 又在其后 | 证据与 Coding MEA 不够靠前 |
| 细节展开 | 检查列表已有 `max-h-60` 内滚动，但命令、来源 ID 和 evidence URI 默认可见 | 不能说整个列表无限撑高；实际问题是默认密度及嵌套滚动 |
| 窄屏状态 | `TaskRoleWorkbench.tsx:381–440` 中 aside 位于整个主列之后；仅在 lg 设置 sticky | 390px 的状态摘要跟在正文后，未放在页签上方 |
| 宿主验证 | `HostVerificationCard.tsx:411` 已将成功步骤放进 details，但外层仍有完整卡头、轮次、解释信息 | 成功步骤折叠已做；整块压成简短一行尚未完成 |
| 测试证据 | `meaWorkbenchStates.test.ts:40–53` 通过读 TSX/正则检查布局类名；旧验收表将其写成 FE-11 PASS | 不能证明实际视口、首屏、焦点或无溢出 |

真实页面：任务 `7502920392433078272`，QA stage `7502920712605274115`。桌面 1280×720 首屏仍停留在角色导航/选择区，产物和证据在屏下；390×844 首屏仍被标题、空操作条和摘要卡占据。滚动后的 QA 页面可见长篇执行概述、6 条自报检查、7 条已完成任务审计、23 条 QA 证据。

### 1.2 哪些不需要重做

- 默认 `issues` /“产物与证据”、四角色导航、Coding 内 MEA、Prompt 页签已经存在。
- `taskId + stageRunId` 身份、Attempt 重挂载、过期响应保护、完整 `/result/content` 读取已经实现。
- QA evidence 默认请求和精确阶段过滤已经修复；PR #40 从真实 task DTO 取得。
- Agent 自报与 Host 审计已分区；任务 head 与历史 Attempt 不混用；缺来源明确显示。
- 成功 Host 步骤及原始 JSON 已有折叠基础；不再新做一套日志/审计系统。

这些是保留项，不作为 V2 的新增交付计数。247 项前端测试是既有基线，不是首屏精简完成的证明。

### 1.3 来源分级与读物

| 来源 | 使用方式 |
| --- | --- |
| 当前 worktree 的上述源码及本轮真实页面 | 当前行为证据，仅对实际核对范围负责 |
| 主仓库 `docs/superpowers/plans/2026-09-06-mea-task-workbench-frontend-plan.md` | 原设计/决策；第 93–98、279–284、393–404 行作为本轮补齐目标 |
| 源工作区 `docs/superpowers/qa/2026-09-06-mea-task-workbench-acceptance.md` | 历史实施声明；FE-01/03/11 的视觉 PASS 需要重新验证 |
| `docs/superpowers/qa/2026-09-08-mea-demo-closeout-and-live-verification.md` | 普通业务、接口与读取真实性证据；不替代 V2 视觉验收 |
| `RULE.md` §3.5.15、§5 前端请求/响应式约束、§6.2 | 必须保留的身份、加载与验证规则 |
| `openspec/specs/requirement/delivery-platform/spec.md` | 平台边界：四角色、Pi、历史任务只读等 |
| 主仓库 `docs/openspec/historical-spec-provenance-audit.md` | 资料分级规则；其中旧的全项目覆盖数量不当作当前事实 |

`mea-demo` 未包含原前端计划和历史 provenance 索引，读取时使用 `/Users/wish233/Documents/RD-Bot/` 下的相应文件。`docs/rd-task-management-requirements.md`、`docs/rd-task-management-design.md` 在本次核查目录中未找到，不伪称已读；使用当前 RULE、上述主 spec 和实际链路。必要来源在新 change 中记录绝对位置，不从旧工作区复制业务实现覆盖当前基线。

## 2. 分支、基线与执行边界

| 项目 | 决定 |
| --- | --- |
| 当前来源 | `/Users/wish233/Documents/RD-Bot/.worktrees/mea-demo`，`codex/mea-demo` |
| 当前 HEAD | `f3e7bcc93285420cf5a320d242f2ec9c50fbe342`，其后仍有未提交修复 |
| 新开发分支（建议，尚未创建） | `codex/mea-workbench-compact` |
| 新工作区（建议，尚未创建） | `/Users/wish233/Documents/RD-Bot/.worktrees/mea-workbench-compact` |
| 集成目标 | `codex/mea-demo`；实施结果须包含当前未提交的正确性修复，不从裸 HEAD 开始后丢失它们 |
| 本期后端 | 零功能修改、零协议/数据库修改；只读验证现有任务 |
| 发布 | 先本地前端直连真实后端验证，再生成一个最终组合包；具体包授权后再上传/切换 |

P00 必须先保存 `git diff HEAD --binary` 和经核对的 untracked 文件清单，建立新 worktree 后恢复该基线。**单独执行 `git worktree add ... f3e7bcc9` 并不能得到当前已验收源码。**

不复制 `node_modules`、target、缓存、`.env` 或其他实验工作区。不得 `reset --hard`、clean、批量覆盖源码。主工作区和 `mea-fresh-executor-episode`、`mea-task-workbench` 保持原状。

本计划不授权 commit、push、merge、archive 或新云端包部署。任务中的交付检查可完成后停在可审查 diff；不因“阶段完成”自动提交。

## 3. 新页面结构与首屏合同

### 3.1 选择的方案

采用“压缩现有结构、调整内容顺序”。只做少量 CSS 微调不能解决重复状态和长文抢占；整体重做布局/新建 MEA 大屏则超出 Demo 范围。因此保留现有组件，主要更改组合顺序、摘要层和折叠默认值。

```text
任务标题   [真实任务状态] [暂停/等待提示]        必要操作
当前流程位置 · 项目 · 次级耗时             [任务详情]
角色工作台 | 任务输入与交付 | 任务审计
需求评审       方案设计       Coding        QA
状态+最近轮次  状态+最近轮次  状态+最近轮次  状态+最近轮次
一句产物摘要   一句产物摘要   一句产物摘要   一句产物摘要
所选角色 · Attempt选择 · 当前/历史标签
产物与证据 | Prompt | 执行轨迹 | 运行记录
┌ 主区 ──────────────────────┬ 所选Attempt状态 ┐
│ 当前阻断/缺口（仅有问题时）   │ 当前动作/最后状态 │
│ 产物摘要：最多3项简短指标      │ 最近上报/必要待办 │
│ PR / 完整产物 / 关键证据入口  │ 来源与版本可展开  │
│ Coding：MEA当前轮三职责摘要   │                  │
│ 关键证据前5项 / 查看全部      │                  │
│ > 自报检查 / 任务最新审计明细 │                  │
│ > 执行概述全文 / 原始结果     │                  │
└────────────────────────────┴──────────────────┘
宿主验证：构建通过 · 静态通过 · 耗时 · 查看步骤（成功时一行）
```

900px 和 390px 使用堆叠布局：所选 Attempt 的简短状态放在四个内容页签之前，完整状态按需展开；不得把整个状态区排在产物/审计长文之后。只渲染一份状态内容，避免双份 DOM、重复 ID 与重复控件。

### 3.2 页面密度的可观察标准

- 顶部只有一套**任务级**状态/进展摘要；所选 Attempt 的状态是另一语义层，必须保留且明确标注，不能为去重删掉。
- 不渲染空快速操作条；现有提交/预算审批/补充信息/重试操作条件和 handler 不变，合并到同一操作区域。
- 标题、四角色、主导航、内容 Tab、按钮继续使用原配色/字号体系/边框/圆角；不通过把字体缩成不可读尺寸达到首屏目标。
- 四角色卡保留两到三行：角色与状态、最近 Attempt、一句产物摘要。缺真实摘要时显示原因，禁止把 `QA_AGENT result json` 当有效业务摘要。
- 单个产物摘要值默认最多两行；执行概述默认最多两行，完整文字可展开。最多三项不等于允许三段超长列表默认铺开。
- 首屏必须有一个真正可操作的证据/产物入口。QA 优先截图或其他真实 evidence；Coding 有真实 PR 时展示“任务 PR”；评审/方案有完整结果时提供对应阶段结果入口。没有可用内容时显示明确不可用状态，不编造链接。
- 正常完成样本在 1280×720、1440×900、900×900、390×844 的初始滚动位置，任务状态、角色选择、Prompt Tab、所选 Attempt 状态摘要、产物摘要、至少一个证据/产物入口均可见。
- Coding 的当前轮 MEA 三职责摘要在桌面首屏可见；390px 不强求三栏同屏，必须有清楚的当前轮摘要及展开入口。
- 长标题允许两行；任务 ID、Provider、成本细项、时间戳、hash、命令和 URI 移至详情。阻断提示优先可见；多个问题可“首条完整 + 总数 + 查看全部”，不得用“全部正常”替代其余问题。

上述视口是一组固定视觉检查，不是 12×3 稳定性矩阵，也不启动新业务或恢复实验。

## 4. 摘要与证据的真实性合同

### 4.1 四角色卡及核心产物

| 角色 | 可用字段形成的简短内容 | 不可用时 |
| --- | --- | --- |
| 需求评审 | 现有 feasibility、missingInformation、acceptanceCoverage、budgetEstimate 中实际有的值 | 执行状态保留；摘要暂不可用/尚未生成，不假称批准 |
| 方案设计 | affectedFiles/implementationSteps/testPlan 的完整数组计数或一行摘要 | 截断 JSON 无法解析时不计算完整数量 |
| Coding | 已知 changedFiles、现有宿主验证结果、任务 PR 入口 | 不借用其他 Coding Attempt 的产物；PR 明确任务级 |
| QA | 自报检查数量与通过/未通过数量、任务最新审计已完成记录数、所选阶段证据数 | 三类数量分别标注；不把 6 条检查写成 6 个 AC，不把 7 条审计记录写成 7 个业务 AC |

角色卡先消费已加载的 stage/resultSummary/resultPreview；可复用纯展示模型，禁止为四张卡并行下载四份完整 JSON。原始 `resultSummary` 是 `ROLE_NAME result json` 这类占位时，使用“结构化摘要暂不可用”等诚实降级。选择某阶段后，主区仍通过现有完整结果读取获取真实内容。

### 4.2 默认折叠与展开

| 区域 | 默认 | 展开后 |
| --- | --- | --- |
| 当前阻断/失败 | 展示明确问题和数量 | 全部问题、原因、现有恢复入口 |
| 产物指标 | 最多三项、每项两行 | 同一区域查看全部值，不能只留一句“去看原始 JSON” |
| 关键证据 | 顶部至少一个快捷入口，列表最多五项 | 在产物页查看完整已加载列表，保留当前 task/stage；不强迫先进入 Prompt |
| QA 检查与任务最新审计 | 两组计数与明确身份标签；成功明细折叠 | 检查项、命令、真实 sourceStageRunId、evidenceRefs |
| 执行概述 | 最多两行 | 完整 summary；展开状态随 Attempt 切换重置 |
| 原始结果 | 折叠 | 现有完整 JSON/明确预览/不可用原因 |
| MEA | 当前轮三职责简短结果展开，历史折叠 | 现有 Manager 全文、command、audit、QA 关联 |
| Host 验证成功 | 一行概要 | 现有步骤/日志/历史，不重复实现日志请求 |
| Host 验证失败 | 失败原因和失败步骤直接显示 | 其余步骤与日志 |

用现有 Radix/原生 details 或受控按钮完成折叠。展开按钮有明确名称和 `aria-expanded`；简单内联展开不强加 Dialog 的 Esc 语义，只有使用 Dialog 时才验收 Esc/焦点归还。

### 4.3 不得回退的已有能力

- 不改 `codingStageRunId` 参数、`boundedContract` wire 类型、`id/text/evidenceRefs` 映射。
- 不改 role/Attempt URL 语义，不用 role 名或轮次号猜 stage 身份。
- 不改 `getCompleteStageResult` 两阶段读取及其过期响应保护。
- `/result` 的截断预览不能计算完整覆盖率；已有完整读取失败仍显示错误，不能显示已确认的零项。
- 审计 head 继续标“任务最新审计”；无 sourceStageRunId 继续明确未记录，不补造来源。
- 任务 paused、stage CANCELLED、等待材料、预算审批保持各自原有语义；本计划不扩展控制状态机。
- 窄屏状态展示只重排现有组件；不启用项目未开启的 PI_AGENT_STATE_V2，不构造动态待办。

## 5. 文件责任与数据边界

路径均相对新工作区根目录。

| 文件 | V2 责任 |
| --- | --- |
| `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` | 合并页头摘要/操作，移除空操作占位，Host 成功概要调用；保留加载与控制 handler |
| `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx` | 去重复进度头、角色卡摘要、紧凑选择器、响应式主区/状态栏顺序 |
| `frontend/src/pages/admin/rdtask/roleDeliverableModel.ts` | 保留完整可展示列表，增加 QA 简短指标；语义边界不变 |
| `frontend/src/pages/admin/rdtask/workbenchSummaryModel.ts`（新） | 小型纯函数：角色卡一句话、检查计数；不发请求、不解析新的状态协议 |
| `frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx` | 首屏指标/入口、明细展开、完整列表入口、MEA 顺序；保留异步保护 |
| `frontend/src/components/admin/rdtask/CodingMeaPanel.tsx` | 当前轮紧凑呈现；只调整 JSX/默认展开，不修改轮次关联 |
| `frontend/src/components/admin/rdtask/RoleAgentStateCard.tsx` | 短状态与详情分层、清楚的当前/历史标题，复用既有解析 |
| `frontend/src/components/admin/rdtask/HostVerificationCard.tsx` | 增加 `compact?: boolean`，仅 roles 成功态使用；audit 视图仍默认完整 |
| `frontend/test/workbenchSummaryModel.test.ts`（新） | 真实执行摘要/计数纯函数的边界测试 |
| 现有 `roleDeliverableModel.test.ts`、页面/布局 contract tests | 保护身份、完整列表与现有加载合同；明确属于静态检查 |
| `docs/superpowers/qa/2026-09-08-mea-workbench-compact-v2-acceptance.md`（新，实施时） | 同视口前后对照、真实请求、失败原因、版本、未验证范围 |

不修改 `codingMeaModel.ts`、后端 Java、bridge、QA skill、数据库迁移或部署脚本，除非实施中发现明确独立缺陷并先记录出界原因。本轮不得顺手调整 Manager/执行器行为。

## 6. 逐项实施任务

### P00：建立完整基线与补充展示合同

**交付物：** 新工作区包含 d707 构建对应的当前源码修复；有可审查的基线清单和新 OpenSpec delta。

- [ ] 在来源运行 `git status --short`、`git rev-parse HEAD`，记录当前 dirty 文件；核对源码相对 d707 验收记录是否又有变化。
- [ ] 保存 tracked patch 和 untracked 清单；逐项审阅清单，尤其新测试、两个已有 delta、QA 文档。不得盲目复制被忽略文件。
- [ ] 创建开发 worktree，应用 patch，逐文件复制经审阅的 untracked 文件并核对 SHA。源目录不切分支、不提交、不清理。
- [ ] 在新 worktree 建立 `mea-workbench-compact-v2` delta，把第 3–4 节写成实际场景；记录来源分类及未执行的视觉项目。
- [ ] 新 worktree 的前端基线测试/typecheck 通过后再进入 P01；保存同一真实任务的四个视口“修改前”截图，不能用业务网站截图代替 RD-Bot 工作台截图。

操作骨架（只在执行阶段运行）：

```bash
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-demo diff HEAD --binary > /tmp/mea-workbench-v2-baseline.patch
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-demo ls-files --others --exclude-standard -z > /tmp/mea-workbench-v2-untracked.list
git -C /Users/wish233/Documents/RD-Bot worktree add -b codex/mea-workbench-compact /Users/wish233/Documents/RD-Bot/.worktrees/mea-workbench-compact f3e7bcc93285420cf5a320d242f2ec9c50fbe342
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-workbench-compact apply --check /tmp/mea-workbench-v2-baseline.patch
git -C /Users/wish233/Documents/RD-Bot/.worktrees/mea-workbench-compact apply /tmp/mea-workbench-v2-baseline.patch
```

命令不包含 untracked 自动复制。实施者在审阅清单后用 Python `shutil.copy2` 按名单逐文件复制，记录相对路径、大小与 SHA；拒绝 `.env`、缓存、node_modules、target 和越出来源根目录的路径。清单与 patch 缺一不可。

### P01：把顶部压成一套任务摘要

**Files:** `RdTaskDetailPage.tsx`、`TaskRoleWorkbench.tsx`；相关页面 contract tests。

**接口：** 保留 `task`、`executionOverview`、`auditedState` 与现有按钮 handlers；不引入新请求。

- [ ] 将标题附近的 task.status/paused、当前流程位置与必要操作合并为紧凑页头。任务 ID、时间、Token 和审计计数放次级行或“任务详情”。
- [ ] 删除 roles 页中第二块独立摘要大卡；原 `TaskSummaryBand` 的等待材料、恢复中、阻断提示迁到页头，不能一起删掉。
- [ ] 将快速操作条件直接复用到页头操作区；所有条件为 false 时不渲染容器。预算/补充材料对话框保留。
- [ ] 工作台 header 仅保留必要名称/错误提示，移除重复 currentRole/elapsed/progress；已有 overviewError 必须仍可见。
- [ ] 用真实成功任务和取消历史任务分别打开验证：无空白操作条，只有一套任务摘要，操作没有错绑；本步骤不点击任何写操作。

渲染条件应复用既有 predicate，例如：

```tsx
const hasQuickAction = (task.taskType === "REQUIREMENT" && canSubmitRequirementTask(task))
  || task.status === "AWAITING_BUDGET_APPROVAL"
  || canAnswerRequirement(task);
// 与原 handler 一起迁入单一操作区；false 时不输出“快速操作”空壳。
```

**退出条件：** 桌面顶部明显缩短；任务等待/失败信息仍可见。单纯隐藏大卡但丢失阻断或操作不通过。

### P02：让四角色卡和 QA 摘要真正提供产物信息

**Files:** `workbenchSummaryModel.ts`、`roleDeliverableModel.ts`、`TaskRoleWorkbench.tsx`；两份模型测试。

**新增纯函数边界：**

```ts
export type CheckStatusLike = { status: string };
export function summarizeChecks(checks: readonly CheckStatusLike[]): {
  total: number; passed: number; other: number;
};
// PASS/PASSED 计 passed，其余保持 other；other 不能统一改名“失败”。

export function roleCardSummary(input: {
  taskId: string;
  stage?: import("./roleWorkbenchModel.ts").RoleStageLike;
}): string;
// 只消费既有字段/完整可解析预览，复用 buildRoleDeliverables；不请求网络。
```

- [ ] 先补失败回归：占位 resultSummary 不作为摘要、截断预览不能生成完整计数、缺阶段显示未创建、PASS/PASSED 与 SKIPPED/BLOCKED/未知值分别处理。
- [ ] 为 QA 生成简短指标：“Agent 自报检查”“任务最新审计已完成记录”“当前 Attempt QA 证据”，三者来源与标签分开；已有缺口分类不变。
- [ ] 四卡用一句摘要替换默认“无当前阻断”占位；有真实 blocker 时仍显示关注标签，详情保留完整原因。卡片描述明确使用最近 Attempt，历史选择在主区另标。
- [ ] 产物模型保留 `allDeliverables` 与完整阶段证据列表，例如在 View 新增 `allDeliverables`、`allEvidence`；旧 `deliverables/keyEvidence` 的前三/前五兼容字段可暂留。前端展开不能凭空补数据。
- [ ] 运行模型测试，再实际查看评审/方案/Coding/QA 四卡，确认可用字段能显示、缺数据诚实降级；不得为满足四卡摘要而同时请求四份完整结果。

测试内容示例：

```ts
assert.deepEqual(summarizeChecks([
  { status: "PASSED" }, { status: "PASS" }, { status: "SKIPPED" }
]), { total: 3, passed: 2, other: 1 });
assert.equal(summarizeChecks([]).total, 0);
// 对现有真实形状 fixture 增补断言：totalEvidenceCount=23，keyEvidence=5，allEvidence=23；
// 所有 evidence.stageRunId 保持所选阶段，跨阶段记录不进入任一列表。
```

**退出条件：** QA 不再只剩一段长执行概述；角色卡有真实摘要或明确不可用原因，未产生新数据获取链。

### P03：把产物/证据/MEA 提前，长内容默认收起

**Files:** `RoleDeliverablesPanel.tsx`、`CodingMeaPanel.tsx`、`HostVerificationCard.tsx`，必要的 presentation tests。

**接口：** 继续消费当前 `RoleDeliverableView` 和 `CodingMeaView`；`HostVerificationCard` 增加可选 `compact`，默认 false，roles 调用设 true。

- [ ] 按第 3 节顺序重排 JSX：当前问题 → 最多三项摘要 → 一行产物/证据快捷入口 → Coding MEA → 五项关键证据 → 折叠验证明细 → 折叠长文/原始结果。
- [ ] 给执行概述、产物长值增加两行预览和真实展开入口。完整 JSON 读取依然自动完成，折叠仅影响呈现，不能回退成只读截断预览。
- [ ] 自报/审计保留两组摘要；成功明细默认折叠。完整命令和 evidence URI 放到明细中。任务 head 阻断要从折叠成功记录区移出，仍清楚标“任务当前阻断，不计入所选 Attempt”。
- [ ] “查看全部产物/证据”在当前页生效，使用 P02 完整列表；保留当前 task/stage 和真实 contentUrl。仅变更展开状态不得触发 task audit-content 或整任务日志下载。
- [ ] Coding 当前轮先展示三职责的短结果，Manager rationale/完整合同/command ID/audit hash 留详情；MEA 状态来源与 DONE≠任务完成不变。成功 Host 验证整块压成一行；失败仍展开失败原因。
- [ ] 切换 Attempt 时重置本阶段的展开状态，并复跑现有 stale-response 测试；真实 QA 23 条证据“查看全部”全部可达，至少点击一个截图确认可打开。

局部展开的实现约束示例：

```tsx
const [summaryExpanded, setSummaryExpanded] = useState(false);
// 接入现有 stageResultIdentity 的 reset effect，不能另起一个结果请求 effect。
<p className={summaryExpanded ? "break-words" : "line-clamp-2 break-words"}>
  {view.executionSummary}
</p>
<button type="button" aria-expanded={summaryExpanded}
  onClick={() => setSummaryExpanded(value => !value)}>
  {summaryExpanded ? "收起执行概述" : "展开执行概述"}
</button>
```

**退出条件：** 未展开时不出现大段 command、JSON 或 URI；真实阻断仍可发现。Coding 的 MEA 摘要不再排在无意义的“本阶段 QA 证据 0 项”之后。

### P04：状态栏与响应式顺序收口

**Files:** `TaskRoleWorkbench.tsx`、`RoleAgentStateCard.tsx`；保留现有状态解析模块。

- [ ] 将主区/aside 改为有明确区域的单份响应式 DOM：桌面左主区右状态；窄屏短状态先于内容 tabs 和正文。可使用 CSS grid area/order，键盘顺序需与视觉顺序一致。
- [ ] 统一所选阶段行：角色、Attempt 选择器、当前/历史标签一处显示，移除占位 `ROLE result json` 副标题；状态卡保留足够的身份文字，不能仅依赖颜色。
- [ ] 当前/最后状态正文只保留当前动作、必要待办、最近上报和阻断；runtime/sequence/hash/注入详情展开后查看。能力未启用时保持真实不可用提示。
- [ ] 在四个规定视口检查标题换行、四卡布局、按钮点击、无水平溢出；390px 必须在首屏见到状态摘要和 Prompt 入口。禁止仅凭 `lg:*` 类名判通过。
- [ ] 使用真实历史 Attempt 来回切换并切换四个内容 tab；确认右侧/上方状态身份不串，不因展开组件导致旧响应覆盖。

**退出条件：** 桌面和窄屏都满足第 3.2 节；移动端不复制第二份状态组件。自报待办无数据不阻塞其他内容。

### P05：真实页面验收与版本核对

**Files:** 新 QA 报告、实际截图/请求摘要；按需要更新现有静态 contract test 的断言，但不把它升级成视觉证据。

- [ ] 先执行下列前端命令。纯 CSS/重排不新增机械式正则测试；新摘要/计数逻辑必须有实际函数断言。已有契约若因有意重排失效，调整其范围并保留语义守卫。
- [ ] 本地 Vite 开发页面通过当前项目已有代理连接真实后端；先读取 `vite.config.ts` 确认实际配置方式，不能猜环境变量。不得把新页面切到 fixture 后写“真实数据通过”。
- [ ] 对照第 7 节完成同视口“修改前/修改后”截图与实际点击，记录页面 URL、task/stage、控制台及关键请求结果。
- [ ] 完成后才运行生产 build，核对输出中的本次变化、静态文件 SHA 和所有打包静态资源；生成组合 jar 并与 d707 包递归比较非前端内容。
- [ ] 将新 jar SHA、目的地、切换时机和回滚路径作为具体发布项供授权。授权后重查无其他实际执行，再切换；不得拿旧 d707 的上传授权覆盖新包。
- [ ] 最终部署后新开/重载页面，复验 QA 默认页、Coding MEA/PR 和历史 Attempt，记录 HTTP 静态资源哈希与构建一致。完成后再勾对应验收项。

```bash
# frontend 目录；当前 package.json 没有 npm test 脚本
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build

# worktree 根目录
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
git diff --check
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw -o -pl bootstrap -am -DskipTests package
```

package 命令跳过 Java 测试，报告须明确；本计划没有 Java 修改，不为前端布局重跑全仓 Java 或新 Pi 镜像。若实际触碰后端，退出本范围重新评估。

### P06：交接与完成状态纠正

- [ ] QA 报告分别列出：既有业务完成、V2 展示完成、静态测试、真实浏览器验证、未验证范围。
- [ ] 在新工作区的后续验收报告记录旧 FE-01/03/11 证据不足与本次新证据；不去原来源工作区改旧报告，不抹掉历史声明。
- [ ] 对照 P00 的 baseline 清单检查差异；列出本次新增文件、生成资源和所有权，确认主工作区/来源工作区未被动到。
- [ ] 提供开发分支、集成目标、构建 SHA、真实页面地址、截图路径和回滚包；清理本轮专用临时服务或明确告知保留的端口。
- [ ] 仅当第 7 节必须项通过后，写“前端 V2 首屏精简验收通过”；否则列具体缺口。未获授权不自动 commit/push/merge/archive。

## 7. 最小但真实的验收矩阵

所有截图必须是 **RD-Bot 管理工作台**，不能拿 PR #40 业务网站的营业说明截图替代。

| ID | 数据/操作 | 必须看到的结果 | 证据 |
| --- | --- | --- | --- |
| V01 | 普通任务 QA，1280×720、1440×900 初始位置 | 唯一任务摘要；状态/角色/Prompt/产物摘要/至少一个证据入口首屏可见；无空操作条 | 同 URL/同视口前后截图 |
| V02 | 同任务 Coding，两个桌面视口 | 任务 PR #40、产物短摘要、当前 MEA 三职责摘要首屏可见；外层仍四角色 | 截图及 PR 链接目标 |
| V03 | 同任务 QA，900×900、390×844 | 短状态在页签前；关键入口首屏可达；无横溢出/顶栏遮挡 | 截图、实际展开/收起 |
| V04 | 同任务 QA 展开全部 | 23 条 evidence 可达；6 条自报与7条任务已完成审计分别标注；长文/命令默认收起、展开后仍完整 | 点击记录、一个真实截图响应 |
| V05 | 旧07l `7502759980530012160`，Coding Attempt 2→1→2 | stage 身份正确；Attempt 1 预览不能留到取消的 Attempt 2；CANCELLED 仍显示已取消 | 状态与内容前后记录 |
| V06 | 四个内容 tab 和两个角色之间切换 | Prompt 一次点击可达；所选状态持续可见；无跨角色/Attempt污染 | 浏览器点击及 console |
| V07 | W1e `7502196308401328128` 只读 | 历史缺口和 Manager DONE 不改变任务失败结论；无关联明确提示 | 页面状态与实际接口 |
| V08 | 默认载入与折叠/展开 | 只读取当前需要的完整结果；不为了四卡同时下载四份全文；不触发 audit-content/整任务事件全文 | 关键请求摘要 |
| V09 | 最终云端版本 | 新开的浏览器页面加载本次 JS/CSS；hash 与构建一致，0 新 error/warn | 资源 hash、console、jar hash |

视觉是否在首屏，以实际 viewport 矩形和截图为准，不以 DOM 中能搜索到文字、`isVisible()` 单项或类名存在为准。可以记录元素 `getBoundingClientRect()`：关键入口 top≥0 且 bottom≤viewportHeight、无覆盖；这只是辅助，仍需看截图与点击。

不要求新造 WAITING_USER_INPUT/运行中任务、kill JVM、预算耗尽、W2/W3 或 12×3 矩阵。若现成真实样本恰好存在可只读查看；没有则在报告明确未覆盖，以已有逻辑回归保护。390/900 只做受改动页面的布局冒烟，不扩大成移动业务专项。

## 8. 给执行 Agent 的直接指令

```text
请执行本计划 P00–P06。先保护 codex/mea-demo 的未提交基线，在
codex/mea-workbench-compact 独立工作区实施，禁止从裸 f3e7bcc9 开始丢失修复。
目标是可见的首屏精简：去重顶部、角色产物摘要、产物/证据/MEA前置、长文默认收起、
窄屏状态前置。保持现有风格和所有 taskId/stageRunId/审计来源守卫。
本期不改后端、不新建业务任务、不重跑P2矩阵，不自动commit/push/archive。
布局必须用真实RD-Bot页面在指定视口验收；Node正则测试不算视觉PASS。
先完成本地前端连真实后端的所有检查，再打一个最终包申请具体发布授权。
交接给用户的是分支、可审查diff、构建hash、前后截图和逐项真实验收结果。
```

## 9. 本次计划交付说明

本轮仅新增本计划。主代理亲自核对原计划、当前关键 TSX/模型、旧验收表、RULE/主 spec 和真实桌面/390px 页面；恢复了浏览器临时视口。未创建上述新分支，未修改业务代码、未重新 build 或部署，未运行新的实现测试，未提交或归档。执行任务全部保持未勾选。
