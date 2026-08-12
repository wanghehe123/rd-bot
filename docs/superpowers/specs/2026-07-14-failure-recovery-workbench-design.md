# RD-Bot 失败恢复工作台设计

## 1. 目标

当需求交付任务在某个角色或治理阶段失败时，管理台必须把机器可读的产物转换为可操作的诊断，并允许操作者在不重跑成功上游阶段的前提下补充证据、创建新的失败阶段 attempt 并继续交付。

本设计解决两个当前问题：

1. `RdTaskDetailPage` 将角色结果 JSON 直接以大段 `pre` 输出，操作者难以识别真正缺失的事实、验收条件或失败类别。
2. 现有“从失败阶段重试”只展示失败阶段和原因；任务材料上传也没有和本次重试 checkpoint 建立可审计关联，无法证明补充证据被下一 attempt 使用。

本设计只覆盖需求交付任务的失败恢复工作台。它不把 RD-Bot 退化为单角色任务重跑器，也不提供任意阶段回退或全任务版本编辑。

## 2. 已确认边界

用户已确认首版按以下边界实现：

- 从当前确定的失败阶段继续执行，并为该角色及其下游角色创建新的 `attemptNo`。
- 已成功的上游角色、旧阶段产物和旧 attempt 永远保持不变。
- 失败时支持补充文字、文本类文件和图片证据，再从失败点重试。
- 不提供整任务编辑、不允许操作者选择任意前序角色重跑、不修改已终态的 `AgentStageRun`。

现有 `TaskRetryEngine` 已能解析失败点并创建失败角色及下游角色的新 attempt；`RequirementDeliveryEngine` 已会复用成功阶段。新能力必须建立在这两个行为之上，而不是重写多角色编排。

## 3. 非目标

- 不修改完成任务、取消任务、死信任务或非需求任务的状态语义。
- 不允许编辑已归档 Prompt、阶段结果、上下文包、历史重试 checkpoint 或成功阶段产物。
- 不实现“重新执行所有角色”开关。
- 不把 Feishu URL 当作已读取的正文证据；首版补充证据以人工文字、已允许的本地文件和图片为准。
- 不在新的 UI、日志、checkpoint 或文档中写入凭证、访问令牌或其他敏感明文。

## 4. 现有基础与缺口

### 4.1 可复用基础

- `TaskRetryPointResolver` 能基于最新失败角色、RAG、AI review 或结构化主任务结果定位唯一重试点。
- `TaskRetryEngine` 会创建 durable checkpoint，把任务转为 `RECOVERING`，并为失败角色及下游角色创建新的 attempt。
- `RequirementDeliveryEngine` 对成功角色复用不可变的阶段结果；重试时会重新读取 `TaskMaterial`，重新构造材料提示词、RAG 根证据和角色上下文。
- `TaskMaterialStore`、对象存储上传、PostgreSQL `rd_task_materials`、阶段产物和 retry checkpoint 已具备持久化边界。
- 管理端已有任务详情、材料列表、执行概览、角色 Prompt / RAG 证据读取和 `/retry-preview`、`/retry-history`、`/retry` 接口。

### 4.2 缺口

- 执行概览只把 `resultPreview` 格式化为 JSON，未提取 `missingInformation`、风险、验收覆盖或 QA/RAG/策略失败信息。
- 当前确认重试对话框没有上下文证据输入，也没有显示本次 evidence 与 checkpoint 的关联。
- 详情页仅提供“补充图片”，列表页编辑仅修改标题、优先级和工单标题；它们都不是失败恢复输入面。
- `TaskRetryCheckpoint` 当前没有保存人工说明和选中的材料 ID；下一 attempt 缺少可回溯的“为何恢复、用了哪些新证据”的审计字段。

## 5. 方案对比

### 方案 A：只在前端格式化 JSON

前端解析已加载的 `resultPreview`，并继续调用现有材料上传和空 body 的 `/retry`。

优点是改动最小；缺点是前端无法可靠覆盖 RAG、AI review、策略等失败来源，也无法把材料绑定到 checkpoint 或证明它进入了下一 attempt。该方案不满足审计与恢复要求，不采用。

### 方案 B：失败恢复工作台

新增后端结构化失败读模型和受约束的恢复命令；前端在任务详情中呈现一个失败诊断与证据补充工作台。补充材料与人工说明作为本次 checkpoint 的不可变输入，后续 Prompt 明确包含它们。

该方案利用现有状态机和持久化边界，改动集中，能提供真实审计链。采用本方案。

### 方案 C：全量任务版本编辑器

为任务、材料、验收标准和任意阶段建立版本分支，可选择任意起点执行。

它会显著扩大状态机、版本和权限面，且违反已确认的失败点恢复边界，不采用。

## 6. 用户体验设计

### 6.1 入口与布局

任务状态为 `FAILED_NEEDS_HUMAN`、`FAILED_RETRYABLE` 或 `REJECTED` 时，详情页在“执行概览”后展示一个全宽的“失败诊断与恢复”工作台。它不是嵌套卡片或仅包含两行文字的确认弹窗。

桌面端使用左右两栏：

- 左侧“诊断”：失败角色、attempt、Provider、错误分类、摘要、可操作问题清单、风险、未覆盖验收项和恢复起点。
- 右侧“补充与重试”：人工说明、文字证据、本地附件、已选证据清单、历史 checkpoint 以及创建重试动作。

窄屏时两栏按诊断、补充、历史的顺序堆叠。所有长文本换行或在固定高度区域滚动，不改变按钮、表格或阶段行的尺寸。

原始角色结果仍可访问，但放在折叠的“原始产物”区域；默认视图不再显示大段 JSON。原始产物保留 artifact ID、hash 和只读内容，便于深度排障。

### 6.2 诊断内容

后端把失败统一投影为以下稳定字段：

- 当前任务状态、失败阶段、重试起点角色、失败 `stageRunId`、失败 attempt 和 Provider。
- 人类可读的标题、摘要、建议动作和是否必须补充证据。
- 问题项数组：标题、说明、严重度、来源字段；不把 JSON path 当作主要文案。
- 风险数组、验收覆盖缺口和可用的原始产物引用。

解析器优先识别需求评审的 `decision`、`feasibility`、`missingInformation`、`risks`、`acceptanceCoverage`，并兼容 RAG 证据门禁、QA 失败类别、策略门控、AI review、provider 与通用阶段异常。无法解析的历史结果降级为明确的通用诊断，不抛异常，也不伪造细节。

对于截图中的 `REQUIREMENT_REVIEWER / NEED_INFO`，工作台应把账户不一致、错误码未确定、数据方案未收敛、缺失端点等内容呈现为逐条待确认项，而不是嵌在原始 JSON 中。

### 6.3 补充证据与重试

操作者可以输入一段人工说明，并可附加：

- 文字证据：需求澄清、接口契约、验收约束或复现结果。
- 本地支持文件：当前已允许的文本、Markdown、JSON、XML、CSV、HTML 文件。
- 图片：当前已允许的 PNG、JPEG、WebP 或 GIF。

文件先通过现有任务材料上传能力持久化；新增可选的失败阶段标记，用于说明它是为哪一个 `stageRunId` 补充。重试命令提交时携带人工说明、已选材料 ID 和前端读取时的失败阶段 ID。

对“需要人工补充”的诊断，至少需要一条非空人工说明或一项证据才可执行重试；对可重试 provider/执行异常，允许保留无补充证据的直接重试能力。后端始终重新校验，不能只依赖前端禁用态。

成功提交后，页面展示 checkpoint ID、恢复 attempt、所绑定的材料、状态和后续结果。重复点击必须复用当前活跃 checkpoint，不能产生重复 attempt。

## 7. 领域与数据设计

### 7.1 Engine 读模型

在 `engine.retry` 增加失败恢复查询用例，负责聚合 `TaskRetryPoint`、最新失败 `AgentStageRun`、对应 `RESULT_JSON` 产物、RetrievalRun、AI review 和历史 checkpoint。其职责是形成不可变的 `TaskFailureRecoverySnapshot`，不直接处理 HTTP、对象存储 SDK 或数据库实现细节。

建议值对象如下：

- `TaskFailureRecoverySnapshot`：任务、失败阶段、诊断、恢复约束和历史 checkpoint 摘要。
- `TaskFailureDiagnostic`：分类、标题、摘要、建议动作、是否需要补充。
- `TaskFailureIssue`：一条可处理的问题、风险或验收覆盖缺口。
- `TaskRetryCommand`：预期失败阶段 ID、人工说明和材料 ID 列表。
- `TaskFailureDiagnosticParser`：纯解析器，输入为有界 JSON/阶段字段，输出诊断值对象。

名称以现有包和模型为准，实施时保持公开类、record 与方法的 JavaDoc 要求。

### 7.2 Checkpoint 审计字段

扩展 `TaskRetryCheckpoint`、PostgreSQL row/mapper/store 和 `p3_task_retry_ai_review.sql`，增加以下不可变创建字段：

- `operatorNote`：操作者对本次恢复的说明。
- `evidenceMaterialIds`：本次 checkpoint 明确选中的 `TaskMaterial` ID 列表。

SQL 必须同时适配新库和已经执行过 P3 脚本的本地库：建表定义补全字段，并使用幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。现有 idempotency key、attempt 序号和状态 CAS 语义保持不变。

创建重试时，后端必须校验：任务仍可重试、解析出的失败 `stageRunId` 与请求一致、每个材料属于该任务且无重复、活跃 checkpoint 的幂等规则仍成立。状态变化或材料不属于任务返回可定位的 409/400，而不是静默忽略。

### 7.3 下一 attempt 的证据传递

`RequirementDeliveryEngine` 在读取活跃 retry checkpoint 后，解析并校验 checkpoint 绑定材料。它必须：

1. 继续把所有任务材料交给既有材料提示词、Deep RAG 和角色上下文构建流程。
2. 在失败角色及下游角色的新 Prompt 中增加显式的“本次失败恢复补充”区段，列出人工说明、材料标题、hash 和有界正文/预览。
3. 不修改成功上游角色的 Prompt、上下文包或阶段产物。
4. 任何已绑定材料缺失或越权时，以可读原因阻断本次恢复，不能让模型在未知输入上继续运行。

现有 Deep RAG 会将任务材料作为根证据写入 RetrievalRun；新 Prompt 区段确保即使角色化检索的排序发生变化，被操作者选中的恢复输入仍被当前失败角色看到。

## 8. HTTP 与前端契约

### 8.1 HTTP

新增只读接口：

`GET /admin/rd-tasks/{taskId}/failure-recovery`

它只返回结构化诊断、恢复约束和历史摘要，不触发检索、执行或重试。

保留既有 `/retry-preview` 和 `/retry-history` 作为兼容接口；扩展：

`POST /admin/rd-tasks/{taskId}/retry`

请求体支持预期失败阶段、人工说明和材料 ID。空请求体保持原有直接重试兼容性。控制器只做 request/response 转换和异常映射，诊断和状态判断在 `engine` 用例中完成。

现有文字与文件材料接口增加可选失败阶段标记，仍使用当前大小、MIME、哈希和任务归属校验。新接口与字段必须加入 Vite `/admin/*` 代理并覆盖代理测试。

### 8.2 前端

新增独立的失败恢复工作台组件，避免继续膨胀已很长的 `RdTaskDetailPage.tsx`。服务层增加恢复快照、扩展 checkpoint 和 retry command 的 TypeScript 类型。

组件在失败状态时加载恢复快照；提交时先保存新增证据，再用其返回的材料 ID 创建 checkpoint。成功后刷新任务、执行概览、材料、角色 Prompt、RAG 运行和 retry 历史。执行中禁用重复提交，失败保留未提交的表单内容。

历史 attempt 显示为紧凑时间线：失败阶段、证据数量、checkpoint 状态、创建时间和错误摘要。它不把旧 JSON 重新铺到页面上。

## 9. 状态、审计与异常规则

- 只能为 `REJECTED`、`FAILED_RETRYABLE`、`FAILED_NEEDS_HUMAN` 创建失败恢复 checkpoint。
- 旧 `AgentStageRun`、旧 checkpoint 和旧产物永不改写；重试只新增 attempt。
- 成功上游角色继续通过现有 `SUCCEEDED` 产物复用，下游从新的 pending attempt 开始。
- `RECOVERING`、新 checkpoint、绑定材料 ID、人工说明、provider/失败分类和最终 checkpoint 状态必须可由 PostgreSQL 与 HTTP 反查。
- 对空、坏格式或未知历史 JSON，诊断接口返回安全的通用问题，不泄露堆栈或凭证。
- 所有新日志仅记录 task ID、stageRun ID、checkpoint ID、材料 ID、数量、hash 和分类；不记录人工证据全文或密钥。

## 10. 验收不变量

实现必须至少证明：

1. `NEED_INFO` 的结构化缺失项不以默认大段 JSON 作为主界面内容。
2. 补充文字或文件后，checkpoint 精确保存所选材料 ID 和人工说明。
3. 重试仅创建失败角色及下游角色的新 attempt；成功上游 attempt 的数量和状态不变。
4. 新 attempt 的 Prompt 或其绑定上下文可反查到本次 checkpoint 的人工说明和选中材料。
5. 错误的失败阶段、跨任务材料、缺少必填补充或重复请求分别得到明确的 400/409 或既有幂等返回。
6. PostgreSQL DDL 连续执行两次仍成功；真实 HTTP、数据库反查和桌面/窄屏浏览器验证均留下可审查证据。

## 11. 实施约束

- 严格遵循 `AGENTS.md`、`RULE.md`、运行密钥与任务控制面经验规范。
- `engine` 只承载诊断、恢复用例和状态编排；`bootstrap` 承载 HTTP、PostgreSQL、对象存储与适配；`frontend` 仅承载展示和用户输入。
- 不新增内存真值作为生产回退；新的生产可见恢复数据必须经 PostgreSQL store/SQL 管理。
- 不提交、推送或创建 PR，除非用户另行明确要求。
