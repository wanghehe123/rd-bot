# 任务详情页：当前状态与 Prompt 追踪优先实施计划

> **For agentic workers:** 使用 `superpowers:executing-plans` 按任务实施；仅在用户明确授权后使用子代理。步骤以 `- [ ]` 跟踪。本文件本身不授权上线、改变执行协议或接管其他人的未提交改动。

**Goal:** 在保持 RD-Bot 原有视觉风格、角色与 Attempt 语义的前提下，让研发人员进入任务详情就能看到任务当前状态、所选 Attempt 的 Prompt 和 Agent 状态，并能直接切换执行追踪。

**Architecture:** 保留 `RdTaskDetailPage → TaskRoleWorkbench` 链路和 `roles/delivery/audit` 三个视图。工作台桌面主体改为左侧内容页签、右侧 Agent 状态；默认左页签为 Prompt，右侧状态在切换左页签时持续显示。复用现有 `role-prompts` 响应、精确 stage 绑定和 freshness guard，仅调整布局、默认页签及数据加载触发范围。

**Tech Stack:** React 18、TypeScript、Vite、Tailwind、现有 shadcn/Radix Tabs、`MarkdownRenderer`、Node `node:test`。

**Spec:** 本文第 2–7 节定义本次目标展示合同；既有主规范为 `openspec/specs/requirement/delivery-platform/spec.md`、`openspec/specs/requirement/audit-only-writeback/spec.md`。Prompt/state 补充来源和可信等级见第 1 节。实施前创建本文第 8 节指定的新 OpenSpec change。

**状态与交接边界：** 2026-09-05 编写的待实施方案。已进行截图分析、Luna 只读代码追踪和文档核对；本轮没有修改业务代码、运行前后端测试或完成真实浏览器验收。示意数据不是实际任务记录。文中“必须/应”描述目标合同，不表示当前系统已经实现。

## Global Constraints

- 用户最新要求：**“重点是：当前状态和追踪（prompt和当前状态栏），保持原有的风格不变”**。
- 本文取代上一版“默认概览、整体收起执行详情”的建议；不把 Prompt、当前状态栏收进“执行详情”总折叠区。
- 不改顶层三个入口名称：`角色工作台`、`任务输入与交付`、`任务审计`。
- 不改四角色顺序，不把 `HOST_VERIFY` 当成第五个 Agent。
- 保留 URL 值 `view=roles|delivery|audit` 和 `tab=issues|evidence|runs|trace`，保留 `role/attempt` 精确定位。
- Prompt、effective context、latest state 和轨迹只绑定同一 `stageRunId`，禁止跨角色/Attempt 借用或按名称猜测。
- 最新 Agent 状态不等同于实际已注入上下文；Agent 待办完成不等同于宿主已审计验收通过。
- 不修改任务状态机、审批/恢复/重试语义、Host 审计器、QA 协议、数据库、后端响应合同或 Pi 镜像。
- 保留 `RULE.md` §5.4 的 shell 优先、overview 按视图加载、audit-content 按需、归档末页读取及请求去重规则。
- 所有样式复用现有组件与样式变量/classes；不引入新组件库、字体、主题、图标库或页面级设计系统。
- 工作区可能有其他 Agent 的改动。先读当前 `git diff`，以小范围修改叠加，不整文件覆盖、重置或回滚他人内容。

---

## 1. 证据、前置阅读与当前实现

### 1.1 必读材料与证据等级

| 材料 | 用途与本轮分类 |
| --- | --- |
| `RULE.md`，重点 §3.5.3、§5.4、§6 | 当前仓库强制规则；实施前重读当前版本 |
| `docs/rd-task-management-requirements.md`、`docs/rd-task-management-design.md` | 必读历史背景；其中旧 BugFix/CRUD 状态和旧接口不能直接作为当前需求交付真值 |
| `docs/openspec/historical-spec-provenance-audit.md` | 历史材料分类依据；索引不是当前代码证明 |
| `openspec/specs/requirement/delivery-platform/spec.md` | 当前主合同：需求交付入口、四角色、历史只读边界 |
| `openspec/specs/requirement/audit-only-writeback/spec.md` | 当前主合同：Agent 声明、已审计状态、完成绑定的区别 |
| `docs/superpowers/specs/2026-07-14-role-prompt-evidence-view-spec.md` | 历史实施声明；用于理解真实 Prompt、安全预览和任务基线差别，当前行为须以代码/测试复核 |
| `docs/superpowers/plans/2026-08-18-pi-effective-context-prompt-page-frontend.md` | 历史计划；不可按其中“待新增类型”等文字重复实现已有能力 |
| `openspec/changes/pi-agent-state-and-qa-remediation-v2/specs/requirement/role-effective-context-audit/spec.md` | 在途 delta 的设计合同；精确 Attempt、真实注入、freshness 和安全边界参考 |
| `openspec/changes/pi-agent-state-and-qa-remediation-v2/specs/requirement/pi-agent-context-state/spec.md` | 在途 delta 的状态合同；不把其归档/部署状态视为已验证 |
| `docs/superpowers/specs/2026-07-28-qa-evidence-reference-and-production-mode-spec.md` | 历史证据协议来源；UI 重组继续保留 QA 证据可达性 |

本轮当前行为判断来自 Luna 对当前工作树的只读追踪和测试源码阅读，**尚未重跑测试，不能标为本轮 `VERIFIED_CURRENT`**。实施者须在新 change 中记录当时的源码锚点、实际运行的命令与结果。

### 1.2 真实链路与关键文件

以下行号为本轮探索时定位提示，实施时以符号为准重新定位。

| 文件与锚点 | 当前职责 / 本次处理 |
| --- | --- |
| `frontend/src/App.tsx:41` | `/admin/rd-tasks/:taskId` 路由；本次不改路径 |
| `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx:254` | 解析 view/role/attempt/tab；当前无有效 tab 时默认 `issues`，本次改为 `evidence` |
| 同文件 `loadInitial:311`、`refreshCore:345` | 任务 shell 与核心刷新；保持先出首屏和串行刷新边界 |
| 同文件 `loadAuditContent:391` | 任务级大文本按需；不作为本次 Prompt 的来源 |
| 同文件 `loadRolePrompts:412` | signature、请求 generation/sequence、expected identity 与 freshness 校验；保留 |
| 同文件 `loadRoleEvidenceData:528` | QA/RAG 等辅助证据；拆开“默认显示 Prompt”与“用户展开证据”的触发条件 |
| 同文件 effects `784–847` | overview、Prompt、证据、恢复等按视图加载；本次主要加载改动处 |
| 同文件 header `1223`、`TaskSummaryBand:1702` | 合并重复任务摘要；不删唯一的动作、暂停标记或失败说明 |
| `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx:233` | 四角色卡、Attempt、页签与角色内容；桌面两列和状态常显的组合位置 |
| 同文件 `RoleEvidencePanel` | Prompt 与上下文/QA 证据；复用 Prompt，辅助证据下沉折叠 |
| 同文件 `ExecutionTracePanel:932`、`RuntimeExecutionEventsPanel:973` | 运行事件、SSE/poll/归档；保留机制，仅调整入口顺序与标签 |
| `frontend/src/components/admin/rdtask/RoleEffectiveContextCard.tsx:36` | Prompt/effective/latestState 三页签；将 latest state 提出，Prompt 保留两种查看方式 |
| 同文件 `AgentLatestStatePanel:306`、`AgentTodoList:375` | 已有完整结构化状态展示；复用而不是另写状态 JSON 解释器 |
| `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts:145` | `buildRoleWorkbench` 精确绑定 stage/prompt/evidence；保留 |
| 同文件 `selectRoleAttempt:217` | URL → 失败角色 → 活跃角色 → 最近更新角色 → 首角色；不改变此规则 |
| 同文件 `roleStageSignature`、freshness 相关函数 | 状态和注入 identity 更新检测；保留现有修复 |
| `frontend/src/services/rdTaskService.ts:192` | role-prompts 与 effective/latest state 类型；本次预计只读 |
| `frontend/src/pages/admin/rdtask/rdTaskDetailLoader.ts` | task request guard 与 shell loading；本次预计只读 |
| `frontend/src/components/admin/rdtask/HostVerificationCard.tsx` | 最新验证和历史轮次；默认摘要，步骤/命令/日志在详情 |
| `frontend/src/components/admin/rdtask/AuditedTaskStateCard.tsx` | 已审计状态；保留在任务审计视图，不用 Agent TODO 替代 |

### 1.3 当前工作树协作风险

编写计划时，以下相关文件已经有未提交变更：

- `frontend/src/components/admin/rdtask/RoleEffectiveContextCard.tsx`
- `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts`
- `frontend/test/rdTaskRolePromptPresentation.test.ts`
- `frontend/test/roleWorkbenchModel.test.ts`
- `RULE.md`
- 后端角色 Prompt 测试、Agent/审计相关代码及静态构建产物也有变更。

实施前保存这些文件的 diff 作为对照。不要把旧版本复制回来。尤其保留当前工作树中的真实 Prompt 绑定、缺失原因、freshness/injection 检查；这些不是本次 UI 精简要清除的内容。不要顺手整理其他在途 change 或归档他人的 MEA 工作。

本轮已核对的两项具体未提交修复，实施后必须仍存在：

1. `RoleEffectiveContextCard` 在 `promptStage` 缺失时显示“当前 Attempt 尚无已绑定的 Prompt 读模型。”，不能用“有效上下文未注入”等其他原因掩盖没有绑定读模型的事实。
2. `evaluateRolePromptsFreshness` 仅在对应 `latestState.available` / `effectiveContext.available` 为真时比较该组 sequence/hash 和 ahead 状态。不可用的动态状态不等于 sequence 落后；历史或未启用 v2 的 PI Attempt 仍可合法返回真实静态 Prompt。

## 2. 用户可见目标与优先级

### 2.1 首屏必须回答的问题

1. **任务现在是什么状态？** 查看任务级状态、暂停标记和必要操作。
2. **我正在查看哪一个角色的哪一次执行？** 角色、Attempt、执行状态明确可见，历史 Attempt 不冒充当前执行。
3. **该次执行实际收到什么 Prompt？** 默认展示安全 Prompt 预览，能区分最近真实注入上下文与静态指令。
4. **该 Agent 正在做什么？** 当前目标、阶段、执行待办、阻塞、数据更新时间可直接读取。
5. **怎样追踪具体执行？** 一次点击进入执行追踪；切换后右侧状态仍然可见。

### 2.2 本次信息等级

| 等级 | 内容 | 默认展示原则 |
| --- | --- | --- |
| P0 | 任务状态、选中角色/Attempt、Prompt、Agent 当前目标/阶段/进行中或阻塞待办 | 不放入总折叠区，不依赖进入“审计”才能查看 |
| P1 | 执行事件、角色结果、失败处理、关键宿主验证结论 | 一次点击可达；真实失败原因必须有可见摘要 |
| P2 | Provider、Run ID、完整 hash、Prompt provenance、预算详细项、已完成待办、历史错误、命令、原始 JSON、完整证据表 | 折叠或留在对应详情，信息仍可达 |

P0 和 P1 是查看优先级，不是新权限或新状态机。信息精简依靠去重、调整默认内容和逐步展开，不删除审计记录或缩小后端证据集合。

### 2.3 必须撤回的上一版方案内容

- 不把“角色工作台”改名为“概览”。
- 不默认收起整个角色工作台。
- 不使用大面积成功横幅或以 PR/验收统计为中心的首屏。
- 不新增一套圆角、颜色、字体、按钮或导航样式。
- 不把“当前状态”继续隐藏在 Prompt 卡内部的第三个页签。

## 3. 目标布局与交互

### 3.1 桌面结构

```text
现有任务头部：任务标题 | 任务状态/暂停标记 | 当前必要动作

[ 角色工作台 ] [ 任务输入与交付 ] [ 任务审计 ]

角色工作台                              任务当前执行/总耗时（只保留一处）
[需求评审 · 状态] [方案设计 · 状态] [编码执行 · 状态] [质量验证 · 状态]

所选角色 · 该次执行状态                  [Attempt 选择器]
该次执行阶段/必要的历史查看提示

┌ 左列，约 60% ──────────────────┬ 右列，约 40% ─────────────────┐
│ [Prompt][执行追踪][结果与问题][运行记录] │ 当前状态 / 本次执行最终状态      │
│                                │ 来源、更新时间、陈旧提示         │
│ 默认：Prompt                    │ 当前目标                       │
│ [最近注入上下文][静态 Prompt]    │ 阶段 / 结果状态                 │
│ 实际注入版本 / 未注入提示        │ 阻塞（有内容才显示）             │
│ 安全 Markdown 预览              │ 进行中、阻塞、待执行的 TODO       │
│                                │ 已完成 TODO（默认收起）          │
│ ▸ 来源与完整性信息               │ ▸ 预算 / 时间 / 历史错误          │
│ ▸ 上下文与检索证据               │                                │
└────────────────────────────────┴──────────────────────────────┘

▸ 宿主验证（编译 / 静态检查） · 结论 · 耗时 / 失败摘要
```

这里的“状态常显”是指**在角色工作台内，切换左侧四页签不会卸载或隐藏右侧状态**；不要求用 `position: fixed` 覆盖页面，也不新增全页面悬浮状态栏。

### 3.2 任务摘要与角色选择区

- 合并 `RdTaskDetailPage` header 和 `TaskSummaryBand` 中重复的标题、状态、类型、ID、更新时间。
- 任务级 status/paused/actions 只在一个摘要区展示。项目/分支可保留一行次要信息；完整 ID 和时间放既有详情或可复制信息区。
- 四角色仍是现有卡片，保留当前激活边框/底色。默认卡片只显示角色名和角色执行状态；错误计数仅在有错误时显示。
- 卡片中去掉重复 `Attempt 1`、Provider、“无当前阻断”。Attempt 在选中角色头部统一展示。
- 保留工作台单处总耗时。内部 `progressCompleted/progressTotal` 若保留，标为“执行步骤”，放次要位置，不作为任务完成百分比。
- 选中角色状态与任务级状态都保留，但明确作用域：任务整体仍运行时，历史角色可以是已成功；历史 Attempt 失败时，也不能把整个任务显示成失败。
- 无显式 URL 选择时复用 `selectRoleAttempt`；有显式选择时，轮询不得强行切回最新角色或 Attempt。

### 3.3 左侧页签与 URL

| 可见顺序 | 可见名称 | 保留的 URL 值 | 内容 |
| --- | --- | --- | --- |
| 1 | Prompt | `evidence` | 最近真实注入上下文 / 静态 Prompt，附折叠证据入口 |
| 2 | 执行追踪 | `trace` | 现有可读运行事件及详情 |
| 3 | 结果与问题 | `issues` | 现有角色结果、问题、失败恢复 |
| 4 | 运行记录 | `runs` | 执行快照、Provider 尝试、运行信息和既有配置动作 |

- 没有 `tab` 或非法 `tab` 时，默认 `evidence`。
- 显式 `tab=issues/evidence/runs/trace` 均按原含义打开，不做强制重定向。
- 保留 `view/role/attempt` 和其他已有 URL 参数，不在更新工作台状态时重建并丢弃整个查询串。
- 有效上下文/静态 Prompt 是卡内局部选择，不新增 URL 协议；当前已有局部状态可继续复用。
- 同一 Attempt 轮询刷新时，保留页签和用户滚动位置；切换 Attempt 时，清楚切换内容身份，不先闪现旧 Prompt。

### 3.4 Prompt 区的精确语义

- 默认查看方式仍选择 effective context，但显示名称改为“最近注入上下文”。这是**最近一次有记录的实际注入**，不是“最新状态重新拼出来的上下文”。
- 第二种方式为“静态 Prompt”，说明是派发时指令，不含运行中的动态状态块。
- effective 不可用时，显示其真实不可用原因，并提供切换到静态 Prompt 的明确入口；不要悄悄换内容却保留“最近注入上下文”标题。
- 使用现有 `MarkdownRenderer` 渲染 Host 返回的安全预览，不新增 HTML 渲染器或读取 raw artifact。
- 默认展开 Prompt 正文的有界预览；沿用现有约 `max-h-[460px]` 的内容滚动能力，不能默认只显示标题或“点击查看”。
- Prompt 元数据中的 `stageRunId/artifactId/hash/完整长度` 进入“来源与完整性信息”；精确角色/Attempt 已在头部，不重复一排大卡片。
- **必须始终可见的边界提示：** unavailable、stale、truncated、最新状态尚未注入。它们不能与一般 metadata 一起被折叠。
- latest state sequence 12、effective state sequence 10 时，提示“最新状态 #12 尚未注入；此处为实际注入的 #10”。injection sequence 与 state sequence 分别显示，不能互相替代。
- 状态更新但尚未注入时，左侧 Prompt 可以不变、右侧状态更新；这属于正确行为。

### 3.5 右侧当前状态

按 `selectedAttempt.promptStage.latestState` 的实际可用性渲染，不从 overview 的计数拼装详细 TODO。

默认可见顺序：

1. 标题、来源、更新时间、stale 提示。
2. `currentGoal`：完整保留内容可读性，长文本换行，不用省略号遮掉唯一目标。
3. `phase` 与 `resultStatus`，均明确是 Agent/本次执行状态。
4. `blocker`：非空时突出，不显示空的“无阻断”占位卡。
5. 执行待办：进行中和阻塞项优先，之后是待执行项；已完成/取消项默认折叠，可查看全部。
6. 预算、开始时间、历史错误、hash/truncation 详细信息按需展开。

具体规则：

- 复用现有 `AgentLatestStatePanel`、`AgentTodoList` 的类型和字段映射；不要复制一份独立状态解释逻辑。
- TODO 保留 `source/required/acceptanceReferences/evidenceCount/blockerReason` 的详情能力。摘要写“执行待办”，不要写“已审计验收”。
- `budget.available=false` 或字段未提供时写“未提供”；未知预算不能显示 `0%`、剩余 0 或超额。
- 活动状态显示“当前状态”；所选阶段已结束或 state.finalized 时显示“本次执行最终状态”。Source 仍按 Host 的 `source/finalized`，不能单靠本地时钟猜测。
- `stale/staleReason/lastProjectionAtEpochMillis/staleAfterMillis` 全部来自 Host。显示更新时间不等于自行计算新的 stale 判定。
- Agent state 不可用、请求失败、legacy/non-PI、尚未派发应各显示原有准确原因；Prompt 或追踪仍可继续查看。
- 当前 DTO 没有 `decision/handoff/memory` 可直接展示字段；本期不添加这些卡片，不从日志或结果文本猜出字段。
- 如当前 Attempt 的状态仍未到达，显示该 Attempt 的局部 loading/空态；禁止在新 Attempt 标题下复用旧 Attempt 的状态。

### 3.6 运行追踪

- 只在 `tab=trace` 时挂载现有事件订阅组件。右侧状态不受该页签切换影响。
- 保留现有 Process/Tools/Raw 等追踪子视图及安全边界，本期不重写事件聚合器。
- 切换角色/Attempt、离开 trace 或切换顶层 view 时清理旧订阅/轮询；历史和新事件不可串入另一阶段。
- 延续现有历史滚动/新事件跟随行为，不因右侧状态更新而重新挂载整个追踪组件或强制滚到底部。
- 归档默认最新窗口、截断提示、读取更早事件入口均保留。连接中断是追踪状态，不自动判定 Agent 已失败或任务已卡死。

### 3.7 宿主验证与任务审计

- roles 视图中的宿主验证保留标题、最新结论、耗时和真实失败摘要。最新成功轮次的 BUILD/STATIC 命令不再默认铺开。
- 详情展开后仍能找到 Coding Attempt、Run ID、退出码、错误分类、步骤日志与历史轮次。
- 不显示重复的“不是第五个 Agent”等实现说明；四角色与宿主验证的分区已表达区别。
- 任务审计页继续保留完整已审计状态、审计轮次和证据引用；不能用右侧 TODO 取代它。
- 不新增一套前端完成判定。任务完成/重试可用性使用现有后端结果；审计缺失与角色成功分别呈现。

## 4. 视觉与响应式约束

### 4.1 保持原风格的可核验定义

复用当前页面的 `border border-slate-200 bg-white`、`bg-slate-50/50`、既有 teal 激活态、slate/teal/amber/rose 状态语义、现有字号、圆角和图标。

允许：减少重复行、调换页签顺序、调整网格、将内容移到折叠区、压缩空白、沿用原边框和分割线。

不允许：大面积成功背景、新阴影体系、新导航、新主题、新字体、新尺寸按钮、把页面改成大数字看板、按角色分配一套新颜色。

只在任务相关组件添加布局类，不为本需求修改全局 admin 主题。对齐原有 `min-w-0`、`break-words` 和代码区横向滚动规则。

### 4.2 各视口布局

| 视口 | 布局与验证 |
| --- | --- |
| 1440×900 | 主体 `lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]` 或等效既有网格；Prompt 和状态标题/主要内容同时出现 |
| 900×900 | 单列：选中执行摘要 → 当前状态精简区 → 左内容页签/Prompt；状态的已完成待办和预算详情继续折叠 |
| 390×844 | 单列，四角色保持现有两列卡片；选择器换行，目标和 blocker 可读，Prompt/代码区有界滚动 |

- 不通过缩小字号把整块桌面布局塞到手机；长 hash/目标必须换行。
- 窄屏状态区先显示目标/阶段/阻塞和进行中事项，默认最多预览 3 条未完成 TODO，剩余以“查看全部待办（N）”展开，避免把 Prompt 推到页面深处。桌面同样保留有界待办预览，最多 5 条，不能删除隐藏的项。
- 使用单一状态组件实例，靠 grid/flex 排序改变位置；不要 desktop/mobile 各渲染一份造成重复请求或重复 ID。
- 沿用现有 Tabs 的键盘和 ARIA 行为；折叠用现有 Disclosure/Collapsible 或语义化 details，按钮有文字/aria-label。
- 不新增 viewport 固定高度、不遮挡 AdminLayout 顶栏；Prompt/追踪保留自身现有有界滚动，外层页面正常滚动。

## 5. 数据读取、缓存与刷新合同

### 5.1 数据源

| 信息 | 唯一复用来源 |
| --- | --- |
| 任务级 status/paused/actions | `getRdTask` 的 task shell 及既有 action model |
| 角色、Attempt、阶段执行状态 | `execution-overview.stageRuns` 与 `buildRoleWorkbench/selectRoleAttempt` |
| 静态 Prompt | `role-prompts.stagePrompts[].prompt`，精确绑定 `stageRunId` |
| 最近真实注入上下文 | 同一条 `stagePrompts[].effectiveContext` |
| 右侧最新/最终 Agent 状态 | 同一条 `stagePrompts[].latestState` |
| 运行轨迹 | 现有 `getAgentRuntimeEvents/agentRuntimeEventsPath` 或原 legacy fallback |
| QA/RAG 详细证据 | 现有 `loadRoleEvidenceData` 读取的接口，用户展开证据后才请求 |
| 宿主验证 | 现有 `host-verifications` |
| 已审计验收/完成依据 | 现有 `audited-state/audit-runs`，不是 Agent `resultStatus/todos` |

### 5.2 请求触发矩阵

| 页面条件 | task shell | overview | role-prompts | QA/RAG 详情 | runtime events | audit-content |
| --- | --- | --- | --- | --- | --- | --- |
| 初次进入角色工作台 | 首先请求，返回即展示 | shell 之后独立加载 | overview 身份可用后按 signature 加载 | 不请求，直到用户展开证据 | 不请求 | 不请求 |
| roles，Prompt 页签 | 复用既有刷新 | 复用既有刷新 | 按 state/injection signature 刷新 | 仅证据展开时 | 不请求 | 不请求 |
| roles，执行追踪页签 | 同上 | 同上 | **仍需加载/刷新：右侧状态常显** | 不因追踪自动请求 | 复用现有订阅 | 不请求 |
| roles，结果/运行记录页签 | 同上 | 同上 | **仍需加载/刷新：右侧状态常显** | 不自动请求 | 不请求 | 不请求 |
| delivery / audit | 保留原行为 | 不新增常驻刷新 | 不新增状态栏刷新 | 保留该视图原行为 | 清理角色追踪 | 保留原按需读取 |

既有宿主验证和 audited-state/audit-runs 的首次加载此次不另做性能重构；不把本任务扩大成全页 API 改造。

### 5.3 必须保留的刷新细节

- `loadInitial` 仍仅让 shell 成为首屏关键依赖；`onTask` 后立即 `setLoading(false)`，占用 `coreLoadInFlightRef` 的原逻辑不动。
- role-prompts 的加载条件从“roles 且 evidence”扩大为“roles 且 overview 中有可选择的阶段”；不要给右侧状态增加独立 endpoint 或 setInterval。
- 使用现有 `rolePromptSignature`、`rolePromptInFlightRef`、`rolePromptLoadSeqRef`、`loadedRolePromptSignatureRef`，不能因为新布局丢掉请求去重。
- `roleStageSignature` 必须继续覆盖 state sequence/hash 和 injection sequence/injected-state/block/prompt identity；state 不变但发生新注入也要刷新。
- 继续使用 `createTaskRequestGuard` 与 `evaluateRolePromptsFreshness`。旧 task、旧 generation、落后 sequence、相同 sequence 不同 hash 的响应不能提交。
- 上一条的 sequence/hash 校验沿用当前对应 identity 的 `available` 前提。不得重新要求不可用的 latest/effective 提供有效 sequence，或让没有 v2 状态的历史 Attempt 永久重试而看不到静态 Prompt。
- Prompt/effective/latest state 以通过校验的同一响应更新。不要三个组件各请求一次 role-prompts，或先混合新 state 和旧 Prompt 再校验。
- 允许“state 比最近真实注入更靠前”，这不同于响应陈旧。UI 只解释差别，不修改后端 identity。
- 暂时刷新失败时：保留同一 stage 已通过校验的旧内容并明确标记“刷新失败/最后更新时间”；不能继续标为无条件实时，也不能跨 stage 保留。Host stale 提示和传输错误是两种不同状态。
- 初始请求失败没有可用缓存时：局部错误和重试入口；不能阻止任务头部、角色选择、其他可用内容显示。
- 沿用已有有界退避/刷新机制，不新增无限快速重试、双重轮询或自动跨运行时 fallback。

### 5.4 证据展开的控制边界

将原来由 `selectedRoleTab === 'evidence'` 触发的 `loadRoleEvidenceData` 改为由该 Attempt 的“上下文与检索证据”展开状态触发。Prompt 数据读取与 QA/RAG 数据读取必须分离。

推荐回调合同（本次新增 UI props，不是后端接口）：

```ts
type RoleEvidenceDisclosure = {
  stageRunId: string;
  expanded: boolean;
};

interface RoleEvidenceDisclosureProps {
  // TaskRoleWorkbench -> RdTaskDetailPage
  onEvidenceDisclosureChange: (value: RoleEvidenceDisclosure) => void;
}
```

页面记录当前用户展开的 stageRunId；打开另一个角色/Attempt 后，默认不继承上一阶段的展开状态。沿用 `loadRoleEvidenceData(signature)` 的缓存和 guard；不得用这个回调绕开精确 stage 绑定。

打开折叠区时即可显示 role-prompts 中已存在的 context metadata，QA/RAG 请求用局部 loading 补齐。Prompt 已成功时，辅助证据失败不能清空 Prompt 和状态栏。

## 6. 边界状态与文案

| 场景 | 必须呈现的行为 |
| --- | --- |
| task shell 成功，overview 慢 | 立即显示任务头部，角色区域局部 loading；不继续整页白屏 |
| 有阶段但尚未派发 | 角色和 Attempt 可选，Prompt/状态显示真实未生成原因 |
| 无 Prompt 绑定 | 提示当前 Attempt 尚无已绑定的 Prompt 读模型；不使用任务基线或其他 Attempt |
| 静态 Prompt 可用、effective 不可用 | 默认 effective 显示原因，静态 Prompt 入口可操作 |
| latest state 可用、从未注入 | 右侧状态正常，左侧明确尚无可证明的注入上下文 |
| latest #12，实际注入 #10 | 两个版本分别显示，提示未注入；不把 #12 拼入 effective preview |
| state 不变，injection sequence 增长 | 刷新最近注入上下文；不等待阶段状态变化 |
| Host stale=true | 显示来源、最后投影时间与后端原因；不自行更改任务运行状态 |
| finalized archived state | 显示最终/归档状态，不因墙钟变老产生假的 stale |
| 用户查看历史 Attempt | 明确“查看历史”；Prompt、状态、轨迹全绑定历史 stage，轮询不切回 |
| 请求乱序或切 task | 旧响应被丢弃，不闪回旧 Prompt/状态 |
| Prompt/state preview 截断 | 保留 truncation 提示和长度，不把预览标为完整上下文 |
| legacy/non-PI/capability 未启用 | 使用原有原因，状态栏位置保留轻量空态，不合成字段 |
| task 失败，但 Agent blocker 为空 | 仍显示权威任务/阶段错误和结果页入口，不写“无问题” |
| Agent TODO 全 DONE，Host 审计不完整 | 只展示执行待办完成，任务/验收结论不被提升 |
| SSE 断开 | 保留现有降级轮询和连接状态；不据此宣告任务失败 |
| 宿主验证失败 | 默认摘要显示失败点与详情入口，不要求展开长命令后才能发现失败 |

## 7. 文件职责与最小接口变更

### 7.1 预计修改/新增清单

| 文件 | 修改职责 |
| --- | --- |
| `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` | 默认 tab、Prompt/证据加载触发、重复摘要收敛、展开回调 |
| `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx` | 两列布局、页签顺序/标签、右侧状态常显、角色卡压缩 |
| `frontend/src/components/admin/rdtask/RoleEffectiveContextCard.tsx` | 支持 workbench 呈现模式：只留 effective/static，精简重复 provenance 头部 |
| **新增** `frontend/src/components/admin/rdtask/RoleAgentStateCard.tsx` | 承接原状态面板/待办实现和右侧 loading/error/unavailable 外壳 |
| `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts` | 小范围默认 tab 解析；保留现有 stage/Prompt/freshness 逻辑 |
| `frontend/src/components/admin/rdtask/HostVerificationCard.tsx` | 最新轮次默认摘要，详情可展开 |
| 对应现有 `frontend/test/*.test.ts` | 修改既有合同断言，增加默认/选择/加载/状态边界用例 |
| `openspec/changes/rd-task-status-prompt-workbench/*` | 本次新 change，禁止直接修改主 specs |

`rdTaskService.ts`、后端、`RULE.md` 预计不需要变更；如实施中发现确需修改，先写明直接原因及合同影响，不为布局顺手重构。

### 7.2 状态组件拆分方式

沿用现有 props 形状建立新 wrapper：

```ts
export interface RoleAgentStateCardProps {
  stage?: RdTaskStageRun;
  promptStage?: RdTaskRolePromptStage;
  promptLoading: boolean;
  promptError: string;
}
```

新文件同时导出原有 `AgentLatestStatePanel({ state })`，其 `state` 参数类型继续为 `NonNullable<RdTaskRolePromptStage['latestState']>`。将原文件该函数及 `AgentTodoList` 的完整实现移入，再按第 3.5 节调整分组；不要重新解释字段。`deriveInjectionBadge/deriveSourceLabel/isEffectiveContextStale/shortHash/sortAgentTodos` 等现有 helpers 仍从 `roleWorkbenchModel` 复用。

`RoleEffectiveContextCardProps` 新增一个有默认值的 UI 模式：

```ts
mode?: 'standalone' | 'workbench'; // 默认 standalone，兼容已有使用处。
```

- `standalone`：保留既有完整查看能力，必要时引用迁移后的 `AgentLatestStatePanel`。
- `workbench`：不渲染内部 latestState 页签；外层已显示角色/Attempt，不再重复一整排身份卡；保留注入、来源、stale/unavailable/truncated 提示。
- 新右侧 wrapper 从同一个 `promptStage` 读取状态。不要改变 service DTO，不从 `stage.agentState*` 合成完整 state。
- 按当前 import/使用情况迁移辅助函数，避免 `RoleEffectiveContextCard ↔ RoleAgentStateCard` 运行时循环依赖。共用类型直接从 `rdTaskService` 引入。

### 7.3 所选 Attempt 的真实模型与可直接使用的接线

`RoleAttemptView` 当前字段为：`stageRunId/attemptNo/status/stage?/promptStage?/qaEvidenceIds`。工作台已经派生 `selectedStage = selectedAttempt?.stage` 和 `selectedPrompt = selectedAttempt?.promptStage`，不要在父页面另写一套猜测逻辑。

右侧接线使用现有变量，完整调用为：

```tsx
<RoleAgentStateCard
  stage={selectedStage}
  promptStage={selectedPrompt}
  promptLoading={promptLoading}
  promptError={promptError}
/>
```

现有 `RoleEvidencePanel` 接收 `stage/promptStage/promptLoading/promptError/evidenceLoading/qaEvidence/qaEvidenceError/retrievalRuns/retrievalError/onInspectRetrievalRun`。本次给它增加展开回调，并让它内部调用 `RoleEffectiveContextCard mode="workbench"`；其他 props 继续传当前 `selectedQaEvidence/selectedRetrievalRuns`，不去掉精确阶段过滤。

`RoleWorkbenchTab` 已在工作台使用，保持原联合值。第 8 节新增解析函数直接返回该四值，不要为 UI label 建第二套 URL enum。

## 8. 实施任务与顺序

每项完成后记录实际命令/结果；存在其他人的未提交内容时只提交自己的范围，不把整仓 `git add .` 当默认操作。

### Task 1：建立 change、冻结展示合同和验证基线

**Files**

- Create: `openspec/changes/rd-task-status-prompt-workbench/proposal.md`
- Create: `openspec/changes/rd-task-status-prompt-workbench/design.md`
- Create: `openspec/changes/rd-task-status-prompt-workbench/specs/requirement/task-workbench-presentation/spec.md`
- Create: `openspec/changes/rd-task-status-prompt-workbench/tasks.md`
- Read: 本文第 1 节材料、相关当前 diff。

**Inputs / Outputs**：输入为用户约束、本计划和当前实现；输出为独立 delta、实现任务与基线结果，不修改运行语义。

- [ ] 用当前 OpenSpec CLI/仓库技能创建独立 change。若同名已存在，先核对 owner/范围，不覆盖。
- [ ] proposal 写清：角色工作台继续作为默认入口；Prompt/state 是核心；原风格保持；只做前端呈现。
- [ ] delta 至少包含下列独立 Requirement 和 Scenario：

```markdown
## ADDED Requirements

### Requirement: 角色工作台默认展示 Prompt 并持续展示所选 Attempt 的状态
系统 SHALL 在未指定有效 tab 时显示 Prompt，并在角色工作台的所有内容页签中保留同一所选 Attempt 的状态区。

#### Scenario: 无 tab 进入角色工作台
- WHEN 操作者打开需求任务详情且 URL 未指定 tab
- THEN 系统选择 Prompt 页签，并展示与所选 stageRunId 绑定的 Prompt 和状态，或各自准确的不可用原因

#### Scenario: 切换到执行追踪
- WHEN 操作者从 Prompt 切换到执行追踪
- THEN 追踪绑定同一 stageRunId，状态区继续可见，不新增第二个状态轮询器

### Requirement: Prompt 与 Agent 状态必须保留真实来源和不同注入版本
系统 SHALL 分别展示真实静态 Prompt、最近真实注入上下文和最新 Agent 状态，不得在浏览器重新拼装 effective context。

#### Scenario: 最新状态领先于实际注入
- WHEN latest state 为 sequence 12，effective context 实际引用 sequence 10
- THEN 两个版本分别显示，并提示最新状态尚未注入

### Requirement: 精简展示保留原风格与完整排障可达性
系统 SHALL 沿用现有管理端风格，保留角色/Attempt/URL 选择、结果、运行记录、证据及宿主验证详情入口。

#### Scenario: 打开历史执行链接
- WHEN URL 显式指定 role、attempt 和合法 tab
- THEN 系统打开对应历史执行，刷新不改变该选择，Prompt、状态、事件不串入其他 Attempt

### Requirement: 默认 Prompt 展示不得扩大首屏阻塞或证据预取
系统 SHALL 在 task shell 返回后展示首屏，复用 overview 和 role-prompts 的去重及 freshness 校验；辅助 QA/RAG 详情按用户展开加载。

#### Scenario: Prompt 可用但证据未展开
- WHEN 操作者只查看默认 Prompt 和状态
- THEN 不额外请求该折叠区的 QA/RAG 详细数据，Prompt 加载不依赖辅助证据成功
```

- [ ] 把第 3–6 节规则写入 design；tasks 不以“已读旧文档”替代实现。
- [ ] 运行第 10.1 节聚焦基线。失败时记录已有失败和是否相关，不批量修复范围外问题。
- [ ] 验证 change 格式：`OPENSPEC_NO_UPDATE_CHECK=1 openspec validate rd-task-status-prompt-workbench --strict`。

**完成标准：** 实施范围、失败基线和样式保留要求可独立评审；未直接改主 spec。

### Task 2：默认 Prompt 页签与精确选择回归

**Files**

- Modify: `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify/Test: `frontend/test/roleWorkbenchModel.test.ts`
- Modify/Test: `frontend/test/taskDetailInformationArchitecture.test.ts`

**Interfaces**：复用 `selectRoleAttempt(roles, requestedRole, requestedAttempt?)`；新增一个小的纯解析函数，页面取代内联 fallback，不改 role/attempt 默认规则。

- [ ] 先加入真实行为单测：

```ts
import { resolveRoleWorkbenchTab } from '../src/pages/admin/rdtask/roleWorkbenchModel.ts';

test('defaults to Prompt but preserves explicit legacy tab links', () => {
  assert.equal(resolveRoleWorkbenchTab(null), 'evidence');
  assert.equal(resolveRoleWorkbenchTab(''), 'evidence');
  assert.equal(resolveRoleWorkbenchTab('invalid'), 'evidence');
  for (const tab of ['issues', 'evidence', 'runs', 'trace'] as const) {
    assert.equal(resolveRoleWorkbenchTab(tab), tab);
  }
});
```

沿用该测试文件现有 `test/assert` import，不重复声明。先跑该文件，确认新增行为在实现前失败。

- [ ] 最小解析实现：

```ts
export function resolveRoleWorkbenchTab(
  value: string | null,
): 'issues' | 'evidence' | 'runs' | 'trace' {
  return value === 'issues' || value === 'evidence' ||
    value === 'runs' || value === 'trace' ? value : 'evidence';
}
```

- [ ] 页面使用 `resolveRoleWorkbenchTab(searchParams.get('tab'))` 替换当前 inline fallback。保持 `updateWorkspaceQuery` 的 replace 和参数保留行为。
- [ ] 保留并重跑 failed/active/latest/URL 指定 Attempt 的既有 fixture 用例；只更新无 tab 默认值的断言。
- [ ] 浏览器或组件验证显式 `tab=issues` 仍打开原结果面，不被默认 Prompt 覆盖。

**完成标准：** 新默认生效，旧深链接保持，角色选择逻辑无变化。

### Task 3：提取状态组件并精简 Prompt 卡

**Files**

- Create: `frontend/src/components/admin/rdtask/RoleAgentStateCard.tsx`
- Modify: `frontend/src/components/admin/rdtask/RoleEffectiveContextCard.tsx`
- Test: `frontend/test/rdTaskRolePromptPresentation.test.ts`
- Test: `frontend/test/roleWorkbenchModel.test.ts`

**Interfaces**：新增 `RoleAgentStateCardProps` 和 `mode` 如第 7.2 节；输入仍为现有 `RdTaskStageRun/RdTaskRolePromptStage`。

- [ ] 先为已有 fixture 增加/保留以下断言：同角色不同 stageRunId 不串 Prompt/state；latest 12 / effective 10 可同时存在；Prompt 未绑定时没有 baseline fallback；non-PI/legacy、stale、truncated 原因可见。
- [ ] 将 `AgentLatestStatePanel`、`AgentTodoList` 及所需局部 helpers 从旧文件完整迁移到新文件。使用 `export` 供 wrapper 和 standalone 模式复用；原 helper 如仍被旧文件使用，移动到已有合适位置或保持单一共享导出，不复制两份。
- [ ] 新 wrapper 明确处理四类状态：初始 loading、无缓存请求失败、state 不可用、state 可用；available state 刷新失败时保留同 stage 内容并显示失败提示。原有 unavailable/freshness 文案不要丢失。
- [ ] 按第 3.5 节重排状态内容，进行中/阻塞待办前置；已完成/取消项默认折叠；缺省预算显示未提供。
- [ ] 为 `RoleEffectiveContextCard` 添加 `mode`，workbench 模式内部只保留 effective/static；effective 默认值不变；两种内容标题准确。
- [ ] 把重复的 runtime/Attempt/长 ID/hash 移入 provenance 详情，保留注入差异和 stale/truncated/unavailable 的可见提示。
- [ ] 不新增 `task.promptSnapshot`、raw artifact、拼接 effective context、推断缺失状态的 fallback。
- [ ] 跑 Prompt/model 聚焦测试和 typecheck，核对所有现有调用处仍可兼容。

**完成标准：** 有且只有一套结构化状态实现；旧边界提示全部可达；当前工作树已有 Prompt 修复保留。

### Task 4：组合双列工作台并压缩重复展示

**Files**

- Modify: `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Test: `frontend/test/roleWorkbenchPresentation.test.ts`
- Test: `frontend/test/taskDetailInformationArchitecture.test.ts`

**Interfaces**：左列使用现有 selected attempt 与四内容组件；右列接受与左列相同的 `stage/promptStage/promptLoading/promptError`。不得另做角色选择。

- [ ] 为“切换 Prompt/trace/issues/runs 后仍有同一状态面”增加组件或浏览器用例；既有源码合同测试仅随真实组件边界更新，不新增大量 class 字符串快照。
- [ ] 保留原顶层 Tabs/角色 cards/Attempt selector，将其后内容组织为左右列。建议 Tailwind 布局：

```ts
const workspaceColumnsClass =
  'grid min-w-0 grid-cols-1 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]';
const workspaceContentClass = 'order-2 min-w-0 lg:order-1';
const workspaceStateClass =
  'order-1 min-w-0 border-b border-slate-200 lg:order-2 lg:border-b-0 lg:border-l';
```

可直接把上述类写在现有容器上，不必为这些字符串增加公共常量模块。左容器放原四内容 Tabs，右 aside 使用第 7.3 节完整状态调用。沿用当前 spacing/classes，不丢弃任何原内容组件。

- [ ] `RoleAgentStateCard` 放在各 `TabsContent` 外面；只按 selected stage 身份切换，不按 `selectedTab` 设置 key。
- [ ] Prompt 分支使用 `RoleEffectiveContextCard mode="workbench"`，其下放折叠的上下文/检索证据。
- [ ] 四角色卡只保留角色名、状态和必要异常提示；Attempt 由唯一 selector 承担，不移除多次尝试。
- [ ] 合并页面 header/summary/workbench 的重复任务元数据。保留任务级状态、暂停/恢复/重试/审批等原有功能入口与可用性控制，不重写 action model。
- [ ] 当选择历史 Attempt 时，显示历史提示；右状态改为该次执行最终状态；任务头部仍代表当前任务。
- [ ] 宽屏 60/40；900/390 单列状态优先，列表默认有界。状态组件只挂载一次。
- [ ] 跑工作台/信息架构测试和 typecheck；在后续 Task 7 用真实浏览器验证内容可见性。

**完成标准：** 默认无需点“输入与证据”即可看到 Prompt；切 trace 不隐藏状态；原风格和既有动作保留。

### Task 5：把常显状态接入现有加载与刷新

**Files**

- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx`
- Test: `frontend/test/taskDetailInformationArchitecture.test.ts`
- Test: `frontend/test/rdTaskRolePromptPresentation.test.ts`
- Test: `frontend/test/roleWorkbenchModel.test.ts`

**Interfaces**：`loadRolePrompts(signature: string)` 与 `loadRoleEvidenceData(signature)` 不改后端协议；新增 `onEvidenceDisclosureChange` 如第 5.4 节。

- [ ] 先验证目标加载场景：roles 任意合法 tab、overview 有 stage 时都可加载状态；default Prompt 未展开证据不请求辅助 QA/RAG；离开 roles 不新增状态轮询。
- [ ] 仅将 role-prompts effect 中的 `selectedRoleTab === 'evidence'` 限制去掉，保留 task、roles、overview/stage、signature、in-flight、loaded signature 条件。不要把它移进 `loadInitial`。
- [ ] 证据区展开通过带 `stageRunId` 的回调通知页面。页面用该身份记录展开，加载已存在的 `loadRoleEvidenceData`；换角色/Attempt 后不沿用旧展开状态。
- [ ] 保留所有 `loadRolePrompts` freshness 分支，不删 `STALE_DISCARD/CONSISTENCY_ERROR`，不降低 hash 校验以修复 fixture。
- [ ] 用 deferred response 或已有 freshness 纯函数 fixture 覆盖第 9 节的竞态用例。没有组件测试框架时复用当前纯函数/loader 测试并补浏览器网络可见验证，不为本任务新增框架。
- [ ] 确保刷新同一 stage 不卸载 Prompt/trace；任务/阶段切换时旧数据不可短暂挂到新身份。继续复用精确 stage 绑定。
- [ ] 确认 trace `EventSource`、1.5s fallback polling、legacy 轮询和 overview 刷新没有因重组新增第二个实例。
- [ ] 跑加载、Prompt、model、trace 相关聚焦测试。若必须改变 freshness 算法或后端更新协议，超出本计划范围，应先按 AGENTS.md 做风险升级评估。

**完成标准：** 状态常显且持续刷新，默认 Prompt 不导致辅助证据全量预取，不引入重复订阅或乱序覆盖。

### Task 6：宿主验证降噪并保留审计/恢复通路

**Files**

- Modify: `frontend/src/components/admin/rdtask/HostVerificationCard.tsx`
- Modify if necessary: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Test: `frontend/test/hostVerificationPresentation.test.ts`
- Regression: `frontend/test/auditedTaskStatePresentation.test.ts`
- Regression: `frontend/test/taskFailureRecoveryWorkbench.test.ts`

**Interfaces**：Host verification 数据、日志链接和历史轮次接口不变；只增加/使用既有 disclosure 状态。

- [ ] 先定义 UI 验证：成功时默认只见摘要，失败摘要无需展开即可发现，展开后日志/命令/历史轮次可达。
- [ ] 将最新轮次的完整 `VerificationRunSection` 默认收起；在收起行保留最新 status、耗时、必要失败说明。
- [ ] 保留 BUILD/STATIC 各自 SUCCEEDED/FAILED/SKIPPED 差别，不把 SKIPPED 统一写成“测试通过”。
- [ ] 保留 Coding Attempt、Run ID、exit code 和 artifact 日志入口在展开内容中；历史入口继续存在。
- [ ] 任务审计视图保留完整审计卡。不要把 `auditedCoverage` 的全部 records 计数或右侧 TODO 计数改成任务完成判定。
- [ ] 验证失败恢复仍绑定正确失败阶段、材料与 checkpoint；查看历史失败不自动触发新的恢复操作。
- [ ] 跑宿主验证、审计与失败恢复聚焦测试。

**完成标准：** 大块命令从默认展示移除，但失败可发现、证据可达、后端行为未变。

### Task 7：完整验证、证据记录与交接

**Files**

- Update: 本次 change 的 design/tasks 中的实测结果。
- Create: `docs/superpowers/plans/2026-09-05-rd-task-status-and-prompt-workbench-acceptance.md`，实施完成时才创建。

- [ ] 运行第 10 节的聚焦/全量前端命令，记录退出状态，不将本计划中的预期写成实测结果。
- [ ] 使用真实后端任务完成 390/900/1440 的浏览器验证；fixture 可覆盖边界，但不能替代真实链路。
- [ ] 对默认 Prompt、切 trace 状态仍在、历史 Attempt、最新/已注入版本差异、宿主验证展开分别留证。
- [ ] HTTP 验证记录真实接口返回、stageRunId 对齐和动作前后不变量。动作验证只在用户已授权的测试任务/环境进行。
- [ ] 在 acceptance 文档中列出验证任务、视口、接口、证据路径、通过/失败和未覆盖项；不要写“全面通过”掩盖不可达环境。
- [ ] 确认 git diff 没有误覆盖协作者修改、无后端/桥接/镜像/状态机改动，无新增依赖。
- [ ] 仅当实现和测试符合 delta 后，按仓库 OpenSpec 流程归档/同步并执行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`。不要归档其他在途 change。

**完成标准：** 不是只有截图漂亮，而是 Prompt/state/trace 三者同 Attempt、按新顺序可用，已有安全和加载边界仍有证据。

## 9. 必测用例矩阵

| ID | 用例 | 核心断言 |
| --- | --- | --- |
| A01 | 无 tab 或非法 tab | 默认 evidence/Prompt；右侧状态区存在 |
| A02 | 显式四种旧 tab | 对应内容保持；四种情况下状态均属于同一 selected stage |
| A03 | 无 URL role，存在失败/活跃角色 | 复用既有 selectRoleAttempt 优先级 |
| A04 | 手工选历史 Attempt 后轮询 | 选择不跳回；Prompt/state/events 均是历史 stage |
| A05 | 同角色 Attempt 1/2 | 不能仅凭 role 关联状态；切换时无旧内容闪现 |
| D01 | shell 快、overview 慢 | shell 返回即解除整页 loading |
| D02 | roles 任意 tab | role-prompts 可刷新；不新增独立 state timer |
| D03 | Prompt 默认，证据折叠 | QA/RAG 详细请求为零；展开后发出并显示局部 loading |
| D04 | Prompt 成功，辅助证据失败 | Prompt/state 保留，证据错误局部呈现 |
| D05 | 请求发出后切 task | 原 task response 被 guard 丢弃 |
| D06 | available state：overview expected 12，response 10 | 不提交旧 response，不推进 loaded signature |
| D07 | available state：相同 sequence，不同 hash | 一致性错误可见，不覆盖已校验数据 |
| D08 | state sequence 不变，injection 增长 | signature 变化，刷新 effective context |
| D09 | response 领先 overview | 保留既有 guarded 对账行为，不形成无限刷新 |
| D10 | latest/effective available=false，静态 Prompt 已绑定 | 不把缺少动态 identity 判成 sequence 落后；可展示静态 Prompt及真实 unavailable 原因 |
| S01 | latest 12、injected 10 | 显示两个版本，未注入提示可见 |
| S02 | 静态可用、effective 不可用 | 显示原因，静态可打开，不伪造 effective |
| S03 | latest 可用、无任何 injection | 右状态可读，左侧不能声称已注入 |
| S04 | legacy/non-PI/unavailable | 有准确原因，无任务基线/跨 Attempt fallback |
| S05 | stale live projection | 后端原因/时间可见，前端不新增判定 |
| S06 | finalized archived 很旧 | 显示最终/归档状态，不误报 live stale |
| S07 | unknown budget、长目标、很多 TODO | 未提供语义准确、目标可读、待办有界且可展开全部 |
| S08 | TODO 全 DONE 但未审计 | 不显示任务验收已通过或整体已完成 |
| T01 | trace 进入/离开/切阶段 | 单一有效订阅；旧订阅清理、事件不串 stage |
| T02 | 首次打开归档 trace | latest=true 最新窗口；不从零扫全任务事件 |
| T03 | SSE 错误 | 复用原降级策略，无第二套并行 timer |
| T04 | 追踪截断/历史范围 | 提示和历史入口仍可见 |
| V01 | 宿主成功/失败/SKIPPED | 默认摘要准确；命令和日志可展开；不是第五角色 |
| V02 | 390/900/1440 | 原样式；无横向溢出、遮挡和重复状态 DOM |
| V03 | 键盘操作 | Tabs、Attempt、折叠可达，focus 可见，无仅 hover 内容 |

## 10. 验证命令与执行口径

### 10.1 聚焦前端验证

在仓库根目录执行：

```bash
cd frontend
node --experimental-strip-types --test \
  test/roleWorkbenchModel.test.ts \
  test/roleWorkbenchPresentation.test.ts \
  test/rdTaskRolePromptPresentation.test.ts \
  test/taskDetailInformationArchitecture.test.ts \
  test/hostVerificationPresentation.test.ts \
  test/auditedTaskStatePresentation.test.ts \
  test/qaEvidencePresentation.test.ts \
  test/taskFailureRecoveryWorkbench.test.ts \
  test/retrievalRunVisibility.test.ts \
  test/viteProxy.test.ts
npm run typecheck
```

### 10.2 交付前完整前端验证

```bash
cd frontend
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build
```

执行时从正确工作目录开始，不在已进入 frontend 的 shell 再执行一次 `cd frontend`。若构建更新 tracked static assets，按仓库既有生成方式处理，只接受本次构建产生且可解释的变更，不手改压缩后的 JS。

### 10.3 后端只读合同核对

本计划不要求修改后端。用实际浏览器 Network 和 HTTP 检查既有任务的：

- `/admin/rd-tasks/{taskId}`：shell 不携带 task baseline Prompt/执行大 JSON。
- `/admin/rd-tasks/{taskId}/execution-overview`：取得当前 stages 与 state/injection identity。
- `/admin/rd-tasks/{taskId}/role-prompts`：静态 Prompt、effective、latest state 对齐同一 stageRunId；安全/截断原因完整。
- 现有运行事件端点：以当前 `agentRuntimeEventsPath` 为准生成 URL，不猜测或新增路径。
- host verification 日志、QA evidence：展开后可读取，绑定原任务/阶段。

如果实施者实际修改了 Java、公共 DTO、代理路径或协议，必须追加对应 Bootstrap/controller/exec tests、Vite 代理合同及真实 HTTP 回归；这已经超出“只改布局”的预计范围。若修改 Pi/QA 资源，还须遵守 AGENTS.md 的镜像和三处协议同步要求，不能用本计划跳过。

### 10.4 真实浏览器验收记录

验收文档至少包含：

- 每个视口的初始状态与 Prompt 同屏截图；900/390 的堆叠顺序截图。
- 切到 trace 后状态仍可见、切 Attempt 后数据身份变化的记录。
- stale / 未注入 / 缺失数据至少通过 fixtures 验证，并标明哪些取得真实响应。
- Network 证明默认 Prompt 没有附带辅助 QA/RAG 全量读取，反复切 tab 没有重复常驻订阅。
- 现有暂停/恢复/审批/失败恢复入口仍存在；实际写操作仅在授权测试环境验证，结果与 task/事件回读对应。
- 未覆盖项必须列明，不能用静态源码断言替代视觉或实时链路证明。

## 11. 非目标、回退与风险控制

- 不优化后端查询、不升级状态协议、不添加新的状态字段或模型总结、不修改 Agent Prompt。
- 不重写现有 Runtime/Readable trace，不解析 raw Pi session 或隐藏 reasoning。
- 不更改 audit completion、failure recovery、retry checkpoint、审批或暂停/恢复实际语义。
- 不删任务输入与交付、审计、证据引用、历史 Attempt、历史轮次入口。
- 不为本次纯前端改动重建 Pi 镜像或触发生产部署。
- 若某个真实任务缺少必要后端字段，呈现明确空态并记录，不能为让截图好看而合成数据。
- 若新布局出现问题，回退本次前端布局/默认 tab/加载条件变更；保留已存在的 Prompt freshness 与其他 Agent 的修复，不执行整个工作树 reset。
- 最主要风险是“把两列做出来但混入不同 Attempt 的数据”，其次是“默认 Prompt 导致额外大查询”和“状态栏把 Agent 声明包装成审计结论”。对应 A04/A05、D02/D03、S08 必须有验收证据。

## 12. 可直接交给实施 Agent 的指令

> 请按 `docs/superpowers/plans/2026-09-05-rd-task-status-and-prompt-workbench-plan.md` 实施任务详情页精简。核心是当前状态与 Prompt/执行追踪，保持当前 RD-Bot 视觉风格。保留三个顶层视图与四角色、现有 URL 和恢复/证据语义；桌面左侧内容页签默认 Prompt，右侧所选 Attempt 的状态始终可见。先读 RULE、相关 specs、当前 diff，创建独立 `rd-task-status-prompt-workbench` OpenSpec change，再按 Task 1–7 实施和验证。当前工作树已有 RoleEffectiveContextCard、roleWorkbenchModel 和相关测试修改，必须在其上小范围叠加，不整文件覆盖。禁止任务级 Prompt fallback、跨 Attempt 绑定、浏览器拼装 effective context、重复状态轮询或默认预取全部辅助证据。完成后提交实际测试与浏览器证据；未经另行授权不要部署生产环境。

## 13. 本轮实际执行记录

- 已阅读：第 1 节涉及的 RULE、任务管理资料、主 specs、历史来源审计、Prompt/state delta 与历史前端计划。
- 已执行：`git status --short`；对文档使用 `cat/sed/rg`；由 Luna 只读追踪当前组件、类型、模型、加载器和测试。
- 已产出：本详细交接计划与当前状态/Prompt 优先的交互布局示意。
- 已完成文档/示意静态检查：当前文件引用路径存在、Markdown 代码围栏成对、7 项任务完整；示意脚本通过 `node --check`，DOM ID 无重复，静态元素引用无缺失。这些检查不等同于业务页面测试。
- 未执行：前后端测试、真实后端 HTTP 回归、业务页面浏览器 QA、构建、OpenSpec 创建/归档、提交、部署。
- 交互示意只使用示例数据；不作为任何实际任务、Prompt、Agent 状态或验收完成的证据。
