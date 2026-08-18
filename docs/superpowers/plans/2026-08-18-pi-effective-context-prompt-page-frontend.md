# 管理端 Prompt 页面“有效上下文 + 最新状态”前端交接计划

> 日期：2026-08-18  
> 交接对象：独立前端 Agent  
> 范围：仅 React/TypeScript 管理端展示和前端测试  
> 后端依赖：`docs/superpowers/plans/2026-08-18-pi-agent-state-and-qa-remediation-backend.md` 的 role-prompts API 扩展

## 1. 目标和边界

在任务详情的角色工作台中，把当前“实际角色 Prompt”升级为可审计的三部分：

1. **有效上下文**：当前 Attempt 的静态 `PROMPT_SNAPSHOT` + 最近一次真实注入的 PI 状态块；
2. **最新状态**：状态栏的最新 snapshot，包含目标、阶段、时间、预算、TODO、blocker 和结果状态；
3. **静态 Prompt**：保留原始安全预览，便于对照。

本前端任务不修改 Java、SQL、Pi bridge、QA 路由或状态协议。后端字段未就绪时先补类型/fixture 测试，不自行拼接一个看似真实的“有效上下文”。

## 2. 当前代码锚点

- API types 与请求：`frontend/src/services/rdTaskService.ts`
  - `RdTaskRolePromptResponse`
  - `RdTaskRolePromptStage`
  - `getRdTaskRolePrompts(...)`
- Attempt 精确绑定：`frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts`
  - `buildRoleWorkbench(...)` 使用 `stageRunId` 绑定 prompt；必须保留。
- 页面：`frontend/src/components/admin/rdtask/TaskRoleWorkbench.tsx`
  - `RoleEvidencePanel(...)` 当前展示“实际角色 Prompt”和“上下文证据”。
- 数据加载：`frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
  - role-prompts 当前按 stage signature 懒加载。
- 已有测试：
  - `frontend/test/rdTaskRolePromptPresentation.test.ts`
  - `frontend/test/roleWorkbenchModel.test.ts`
  - `frontend/test/taskDetailInformationArchitecture.test.ts`
  - `frontend/test/viteProxy.test.ts`

## 3. 后端响应依赖

继续使用：

```text
GET /admin/rd-tasks/{taskId}/role-prompts
```

不要新增第二个并行 endpoint，避免 Prompt、最新状态和用户选中 Attempt 发生竞态。期待每个 `stagePrompts[]` 增加：

```ts
interface RdTaskRolePromptStage {
  // existing fields
  runtimeType: string;
  effectiveContext: RdTaskEffectiveContext;
  latestState: RdTaskLatestAgentState;
}

interface RdTaskEffectiveContext {
  available: boolean;
  unavailableReason: string;
  source: "LIVE_PROJECTION" | "ARCHIVED_ARTIFACT" | "";
  finalized: boolean;
  stale: boolean;
  staleReason: string;
  lastProjectionAtEpochMillis: number;
  staleAfterMillis: number;
  protocol: string;
  compositionOrder: string[];
  injectionSequence: number;
  promptArtifactId: string;
  promptContentHash: string;
  stateArtifactId: string;
  stateSequence: number; // 该 injection 实际使用的 state sequence，不是 latest sequence
  stateContentHash: string;
  injectedBlockHash: string; // 当次实际 injected block hash；不得用 composed contentHash 替代
  contentPreview: string;
  contentHash: string;
  contentLength: number;
  previewLength: number;
  truncated: boolean;
  generatedAtEpochMillis: number;
}

interface RdTaskLatestAgentState {
  available: boolean;
  unavailableReason: string;
  source: "LIVE_PROJECTION" | "ARCHIVED_ARTIFACT" | "";
  finalized: boolean;
  stale: boolean;
  staleReason: string;
  lastProjectionAtEpochMillis: number;
  staleAfterMillis: number;
  artifactId: string;
  protocol: string;
  sequence: number;
  generatedAtEpochMillis: number;
  currentGoal: string;
  phase: string;
  taskStartedAt: string;
  stageStartedAt: string;
  resultStatus: string;
  blocker: string;
  budget: RdTaskAgentStateBudget;
  todos: RdTaskAgentTodo[];
  recentErrors: RdTaskAgentRecentError[];
  contentHash: string;
  previewTruncated: boolean;
}
```

字段以最终后端 DTO 为准，但必须保留 `available/unavailableReason`、identity/provenance、state/injection sequence/hash、显式 injectedBlockHash、Host-owned stale provenance 和 truncation 语义。

## 4. 页面信息架构

在 `RoleEvidencePanel` 顶部把“实际角色 Prompt”替换为“角色有效上下文”卡片。

### 4.1 顶部摘要

显示：

- runtime badge：`PI` / 非 PI；
- Attempt 与 stageRunId（stageRunId 使用可复制的短 hash 展示）；
- 最新状态 sequence；
- `动态状态已注入`、`最新状态未注入`、`动态状态未启用` 等准确 badge；
- `运行中投影` / `已归档终态` / `投影陈旧` 来源状态；stale 只读取后端 `stale/staleReason/lastProjectionAt/staleAfter`，前端不按时间自行推断；
- Prompt hash / state hash 可折叠查看。

不得根据 `latestState.available` 推断“已注入”。只有 `effectiveContext.available` 才能显示“动态状态已注入”。

### 4.2 三个页签

默认页签：`有效上下文`；另有 `静态 Prompt`、`最新状态`。

**有效上下文**

- 用现有 `MarkdownRenderer` 显示后端给出的 safe `contentPreview`；
- 上方标出组合顺序 `PROMPT_SNAPSHOT → AGENT_STATE` 与 as-of sequence；
- truncated 时展示真实 `contentLength` / `previewLength`；
- unavailable 时展示后端原因，并引导用户切换到静态 Prompt/最新状态，不在浏览器自行拼接。

**静态 Prompt**

- 保留现有 Prompt metadata 和 Markdown 展示；
- 文案明确“这是派发时静态指令，不含运行中最新状态”。

**最新状态**

- 不直接渲染原始 JSON；使用结构化状态组件。
- 顶部：当前目标、phase/resultStatus、task/stage 起始时间、最新更新时间、blocker。
- 预算：token、context、deadline；未知数据展示“未提供”，不显示假 `0%`。
- TODO：按 `IN_PROGRESS → BLOCKED → PENDING → DONE → CANCELLED` 排序；显示 source/required、验收引用、证据数和阻断原因。
- recent errors 默认折叠，仅显示脱敏摘要、工具名和时间；不展示原始工具参数。
- 提供“查看安全原始状态”折叠区的前提是后端明确返回 safe preview；前端不得请求私有 raw artifact。

### 4.3 原有上下文证据

现有“上下文证据”“检索运行”“QA 验证证据”保持在三页签卡片之后，不混入状态栏。它们继续按当前 stageRunId 精确绑定。

## 5. 状态与刷新

- `roleWorkbenchModel.buildRoleWorkbench(...)` 继续只通过完全相等的 `stageRunId` 绑定 Prompt/state；禁止按 role 或 attemptNo 猜测。
- 补齐 `RdTaskStageRun` 已由 Java execution-overview 返回、但 TypeScript 当前遗漏的字段：`agentStateAvailable`、`agentStateSequence`、`agentStateSchemaVersion`、四类 TODO count、`agentStatePreviewTruncated`、`agentStateContentHash`，以及后端新增的 `agentLastInjectionSequence`、`agentLastInjectedStateSequence`、`agentLastInjectedBlockHash`、`agentLastInjectedPromptHash`。
- 扩展 `roleStageSignature(...)`，把 overview 中 state 和 injection 两组 identity 全部纳入 signature。详情页现有 overview 轮询因此既能发现“stage status 未变但状态已更新”，也能发现“state 未变但发生了新 context injection”，再触发 role-prompts fetch；不能把只有 role-prompts response 自己才知道的 hash 当作 fetch 触发源。
- `loadRolePrompts(signature)` 发请求时除现有 generation/sequence guard 外，还要捕获 `expectedByStageRunId = {stateSequence, stateHash, injectionSequence, injectedStateSequence, injectedBlockHash, promptHash}`。响应返回后对 state 与 injection 分别校验 freshness；`effectiveContext.contentHash` 是组合预览 hash，禁止代替 `injectedBlockHash`：
  - 任一 response sequence 小于对应 expected：丢弃响应、不更新 `loadedRolePromptSignatureRef`，按 bounded backoff 重试；
  - sequence 相等但对应 state/block/prompt hash 不同：视为一致性错误，丢弃并展示校验失败；
  - 两组 sequence/hash 相等：正常接受；
  - 任一 response identity 领先 expected：接受该响应，但立即触发一次 guarded overview refresh 对账，不能让 overview 永久落后。
- 慢响应不得覆盖较新的 overview signature；响应返回后还要确认当前 taskId、请求 generation 和当前 expected map。只有通过 freshness 校验的 payload 才能原子替换 state/effective/prompt 并记录 loaded signature。
- 使用现有详情刷新节奏；同一 HTTP response 原子替换该 Attempt 的 prompt/effective/state。
- 用户正在阅读旧 Attempt 或非默认页签时，后台刷新不得把选择跳回最新 Attempt/默认页签。
- 把选中 role、attempt、证据主 tab 继续保存在 URL；上下文子页签可放本地 state，如项目已有 URL 约定则跟随现有约定。
- 终态 Attempt 若 hash/sequence 未变化，不重复触发 Markdown 重渲染。

## 6. 空态与错误文案

必须区分：

| 情况 | 文案 |
|---|---|
| 非 PI runtime | 当前 Attempt 不是 PI，状态栏不适用 |
| 历史 PI 配置关闭 | 当前 Attempt 的冻结配置未启用动态状态 |
| 尚未生成 Prompt | 当前 Attempt 尚未生成实际角色 Prompt |
| 有最新状态、无 injection descriptor | 已记录最新状态，但尚无可证明的注入上下文 |
| Host 标记 live projection stale | 状态投影已陈旧；显示后端原因、最后成功时间和阈值，不声称这是最新状态 |
| hash/stage 不一致 | 有效上下文校验失败，请查看阶段错误或联系管理员 |
| 预览截断 | 安全预览已截断，并显示原始/预览长度 |

错误时仍允许查看可用的其他页签。不得回退展示 `task.promptSnapshot`，也不得跨 Attempt 借用状态。

## 7. 响应式与可访问性

- 390px：tabs 可横向滚动或等宽换行；TODO 单列；长 ID/hash 断行；代码区横向滚动。
- 900px：预算使用 2 列，TODO 保持单列以避免内容压缩。
- desktop：摘要最多 4 列；内容区 `max-height` + 内部滚动，不让整个角色页无限增高。
- tabs 使用可键盘操作的现有 shadcn/Radix 组件；如仓库没有 Tabs，则使用 `role=tablist/tab/tabpanel` 和方向键语义。
- badge 不只用颜色表达状态；所有 icon 有文本或 `aria-label`。
- 时间同时显示本地格式与 title 中的绝对时间。

## 8. 文件级任务

### Task 1：类型和 fixture

- [ ] 修改 `frontend/src/services/rdTaskService.ts`，增加 effective/latest-state 类型，并补齐 overview stage 的 state + injection identity 字段。
- [ ] 更新 role prompt API fixture，覆盖 available、unavailable、latest sequence 高于 injected sequence、injection-only 前进、explicit injectedBlockHash、stale、truncated。
- [ ] 不新增 endpoint，不改 Vite proxy，除非后端最终改变已锁定路径；若路径不变，`viteProxy.test.ts` 应证明现有代理仍覆盖。

### Task 2：纯展示模型

- [ ] 在 `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts` 或相邻纯函数文件增加状态排序、badge 派生和 unavailable 映射。
- [ ] 单测精确 stageRunId 绑定，增加“同角色两个 Attempt 不串状态”的 case。
- [ ] 增加“latestState sequence 12、effective sequence 10”时显示“最新状态未注入”，而不是已注入。

### Task 3：有效上下文卡片

- [ ] 修改 `TaskRoleWorkbench.tsx` 的 `RoleEvidencePanel`。
- [ ] 抽取小组件：`EffectiveContextPanel`、`AgentLatestStatePanel`、`AgentTodoList`；避免继续扩大单函数。
- [ ] 复用 `MarkdownRenderer`、`Metric`、`Badge`、`EmptyLine`、`PanelError`。
- [ ] 原“上下文证据”以下区域行为不变。

### Task 4：刷新和选择稳定性

- [ ] 修改 `roleWorkbenchModel.roleStageSignature(...)` 与 `RdTaskDetailPage.tsx` 的 signature/cache 更新逻辑：overview 的 state 或 injection identity 变化都触发 role-prompts refetch。
- [ ] 保留并测试 request generation/sequence guard，并增加 expected-state freshness gate：先发出的旧 signature 响应、当前 generation 但落后 expected 的响应都不得覆盖新 state或标记已加载。
- [ ] 测试 response 领先 overview 时先展示更新数据并触发 guarded overview 对账，不产生无限请求循环。
- [ ] 增加 state sequence/hash 完全不变、仅 injectionSequence/blockHash 前进的 case，证明有效上下文仍会刷新。
- [ ] 增加后端 stale=true/reason/threshold/time 的展示 case，并证明前端不自行按时钟推断 stale。
- [ ] 测试刷新后仍保留 role/attempt/tab；被选 Attempt 消失时才回退。
- [ ] 避免新增与 overview 并行、结果可能乱序的 state fetch。

### Task 5：前端验证

- [ ] 静态测试锁定标题、页签、MarkdownRenderer、安全文案和无 `task.promptSnapshot` fallback。
- [ ] 运行 typecheck/build。
- [ ] 真实浏览器验证 desktop 1440×900、tablet 900×900、mobile 390×844。
- [ ] 保存每个视口截图；验证长 TODO、长 hash、截断提示、空态、两个 Attempt 切换。

## 9. 验收标准

1. 用户选择任一角色 Attempt，可看到同 stageRunId 的有效上下文、静态 Prompt 和最新状态。
2. 默认显示有效上下文；其 preview/hash/sequence 全部来自后端，而不是浏览器推断。
3. 最新状态清晰显示当前目标、时间、阶段、预算、TODO、blocker 和 result status。
4. latest sequence 大于 last injected sequence 时，页面准确区分两者。
5. 非 PI、历史未启用、产物缺失和校验失败都有独立空态。
6. 同角色多个 Attempt 不串 Prompt/state/evidence；刷新不重置用户选择。
7. 不展示 reasoning、原始工具参数、凭据、外部 artifact URL 或任务级基线 Prompt fallback。
8. 390/900/1440 三种宽度无溢出、遮挡或不可操作 tabs。
9. active PI Attempt 仅更新 state sequence/hash、stage status 不变时，页面仍会刷新；乱序 HTTP 响应不会回退到旧状态。
10. overview 已为 sequence 12 而 role-prompts 返回 10 时，payload 被拒绝且 loaded signature 不前进；role-prompts 返回 13 时触发 overview 对账。
11. state sequence/hash 不变但 injection sequence 前进时，signature 改变并刷新有效上下文；落后的 injection payload 同样被拒绝。
12. stale 标记、原因、最后投影时间和阈值全部来自 Host；finalized archived artifact 不因页面停留时间变成 stale。

## 10. 验证命令

```bash
cd frontend
node --experimental-strip-types --test \
  test/rdTaskRolePromptPresentation.test.ts \
  test/roleWorkbenchModel.test.ts \
  test/taskDetailInformationArchitecture.test.ts \
  test/viteProxy.test.ts
npm run typecheck
npm run build
```

浏览器联调前先确认后端 response 中当前 Attempt 同时含 `effectiveContext` 和 `latestState`。若后端尚未完成，只能交付前端 fixture 测试和静态布局，不能声称联调验收完成。

## 11. 不做事项

- 不在前端解析 `agent-state-events.jsonl`。
- 不访问私有 raw Pi events 或文件系统路径。
- 不提供 TODO 人工编辑按钮；本期是只读审计。
- 不把 overview 的 TODO 计数拼成完整状态。
- 不从静态 Prompt + 最新 snapshot 在浏览器重建“有效上下文”。
- 不修改 QA 打回策略、后端 schema、SQL、Pi bridge 或 Docker 镜像。
