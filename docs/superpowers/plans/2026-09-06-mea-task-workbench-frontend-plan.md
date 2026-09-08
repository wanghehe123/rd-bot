# 四角色产物与证据工作台：Coding 内 MEA 展示前端实施计划

> **For agentic workers:** 使用 `superpowers:executing-plans` 按任务执行；用户要求代码探索使用 Luna。本文件是待实施计划，步骤以 `- [ ]` 跟踪。它不表示业务代码、部署或验收已经完成。

**Goal:** 保持现有 RD-Bot 风格与四角色流程，让研发人员首先看到任务当前状态、各角色产物及其证据，并在 Coding 内追踪 MEA 的决策、执行、审计和返工。

**Architecture:** 外层沿用 `RdTaskDetailPage → TaskRoleWorkbench` 与四角色导航；Coding 内增加局部 MEA 协作区，引用真实 command、Coding/QA Attempt、Host 验证与 AuditRun。主体默认“产物与证据”，右侧所选 Attempt 状态持续可见；Prompt、执行追踪和运行记录保留直接入口。新的 Coding MEA 只读接口由后端计划交付。

**Tech Stack:** 现有 React、TypeScript、Vite、Tailwind、shadcn/Radix 组件、`MarkdownRenderer`、Node `node:test`；不增加主题、字体、图标库、图编辑器或状态管理框架。

**Spec:** 本文第 2–6 节是用户本轮确认后的目标合同；既有约束见 `RULE.md`、`openspec/specs/requirement/delivery-platform/spec.md`、`openspec/specs/requirement/audit-only-writeback/spec.md`、`openspec/specs/requirement/manager-decision-command/spec.md`。接口合同与后端顺序见同目录 `2026-09-06-mea-backend-next-phases-plan.md`。

## Global Constraints

- 用户确认：**四角色是流程中的四角色；MEA 是 Coding 执行过程中的定义。** 不把完整四角色流程重新包装为一个顶层 Executor，不把 MEA 变成与四角色并列的第二套导航。
- 用户确认：默认打开“产物与证据”。这覆盖昨日方案“默认 Prompt”的决定；保留昨日方案的状态常显、精确 Attempt 绑定和延迟加载。
- 四角色仍为需求评审、方案设计、编码执行、质量验证，顺序不变。Manager 是 Host 策略，Auditor 是 Host 审计职责；`HOST_VERIFY` 是独立宿主验证命令。
- QA 仍是外层第四角色；其结果参与 Coding 的 MEA 审计闭环时，引用同一个 `stageRunId`，不创建第二个“内部 QA”角色或复制 Attempt。
- `view=roles|delivery|audit` 和 `tab=issues|evidence|trace|runs` 保留。`issues` 的用户文案改为“产物与证据”；`evidence` 改为“Prompt”。
- 任务状态、命令状态、Agent 执行状态、Agent 自报待办、Host 已审计验收状态分别展示，不互相代替。
- Prompt、latest state、effective context、运行轨迹都绑定所选 `stageRunId`；禁止回退到任务基线 Prompt，禁止借用其他 Attempt 的状态。
- 本计划仅修改前端与本 change 文档；不更改调度顺序、完成门、QA 合同、schema、Pi 镜像或真实任务数据。
- `RULE.md` §5.4 的 shell 先出、overview 按视图加载、audit-content 按需、归档事件末页读取和请求去重必须保留。
- 页面无横向溢出；390px、900px、桌面均须真实浏览器验证。隐藏抽屉满足 `aria-hidden`、`inert` 和焦点归还要求。
- 只有请求/断言/截图实际执行后，才填写验收通过；本计划中的示例和测试夹具不是真机验收。

## 1. 开发分支、基线与所有权

### 1.1 分支安排

| 项目 | 明确安排 |
| --- | --- |
| 前端开发分支 | `codex/mea-task-workbench` |
| 基线 | 从实施时已核对的 `main` 创建，必须包含 `3fcd7db65cb7a3aabace2d04ffa5d30fdf69e714`；记录实际 base SHA |
| OpenSpec change | `mea-task-workbench`，新增 delta，实施期间不直接改主 spec |
| 后端依赖分支 | `codex/mea-coding-read-model`；只依赖其读取合同，不等待 Phase 4–7 |
| 并行开发方式 | 后端合同冻结后，前端可用标注为 fixture 的数据开发；最终合并验收必须连接真实后端 |
| 合并顺序 | 后端读取接口可用 → 前端真实 HTTP/浏览器验收 → 独立提交/集成；提交、push、archive 按实施会话实际授权执行 |
| 本轮状态 | 只创建计划文件，没有创建以上开发分支 |

规划时 `main=3fcd7db6`，共享工作区存在另一任务的 P3 归档、主 spec 和交接文档改动。它们不是本计划的交付物。实施者用独立 worktree 开工，不能 stash、清空、恢复或顺手提交这些文件。若归档尚未合入基线，可只读当前归档资料；不要把归档 patch 混入前端分支。

### 1.2 读物与来源等级

| 来源 | 本计划如何使用 |
| --- | --- |
| `RULE.md`，§3.5.3、§5.4、§6 | 当前强制约束 |
| `docs/rd-task-management-requirements.md`、`docs/rd-task-management-design.md` | 任务页背景；历史 BugFix/评测内容不恢复 |
| `docs/openspec/historical-spec-provenance-audit.md` | 使用其中证据分级方法，不把旧索引当当前能力清单 |
| 三份 requirement 主 spec | 现有执行角色、已审计真值及 Manager 合同 |
| `docs/superpowers/plans/2026-09-05-rd-task-status-and-prompt-workbench-plan.md` | 尚未实施的上一版计划；默认页签和 MEA 层级以本文覆盖，其他相关约束吸收进本文 |
| `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md` | 历史真机结果与已实现声明；部署状态需重核 |
| `docs/superpowers/qa/2026-09-06-mea-p3-w1e-triangle-evidence.json` | 只读回放夹具来源，证明 W1e 局部闭环，不证明最终任务完成 |
| 本轮 Luna 的源码/测试源码追踪 | 确认当前入口和字段；本轮未跑业务测试，不标成新的真机证明 |

## 2. 信息架构与默认行为

### 2.1 页面结构

```text
任务标题 + 任务状态 + 当前执行位置 + 是否暂停/等待输入 + 必要操作
角色工作台 | 任务输入与交付 | 任务审计                 （保留）

需求评审       方案设计       编码执行        质量验证   （四角色）
执行状态       执行状态       执行状态        执行状态
产物一句摘要   产物一句摘要   变更/修复摘要   验收覆盖摘要

所选角色 + Attempt 选择器 + 历史/当前标签
┌────────────────────────────────┬──────────────────┐
│ 产物与证据 | Prompt | 执行追踪 | 运行记录 │ 所选 Attempt 状态 │
│                                │ 当前目标/动作     │
│ 默认：角色产物摘要 + 关键证据    │ Agent 待办       │
│ Coding 时：局部 MEA 当前轮摘要   │ 最近上报时间      │
│           Manager / 执行 / 审计 │ 注入/状态来源     │
│ 按需：历史轮次、原始结果、日志   │ 阻断/不可用原因   │
└────────────────────────────────┴──────────────────┘
```

这是一张交付任务页面，不新增全局 MEA 大屏。三栏 MEA 区标题固定为“Coding 内 MEA 协作”，只出现在 Coding 工作区。其他角色通过四角色导航进入 Coding 查看该协作，不占用同样的大块区域。

### 2.2 默认规则

1. 进入 `view=roles` 且 URL 没有合法 tab 时，默认 `tab=issues`，页面文案“产物与证据”。
2. 角色/Attempt 的 URL 显式选择优先；无显式选择时沿用现有失败角色、活跃角色、最近更新角色的选择规则，不强制跳 Coding。
3. 轮询不能抢走用户选择的历史 Attempt。当前任务已运行到 QA，但用户查看 Coding Attempt 1 时，顶部显示真实当前任务位置，侧栏标题显示“Coding Attempt 1 的最后状态”。
4. 切换左侧页签，右侧状态栏保持；切换角色/Attempt，右侧立刻切换 identity 并清除旧响应，不显示上一 Attempt 的内容过渡。
5. Coding 默认只展示与所选 Attempt 关联的 MEA 轮次；无显式轮次时选择该 Attempt 的实际关联轮次，不能选择任务最新但无关的轮次。
6. 普通 Coding 可能早于第一条 Manager 决策。此时显示“首次执行尚无前置 Manager 决策”，不得补画一个初始 Manager 节点。
7. 已完成、失败和运行中采用同一布局；完成任务减少无用动画，保留产物、证据及历史状态。

### 2.3 页面密度

- 顶部只保留一套任务状态/进展摘要；耗时、成本沿用现有次级文字，不重复渲染大卡。
- 四角色卡每张只展示角色名、执行状态、一句产物摘要和一个数量/关键缺口；Provider、结果 ID、全部时间戳进入详情。
- 产物摘要默认最多三项，证据默认最多五项，均有“查看全部”；数量来自完整列表时才显示总数，截断数据标“已加载”。
- 原始 JSON、完整命令、stderr、审计 hash 和历史轮次默认折叠；关键失败原因与当前阻断记录不折叠。
- 成功的 Host 验证压成一行“构建通过 / 静态检查通过 / 耗时 / 查看证据”；详情仍可展开所有步骤和日志。
- 长 Prompt 和 JSON 在自身内容区域阅读，禁止撑开整个页面横向宽度。

## 3. 四角色产物与证据展示合同

### 3.1 角色内容

| 角色 | 默认产物 | 默认证据与关联 | 降级规则 |
| --- | --- | --- | --- |
| 需求评审 | 可行性、缺失材料、验收覆盖、预算估算中实际存在的字段 | 本 Attempt 结果产物、输入材料/上下文引用；可一键看 Prompt | 未结构化则显示“尚无可展示的结构化产物”，保留原始结果入口 |
| 方案设计 | 影响文件、实施步骤、测试计划、验收映射 | 本 Attempt 的方案结果、所引用需求/验收 ID | 不从长文本推断不存在的字段或 AC 覆盖 |
| 编码执行 | 修改文件、实现摘要、Agent 自报测试、交付产物 | 绑定该 Coding 的 Host Verify、对应 QA Attempt、AuditRun 与审计记录；任务级 PR 单独标“任务交付产物” | 无法证明 PR 属于某次 Attempt 时，不把它标成本次 Coding 产物 |
| 质量验证 | CURRENT/REGRESSION 分开，按 criteriaId 显示测试结果及缺口 | 同 stageRunId 的截图、console、network、trace、日志；关联 Host 审计结论 | QA 自报 PASSED 与 Host 已审计 COMPLETED 分列；没有 AuditRun 则显示“待 Host 审计” |

只渲染接口真实提供的字段。`resultPreview` 是摘要输入，可能截断或无法解析，不能作为完整结果下载，也不能据它计算完整验收覆盖率。当前并无完整角色结果读取服务；本计划F02/F06依赖后端B06新增的stage-result接口。其完整内容来自精确绑定的持久化finalization；旧stage只有预览时明确显示“完整结果未保存或无法关联”。

### 3.2 三种状态必须视觉区分

| 来源 | 推荐文案 | 不能表达为 |
| --- | --- | --- |
| `stage.status=SUCCEEDED` | “执行结束”或既有“执行成功” | “所有验收通过” |
| Agent `testStatus` / `latestState.todos` | “Agent 自报测试通过”“Agent 待办” | “Host 已确认” |
| `AuditedRecord.status=COMPLETED` 且有关联 EvidenceRef | “已审计通过” | 仅凭颜色推导的完成 |
| `Manager.route=DONE` | “交由交付复核” | “任务已完成” |
| `task.status=COMPLETED` | “任务已完成” | 从四角色全绿合成的状态 |

存在证据引用但读取失败时，显示“审计记录已通过，证据暂不可读取”，保留来源与重试；不得篡改后端审计状态，也不得说“没有证据”。

### 3.3 证据行的最小字段

每行显示：证据名称/类型、来源角色与 Attempt、关联验收 ID、Host 审计标签和“查看”。完整 identity 放可展开元数据：`stageRunId`、`commandId`、`auditRunId`、`stateVersion/stateHash`、`remediationRoundId`、证据 URI/hash。

证据动作使用现有 task-scoped 读取接口，不在浏览器直接访问数据库路径、容器路径或任意 URI。未知 `sourceKind` 显示原始类型与“暂不支持预览”，允许复制脱敏引用，不拼接猜测的下载链接。

## 4. Coding 内 MEA 协作合同

### 4.1 概念与真实对象映射

| 局部 MEA 职责 | 页面显示 | 数据来源 |
| --- | --- | --- |
| Manage | Host Manager：轮次、决策、目标 AC、边界合同、决策理由 | `rd_task_manager_decisions` 的只读投影 |
| Execute | 当前 Coding Attempt：执行状态、产物、所接收缺口 | 现有 stage run、结果与 Prompt；关联 command |
| Audit | Host 审计：构建/静态、QA 证据、完整性和验收缺口 | Host Verify、实际 QA Attempt、AuditRun、AuditedTaskState |

QA 在此作为被引用的证据生产者出现，点击“QA Attempt 2”跳到外层质量验证角色的同一 Attempt。Host Verify 和 Auditor 职责有关联，但二者不是同一条记录。

### 4.2 当前轮与历史

- 当前轮上方只显示一个结论，例如“修复 AC-003，等待 QA 审计”。文案依据结构化字段模板生成，不调用模型总结。
- Manager 决策 round、remediation round 和 Agent attempt 是三种编号。分别写“决策第 2 轮”“修复第 1 轮”“Coding Attempt 2”，不统一叫 round 2。
- 历史列表默认折叠，每次展开一轮；展示真实顺序 `Coding → HOST_VERIFY → Manager → QA → auditQa → Manager`，决策导致返工时链接到下一 Coding Attempt。
- `sourceCommandId`、`subjectStageRunId`、`codingStageRunId`、`remediationRoundId` 是关联依据；时间排序只用于显示，不作为因果连接依据。
- 同一修复 round 内关联的 Coding、Host Verify、Manager、QA 用同一浅色标识；QA 后退出世代的 Manager 单独标“修复后决策”，不能因时间接近强塞回上一 round。
- 分页/缺少记录时显示“当前仅加载部分历史”或具体 unavailable 原因；不把“本页无复核命令”当作“该任务从未进入复核”的证明。
- `HOST_VERIFY_FIX`、`MANAGER_GAP_FIX`、`QA_PRODUCT_FIX`、`QA_PROTOCOL_RETRY` 依据真实 kind 显示，不把所有重试称作 Manager 返工。

### 4.3 W1e 固定验收夹具

以 W1e `7502196308401328128` 的脱敏证据建立三个 UI 状态：

1. `snapshot-p080`：AC-003 PENDING，Manager 点名 AC-003，显示有界 Coding 修复，无提前复核结论。
2. `snapshot-p157`：仍有缺口，正在等待对应 QA/Host 审计闭环；不能借新一轮结果把旧视图全标绿。
3. `snapshot-p158`：三 AC 全部 COMPLETED，Manager DONE 后交给复核；任务后续为恢复失败，最终状态保持失败，不能显示整体完成。

W1 的 PR #38 仅用作普通成功流程对照。后端 B01–B03 产生的 W1g/W2/W3 新证据用于真实浏览器验收，不能用 fixture 替代。

## 5. Prompt、状态栏与加载

### 5.1 Prompt 页签

- 页签文案“Prompt”，保留 URL `tab=evidence`。
- 内部只保留“最近注入上下文”和“静态 Prompt”；将最新状态移到右侧独立卡。
- 每种视图显示实际来源、注入 sequence/hash、快照时间、截断标志及不可用原因。
- `promptStage` 缺失显示“当前 Attempt 尚无已绑定的 Prompt 读模型”。静态 Prompt 可用但动态状态未启用时，正常显示静态 Prompt。
- 最新状态不意味着已经注入；注入内容与最新上报不同步时保留已有 freshness 标识。

### 5.2 状态栏

- 桌面复用现有左右布局间距，主列约 2/3、侧栏约 1/3，最小宽度由内容验证决定；不引入新的设计 token。
- 侧栏 sticky 的 top 必须避开现有顶栏；页面只保留一个主要纵向滚动区，长待办可局部展开。
- 状态显示：所属角色/Attempt、目标或正在执行动作、Agent 待办、最近上报时间、阻断原因、来源/版本折叠详情。
- 非运行中的 Attempt 标题为“最后状态”；历史 Attempt 无动态数据时直接显示原因，不显示另一轮的最新数据。
- 900px 以下按现有断点测试堆叠；390px 把简短状态摘要置于页签上方，完整状态可展开，不使产物入口离开首屏。

### 5.3 请求触发矩阵

| 数据 | 何时加载 | 刷新/缓存边界 |
| --- | --- | --- |
| task shell | 初始立即；沿用核心轮询 | shell 到达即结束整页 loading，不等任何辅助接口 |
| execution overview | `view=roles` | 复用当前 in-flight、signature 和 task generation guard |
| role-prompts | `view=roles` 且有选中 stageRunId，因状态栏常显而加载 | 复用接口，一份响应供 Prompt 与状态；切 tab 不重复请求；状态 identity 更新时按现有策略刷新 |
| coding-mea | Coding 被选中且有真实 Coding stageRunId | 独立加载/错误；以 task+stage+cursor 为请求键，核心版本变化触发失效；不阻塞普通四角色 |
| QA evidence / Host verify | 默认产物区的所需证据，或用户展开相关引用 | 同 task 的请求去重；stage 精确过滤；不反复下载日志正文 |
| RAG 检索详情 | 用户展开“输入依据” | 不因为状态栏需要 role-prompts 就一并加载 |
| audit state/runs | Coding 审计摘要优先读聚合摘要；完整证据或 audit 视图按需 | 同一资源由统一 loader 缓存，不因两个组件各发一遍 |
| 完整结果、命令日志、截图/trace | 用户查看时 | 角色完整结果走B06新增stage-result；QA/Host日志等复用既有读取；关闭详情后保留轻量摘要 |
| runtime events | `tab=trace` | 保留 SSE/poll 和归档 `latest=true`，不从 sequence 0 全量翻页 |

role-prompts 当前返回 Prompt 与 state 同一读模型，本期允许为状态栏读取该有界响应；不要额外创造 state API，也不要因此请求 task audit-content。后续如果实测响应过大，再独立提出读取优化。

## 6. 接口交接与容错

后端 B04–B06 提供 `GET /admin/rd-tasks/{taskId}/coding-mea`。完整 JSON、枚举、分页和归属校验以配套后端计划第 4 节为唯一合同；前端不得自己增加字段要求。服务层负责解析 DTO，纯展示模型负责关联与文案，组件不按字符串猜关系。

建议新增前端函数签名（均为计划新增）：

```ts
getCodingMea(taskId: string, query: CodingMeaQuery): Promise<CodingMeaResponse>
buildCodingMeaView(response: CodingMeaResponse, selectedStageRunId: string): CodingMeaView
buildRoleDeliverables(input: RoleDeliverableInput): RoleDeliverableView
```

`CodingMeaQuery/Response` 在 `frontend/src/services/codingMeaService.ts` 按后端合同定义。`CodingMeaView` 在 `codingMeaModel.ts` 定义，至少包含当前 Coding、关联决策、验证/审计引用、历史完整性和不可用原因；不增加业务状态枚举。`RoleDeliverableInput/View` 在 `roleDeliverableModel.ts` 定义，只消费当前 roleWorkbench model 已绑定的 Attempt 及证据，不重新查找“最近一个相同角色”。

容错必须覆盖：尚未进入 Coding、历史任务无 Manager、聚合 API 404/暂未上线、500、超时、分页不完整、证据已过期、未知字段、角色快速切换、重复刷新。缺 MEA API 只使 Coding 协作区显示“协作追踪暂不可用”，四角色和原有证据仍可使用；不得以 mock 数据填生产空白。

## 7. 精确实施任务

每个任务先更新自己的 delta 场景与失败断言，再实现；目标是可独立评审的行为。现有文件以符号定位，避免依赖昨日行号。

### F01：冻结页面合同与分支

**Files:** 新增 `openspec/changes/mea-task-workbench/{proposal.md,design.md,tasks.md}`、`openspec/changes/mea-task-workbench/specs/requirement/task-workbench/spec.md`；引用本文与后端计划。

**输入/输出：** 消费用户的三项回答；输出四角色外层、Coding 内 MEA、默认产物、状态常显、接口依赖和 source classification。

- [ ] 记录 base SHA 与工作区归属，用独立 worktree 创建 `codex/mea-task-workbench`。
- [ ] 将第 2–6 节拆成 delta 的可验收场景，明确覆盖昨日默认 Prompt 决策。
- [ ] 与后端 B04 锁定 JSON fixture；不将拟新增接口写成已实现接口。
- [ ] 验证 change，产出一份 `frontend-contract.md` 放在该 change 下，记录接口字段与本计划版本。

**验证：** `openspec validate mea-task-workbench --strict`。退出：不存在“全流程 MEA 包裹四角色”或“新增第五执行角色”的歧义。

### F02：接入 Coding MEA 读取合同

**Files:** 新增 `frontend/src/services/codingMeaService.ts`、`frontend/test/codingMeaService.test.ts`、`frontend/src/services/stageResultService.ts`、`frontend/test/stageResultService.test.ts`；修改 `frontend/test/viteProxy.test.ts`，必要时仅修改现有 Vite bypass 的精确路由白名单。

**接口：** 产出 `CodingMeaQuery`、`CodingMeaResponse` 和 `getCodingMea`；同服务提供 `getManagerDecision(taskId, decisionHash)`。stageResultService提供 `getStageResult(taskId, stageRunId)` 和后端第4.4节的 `StageResultResponse`；消费后端第 4 节合同。

- [ ] 用后端 fixture 断言 ID 均为字符串、nullable 字段保留、分页标志不丢失。
- [ ] 新增 GET 适配，沿用现有 HTTP client/error 类型；URL 参数通过参数对象编码。
- [ ] 为 `/admin/rd-tasks/123/coding-mea` 增加普通请求与 `Accept: text/html` 均代理后端的断言；保留详情页导航返回 SPA。
- [ ] 同时为 `/manager-decisions/{decisionHash}`、`/stage-runs/{stageRunId}/result`、`/result/content` 增加代理合同；解析source/truncated/downloadPath，不生成未返回的下载地址。
- [ ] 断言 404/超时抛出可由局部面板处理的错误，不合成空成功响应。

```ts
// 新测试的关键断言；fixture 必须源自 B04 合同。
assert.equal(response.codingStageRunId, fixture.codingStageRunId)
assert.equal(response.page.hasMore, fixture.page.hasMore)
assert.equal(typeof response.taskId, 'string')
```

**验证：** `cd frontend && node --experimental-strip-types --test test/codingMeaService.test.ts test/stageResultService.test.ts test/viteProxy.test.ts`。

### F03：实现四角色产物纯展示模型

**Files:** 新增 `frontend/src/pages/admin/rdtask/roleDeliverableModel.ts`、`frontend/test/roleDeliverableModel.test.ts`；消费 `roleWorkbenchModel.ts`，不重写其选中/绑定逻辑。

**接口：** `buildRoleDeliverables(RoleDeliverableInput): RoleDeliverableView`；View 分开 `executionSummary`、`reportedChecks`、`auditedChecks`、`artifactLinks`、`unavailableReason`。

- [ ] 为四角色各建立一份完整结果、一份截断/无结构摘要、一份没有产物的 fixture。
- [ ] 按第 3 节字段白名单提取最多三项摘要；原文截断只用于显示，不改变原始证据数据。
- [ ] 所有证据按传入的精确 stageRunId 绑定；任务 PR 标明任务级范围。
- [ ] 缺字段保留不可用原因，不以空数组等价“无问题/全部通过”。
- [ ] 断言 Agent 自报 PASSED 且无 AuditRun 时 `auditedChecks` 不产生通过项。

```text
输入：Coding Attempt 1 自报 PASSED；Host Verify 绑定 Attempt 2。
预期：Attempt 1 仅显示自报；不显示 Attempt 2 的 Host 通过标签。
输入：QA CURRENT=PASSED；AC-003 在 audited head=PENDING。
预期：两种结论并列，缺口仍显示 AC-003。
```

**验证：** `cd frontend && node --experimental-strip-types --test test/roleDeliverableModel.test.ts test/roleWorkbenchModel.test.ts`。

### F04：重组默认产物区与四角色摘要

**Files:** 修改 `frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx`；新增 `frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx`；修改 `frontend/test/roleWorkbenchPresentation.test.ts`、`frontend/test/taskDetailInformationArchitecture.test.ts`。

**输入/输出：** 消费 F03 View，产出第 2 节四角色摘要和“产物与证据”默认主体。

- [ ] 保持四角色/Attempt 选择器与 `issues` URL，修改页签文案与顺序为产物、Prompt、追踪、记录。
- [ ] 替换首屏重复 Provider/ID/状态大网格，保留内容到可展开元数据。
- [ ] 四角色卡展示角色状态及一行产物摘要；长标题换行/截断具有可访问完整名称。
- [ ] 原始结果统一收起并保留读取入口；不能将 `resultPreview` 假装完整 JSON。
- [ ] 同任务从 `issues` 切其他页签再返回不重置 Attempt；URL 刷新仍能定位。

**验证：** 上述两项 Node 测试；真实视觉纳入 F10。退出：默认页签上可直接看到至少一种角色产物和对应证据入口。

### F05：实现 Coding 局部 MEA 模型与面板

**Files:** 新增 `frontend/src/pages/admin/rdtask/codingMeaModel.ts`、`frontend/src/components/admin/rdtask/CodingMeaPanel.tsx`、`frontend/test/codingMeaModel.test.ts`、`frontend/test/codingMeaPresentation.test.ts`；修改 `TaskRoleWorkbench.tsx` 的 Coding 分支。

**接口：** 消费 F02 response 和当前 Coding stageRunId，产出 F06 可跳转的角色/Attempt/审计引用；不写任务状态。

- [ ] 先建立 W1e 三快照 fixture，保留 sourceCommandId、remediationRoundId 与两个 QA stageRunId。
- [ ] 实现 Manager / Coding 执行 / Host 审计三栏，默认一轮；只在 Coding 区域挂载。
- [ ] 展示目标 AC 与 bounded contract 摘要，完整合同点击展开；rationale 使用服务端原文脱敏内容。
- [ ] 展示同轮 Host Verify 与 QA/AuditRun；QA 点击跳到已有第四角色 Attempt。
- [ ] 历史轮次按真实关联组织；首轮没有 Manager、退出世代 Manager、分页缺口分别显示。
- [ ] 断言 W1e 最后一快照 Manager DONE 只产生“交由复核”，不改变任务失败状态。

```text
assertion A: selected Coding stage A 的 QA 链接只指向后端明确关联的 QA stage B。
assertion B: 同一 remediationRoundId 的对象聚合；QA 后普通 Manager 保持独立。
assertion C: response.page.hasMore=true 时，页面必须出现“部分历史”提示。
assertion D: 选中 Reviewer/Architect/QA 时，不渲染 CodingMeaPanel 的三栏内容。
```

**验证：** `cd frontend && node --experimental-strip-types --test test/codingMeaModel.test.ts test/codingMeaPresentation.test.ts`。

### F06：贯通产物、QA 与审计证据跳转

**Files:** 修改 `TaskRoleWorkbench.tsx`、`frontend/src/components/admin/rdtask/AuditedTaskStateCard.tsx`、`frontend/src/components/admin/rdtask/HostVerificationCard.tsx`、`frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`；修改 `frontend/test/qaEvidencePresentation.test.ts`，新增 `frontend/test/taskEvidenceNavigation.test.ts`。

**接口：** 跳转使用现有 `view/role/attempt/tab`；可新增 `recordId`、`auditRunId` 查询参数用于 audit 视图定位，后端仍验证归属。MEA 轮次使用 `meaRound` 仅作选择，不驱动写操作。

- [ ] 将 EvidenceRef 按已支持 sourceKind 分派到 QA/Host/审计/发布既有读取入口。
- [ ] “查看角色结果”按需调用F02的stage-result服务；source=ARTIFACT_PREVIEW时标明预览，downloadPath为空不显示完整下载；source=FINALIZATION_RESULT时保留来源command/finalization身份。
- [ ] 增加从 Coding 协作到 QA 精确 Attempt、从 AC 到 AuditRun/证据、从审计返回来源 Attempt 的动作。
- [ ] 点击引用时保留当前 task/project；未知、失效和跨任务引用拒绝/不可用，不导航猜测地址。
- [ ] 将 Host 成功详情改为摘要折叠；失败原因和当前失败步骤仍直接可见。
- [ ] 任务审计页保留全量表/轮次，Coding 面板只做摘要和定位，不复制整份审计工作台。
- [ ] 以 `sourceStageRunId/subjectStageRunId` 精确查 `executionOverview.stageRuns` 得到role+attempt，再生成URL；stage未加载先请求其所在概览，不能从ID字符串或时间猜角色。

**验证：** QA 和导航测试，F10 实测一次截图、console、network、trace、Host 日志和完整角色结果读取。

### F07：将状态栏移出 Prompt 内层页签

**Files:** 修改 `frontend/src/components/admin/rdtask/RoleEffectiveContextCard.tsx`；新增 `frontend/src/components/admin/rdtask/RoleAgentStateCard.tsx`；修改 `TaskRoleWorkbench.tsx`、`frontend/test/rdTaskRolePromptPresentation.test.ts`。

**接口：** `RoleAgentStateCard` 接收当前绑定的 promptStage/latestState 与 unavailable 信息；复用现有 `AgentLatestStatePanel`、`AgentTodoList`，只移动展示职责。

- [ ] 提取已有状态展示，不重新实现 state JSON parser。
- [ ] 在 workbench 模式使 Prompt 卡只显示 effective/static，状态放右侧；独立引用点可保留旧 standalone 模式，避免未调查的调用方退化。
- [ ] 标明当前/历史 Attempt，状态时间与实际注入时间分别显示。
- [ ] 保留 `latestState.available=false` 时静态 Prompt 可读、缺读模型文案、sequence/hash freshness 的全部断言。
- [ ] 测试四个左页签均保留右侧状态区域，换 Attempt 立即刷新身份。

**验证：** `cd frontend && node --experimental-strip-types --test test/rdTaskRolePromptPresentation.test.ts test/roleWorkbenchModel.test.ts`。

### F08：重排加载与异步保护

**Files:** 修改 `RdTaskDetailPage.tsx` 的 `loadInitial`、`loadRolePrompts`、`loadRoleEvidenceData` 与相关 effects；消费 `rdTaskDetailLoader.ts`；新增 `frontend/test/codingMeaLoading.test.ts`，修改 `taskDetailInformationArchitecture.test.ts`。

**接口：** F02 API 的局部 loading/error 独立于 core；保留现有 signature 与请求序列。

- [ ] 将 role-prompts 的触发从 `tab=evidence` 扩到所选角色状态栏可见；同一 task/stage/signature 只请求一次。
- [ ] 将 RAG 与大证据详情从 role-prompts 的触发条件分离，按第 5.3 节实现。
- [ ] coding-mea 的响应只有 task、stage、generation、cursor 都匹配才能写入。
- [ ] 切换 task 时清空其局部 selection/cache/error；旧 Promise 完成时丢弃结果。
- [ ] 保留 shell 到达立即出页面，核心轮询不得等待 coding-mea。
- [ ] 用可控 deferred Promise 测试 A→B 路由竞态、旧分页覆盖新轮次、重复刷新去重和错误重试。

```text
顺序：发起 task A 请求 → 切 task B → B 成功 → A 成功。
断言：当前页面仍是 B，Coding、Prompt、右侧状态、证据没有任何 A 的 identity。
```

**验证：** loading 与 IA 测试；浏览器 Network 面板确认切 tab 不重复加载相同 Prompt、不自动拉 RAG 全详情。

### F09：状态、等待输入与响应式收口

**Files:** 修改 `RdTaskDetailPage.tsx`、`TaskRoleWorkbench.tsx` 与本次新增组件；复用已有 `TaskSummaryBand`、回答弹窗；新增 `frontend/test/meaWorkbenchStates.test.ts`。

- [ ] 合并重复任务摘要，但保留暂停、等待输入、失败与恢复入口。
- [ ] `WAITING_USER_INPUT` 仍使用现有紫色/文案，与 `WAITING_APPROVAL` 区分；ASK 详情能定位到 Manager 决策。
- [ ] 回答表单保持现有 `version/fencingToken/managerDecisionHash/answerRequestId` 防并发逻辑；不新增另一套 submit/answer 操作。
- [ ] 断言任务暂停时局部执行状态不伪造为取消；历史 stage 仍可浏览。
- [ ] 按第 5.2 节完成桌面 sticky、900px/390px堆叠与键盘操作。

**验证：** 状态测试及 F10 的真实 W2/W3 页面；只看徽标不能代替 claim 守卫后端验收。

### F10：前后端联调与交接

**Files:** 新增 `docs/superpowers/qa/2026-09-06-mea-task-workbench-acceptance.md`；脱敏截图/网络摘要置于同名证据子目录；更新本 change tasks，不修改别人的 P3 归档。

- [ ] 运行全部前端测试、typecheck、build，记录命令/退出码；构建产生的 `bootstrap/src/main/resources/static/admin` 由前端分支负责，避免后端重复提交。
- [ ] 使用后端 B06 已验证的真实服务，分别查看 W1e、普通成功任务、新 W1g、W2、W3。
- [ ] 完成第 8 节逐项浏览器验收，保存390px/900px/桌面截图、console 与关键 API 请求摘要。
- [ ] 列出已完成/未完成项、实际 taskId、stageRunId、backend SHA、frontend SHA 与浏览器时间；不把旧测试数量复制为本轮结果。
- [ ] `git diff --check`；检查差异仅含本分支文件与生成静态资产。没有验收证据的条目保持未勾选。

```bash
cd frontend
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build
```

仓库根执行：`openspec validate mea-task-workbench --strict`、`git diff --check`。归档在实际实施/验收和授权后进行，届时再跑 `openspec validate --all --strict`。

## 8. 验收矩阵

| ID | 场景 | 可观察通过条件 |
| --- | --- | --- |
| FE-01 | 首次进入 | 四角色入口完整；默认产物证据；唯一任务状态栏 |
| FE-02 | Coding MEA | Coding 内三职责可见；外层角色仍四个；Manager/Host 不变成执行 Agent |
| FE-03 | 快速识别 | 在桌面首屏能指出当前任务位置、所选角色产物、一个关键证据和当前缺口；不需展开原始 JSON |
| FE-04 | W1e 回放 | AC PENDING 与 Manager 缺口合同相符；DONE 后仍保留任务最终失败 |
| FE-05 | 精确跳转 | Coding → QA Attempt → AC → AuditRun → 证据，identity 均属于同任务和正确 Attempt |
| FE-06 | Prompt/状态 | 四页签切换时状态常显；无动态状态的旧 PI 仍能读静态 Prompt |
| FE-07 | 历史 Attempt | 用户选择不被轮询抢回；旧 Prompt/状态不被当前轮覆盖 |
| FE-08 | 证据可信度 | Agent 自报与 Host 核验分开；缺口、不可读证据和截断结果都有原因 |
| FE-09 | 请求隔离 | 快速 A→B 不串任务；辅助超时不阻塞 shell；不拉无关 RAG/日志正文 |
| FE-10 | ASK/pause | W2 等待输入/回答与 W3 暂停显示真实状态，已有恢复防并发行为不变 |
| FE-11 | 响应式 | 390/900/桌面无横溢出、顶栏遮挡、按钮失焦；Esc 能正确关闭详情并归还焦点 |
| FE-12 | 风格保持 | 与原截图/当前页面对照，配色、字号体系、边框、圆角、Tab、按钮沿用原组件；变化集中在信息顺序和默认展开 |
| FE-13 | 大历史 | 多 Attempt/多轮/分页明确；无按任务全量日志下载，无一屏罗列所有命令 |
| FE-14 | 真机新版本 | W1g/W2/W3 使用后端计划本轮实际证据；fixture 与真实结果明确区分 |

## 9. 给前端 Agent 的开工指令

```text
在 /Users/wish233/Documents/RD-Bot 实施本计划，使用独立 worktree/分支 codex/mea-task-workbench。
先读 RULE.md、相关主 spec、本计划和配套后端计划第4节；核对当前main与他人未提交文件。
四角色是完整流程，MEA只在Coding内部。默认产物与证据，Prompt一键切换，所选Attempt状态常显。
先做F01，与后端B04冻结接口；F02-F09可用明确标注的fixture开发；F10必须真实后端与浏览器。
精确保留stageRunId、commandId、sourceCommandId、remediationRoundId、auditRunId，不按时间猜关联。
不要改后端调度、QA协议、审计完成门；不要改P3历史证据、接管其他Agent的归档或部署。
交接给出实际文件/测试/截图/服务版本与未完成项。业务实现、提交和上线按接手会话授权执行。
```

## 10. 本轮计划交付检查

本轮只新增本文与配套后端计划。已核对F01–F10连续、前后端读取字段一致、代码围栏成对、引用的现有文件路径可定位；新文件均在任务中标出。没有执行前端测试/build、浏览器验收、后端测试或部署，没有创建开发分支、commit或归档。本节不能替代F10的业务验收报告。
