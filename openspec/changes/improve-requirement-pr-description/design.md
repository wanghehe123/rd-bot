## Context

动机见 `proposal.md`。当前真实链路是 `RequirementAgentStageOrchestrator` 生成并聚合角色结果，`RequirementDeliveryEngine` 执行确定性/AI 审核并构造 `RequirementPullRequestPublishCommand`，`EngineRequirementPullRequestPublisherAdapter` 从任意 `deliveryResultJson` 临时提取字段和拼接 Markdown，最后由 `GitHubCodePlatformAdapter` 原样提交正文。

当前生产 Coding 合同与 `StructuredResultValidator` 要求 `changedFiles[]`、`testCommands[]`、`testStatus`，QA 合同提供带命令、状态、退出码和证据引用的 `acceptanceResults[]`。发布适配器却只读取根级文本 `changedFiles`、`testSummary`，只为 QA 实现嵌套阶段查找；`RequirementDeliveryReviewer` 和相关测试夹具也保留了旧文本形状。阶段恢复还可能复用已成功 Coding 结果而不恢复根级交付结果基底。由此，当前各消费者对同一 JSON 形成了不同事实解释。

历史资料 `docs/superpowers/specs/2026-08-13-requirement-publication-preflight-and-retry-provenance-spec.md` 在 `docs/openspec/historical-spec-provenance-audit.md` 中属于需用当前代码/测试复核的实施声明。本设计只保留本轮由 publication adapter、ledger/reconcile 代码和聚焦测试重新确认的 operation/candidate-patch 边界；历史真实任务或文档日期不作为本 change 的当前实现证明。

本轮基线命令：

```bash
./mvnw -q -pl bootstrap -am -Dtest=EngineRequirementPullRequestPublisherAdapterTest,GitHubCodePlatformAdapterTest,RequirementDeliveryReviewerTest,StructuredResultValidatorTest,RequirementDeliveryEngineTest#shouldExecuteRequirementTaskAndCommitPullRequest -Dsurefire.failIfNoSpecifiedTests=false test
```

命令退出码为 0。它确认旧基线仍通过，但现有 publisher/reviewer fixture 没有覆盖生产数组和嵌套角色结果。

## Goals / Non-Goals

**Goals:**

- 让 engine 只解析一次交付结果，并向审核器、Markdown renderer 和发布端口暴露同一份不可变事实视图。
- 让正常执行、阶段复用、定界 QA、确定性审核重试和 AI review 重试对相同已持久化结果生成等价发布事实。
- 在任何 GitHub 写入前验证关键 Coding、QA、review 和 evidence 字段，返回字段级诊断。
- 让正文具有稳定章节和排序，便于人读、测试和后续版本演进。
- 保持现有模块依赖方向、publication ledger、operation identity、candidate-patch marker 和 GitHub pass-through。

**Non-Goals:**

- 不让 LLM 自由生成最终事实章节；Agent `prBody` 只作为可选叙述摘要。
- 不增加数据库列、消息字段或公开 HTTP API。
- 不自动更新旧 PR，不增加 post-create body update，不查询远端 diff 统计来补 `+/-` 行数。
- 不重构 publication ledger、分支推送、PR reconcile 或未知远端结果协议。
- 不为任意递归 JSON URL 建立新的公共 artifact 网关。

## Decisions

### 1. engine 拥有统一发布视图与最终 Markdown

在 `engine.requirement.model` 增加不可变 `RequirementDeliveryPublicationView`，使用显式子记录表达：

- `schemaVersion`，首版固定为 `1`；
- `summary` 与可选的受控 `agentNarrative`；
- `changedFiles`；
- `testCommands`、`testStatus`、`riskLevel`；
- QA `acceptanceResults`，每项包含 criteria/scope/command/status/exitCode/logArtifactId/evidenceArtifactIds；
- `evidenceManifestArtifactId`；
- 确定性 `deliveryReview`；
- 规范化后的持久证据引用。

新增 `RequirementDeliveryPublicationViewAssembler` 在 engine 内解析聚合 JSON 并返回视图或字段级 validation errors；新增 `RequirementPullRequestBodyRenderer` 从已批准视图、taskId 和 operationId 生成最终 Markdown。`RequirementPullRequestPublishCommand` 继续携带 `deliveryResultJson` 供现有 publication identity/metadata 使用，同时新增已经生成并验证的 `pullRequestBody`。bootstrap publisher 不再解析角色 JSON，只校验正文非空并把正文交给代码平台端口。

发布视图提供排除 `deliveryReview` 自身的规范化 `publicationFactsHash`。确定性审核落库时必须写入精确 taskId、固定 reviewer 类型和该 SHA-256；AI review 重试、publication 重试及 renderer 均重新计算并比对三者，防止旧审核授权已变化的 Coding/QA 事实。

选择该边界是因为交付事实、审核前置和正文内容属于 engine 业务合同；bootstrap 只应适配 GitHub/GitLab 等外部平台。保留 raw JSON 是兼容现有 operation/hash 逻辑，不把它继续当作展示接口。

**替代方案：**只增强 `EngineRequirementPullRequestPublisherAdapter` 的 JSON 查找最省代码，但 reviewer、恢复和 publisher 仍会拥有不同解释，下一次 schema 演进会再次漂移，因此不采用。

### 2. 角色阶段结果优先，聚合根字段仅作受限兼容

Assembler 按以下顺序定位每个角色的一份成功结果：

1. 当前 `multiAgentStages` 中对应角色的成功 `resultJson`；
2. 兼容 `stageResults`；
3. 兼容 `roleResults.CODING_AGENT` 与 `roleResults.QA_AGENT`；
4. 仅对 Coding 使用聚合根上的生产字段作为最后 fallback。

每个容器都必须解析为对象。相同关键事实出现在多个位置时，先规范化再比较；非空值冲突、同一容器出现多个成功角色结果或角色结果解析失败均返回 validation error，不静默覆盖。`changedFiles` 与 `testCommands` 的当前合同只接受非空字符串数组；历史单字符串 `changedFiles` 仅允许作为一个文件路径兼容，旧 `testSummary` 不得冒充真实 `testCommands`。

聚合根的 `multiAgentStatus` 允许在旧兼容形状中缺失；一旦显式出现则必须是文本 `SUCCESS`。不得仅凭四个成功 stage wrapper 覆盖根上明确的失败聚合状态。

同时修复 `RequirementAgentStageOrchestrator`：复用已成功 `CODING_AGENT` 时，从该持久阶段的完整发布事实投影恢复 `deliveryResultJson` 基底，再继续合并 QA 与 stage wrappers。Assembler 的多位置解析仍保留，作为恢复和旧快照的兼容防线，而不是替代聚合修复。

由于 `RESULT_JSON.contentPreview` 有 20,000 字符审计预览上限，Coding 成功时另存紧凑、完整的 `PUBLICATION_FACTS_JSON` 产物，只包含 summary/narrative、changedFiles、testCommands、testStatus 和 riskLevel 等发布字段。恢复路径优先读取该不可变投影，不把截断预览误称为完整结果。若最高优先级容器明确记录任一必需角色失败，低优先级兼容位置不得将其恢复为成功。

**替代方案：**以根字段优先可少改 orchestrator，但会让旧/残缺根结果覆盖较新的持久阶段事实，不采用。

### 3. 审核和发布使用同一视图的两阶段不可变流程

流程调整为：

```text
persisted role results
        |
        v
PublicationViewAssembler (review absent)
        |
        v
RequirementDeliveryReviewer
        |
        v approved review attached as a new immutable view
RequirementPullRequestBodyRenderer
        |
        v
RequirementPullRequestPublisherPort -> GitHub adapter
```

正常路径先组装 Coding/QA 事实视图，再由 reviewer 校验并产生 approved review，最后创建含 review 的新视图。AI review retry 的 `skipDeterministicReview` 路径必须从结果中读取既有 review，并确认 `approved=true`；缺失、空对象、rejected 或不可解析 review 直接失败关闭。renderer 只接受已批准视图。

`RequirementDeliveryReviewer` 不再自行解析 raw JSON，也不再把非空 `prBody` 当成 Coding 交付证据。它校验的 production fields 与 assembler/validator 一致。

**替代方案：**缺 review 时发布警告正文可以提高完成率，但会把未经审核结果写入远端并延续 PR #28 的空审核问题，不采用。

### 4. 最终正文由固定模板生成

renderer 使用固定顺序生成：

1. `Summary`：任务标题/交付摘要和受控 Agent narrative；
2. `Changes`：去重、稳定排序的实际文件列表；
3. `Verification`：真实命令、Coding test status 和 risk level；
4. `Acceptance`：逐条 criteria/scope/status/command/exitCode/evidence；
5. `Delivery Review`：明确 approved、reviewer 类型和 reason；
6. `Evidence`：manifest、log 和 evidence artifact 引用；
7. `RD-Bot Provenance`：taskId、operationId 与现有 marker 内容。

所有动态文本执行 Markdown table/inline-code 转义。数组保持稳定、可预测的顺序；文件和证据引用去重。任何关键章节为空在 assembler/reviewer 阶段已经失败，renderer 不提供 `not reported` fallback。Agent `prBody` 不得覆盖确定性章节，只可作为 `Summary` 下的 narrative。

**替代方案：**继续把 Agent `prBody` 作为正文基底会保留模型波动、重复章节和事实冲突，不采用。

### 5. 证据只从显式 QA 字段收集

删除正文路径对任意 JSON 文本的递归 URL 抓取。证据来源限定为 `acceptanceResults[].logArtifactId`、`acceptanceResults[].evidenceArtifactIds` 和 `evidenceManifestArtifactId`。确定性审核要求 manifest、log 以及每项 evidence 至少包含一个可持久引用，loopback/runtime-only URL 不得充当必需证据。相对路径与 artifact ID 以代码格式呈现；对大小写不敏感的 `http`/`https` URI 解析 host，按浏览器兼容的数值 IPv4 规则识别十进制、八进制、十六进制和 1–4 段缩写，拒绝整个 127/8、未指定地址、IPv4-mapped IPv6 与等价 IPv6 loopback。非 URI 字符串不被伪造成链接。

首版不新增公共 base URL 配置。若已有值本身是非 loopback 的完整 `http`/`https` URL，可以保留为链接；是否可从外网访问仍是部署责任，真实 canary 必须检查。

**替代方案：**维护递归正则 blocklist 无法区分运行时 URL 与持久证据，而且会继续把无关文本变成链接，不采用。

### 6. 外部副作用边界保持不变

`RequirementOperationId`、publication ledger 状态、branch/PR remote marker 和 `GitHubCodePlatformAdapter` 的 body pass-through 不变。新 renderer 在首次远端写入前完成；reconcile 找到匹配 PR 时沿用现有复用逻辑，不因模板版本变化创建或更新 PR。正文模板版本不参与 operation identity。

这避免把文案优化升级为跨 GitHub/PostgreSQL 的新一致性协议。若未来要自动更新正文或补远端 diff 统计，必须建立新的外部操作身份、sent boundary 和 reconcile 设计，另开 change。

## Risks / Trade-offs

- [严格失败关闭会让部分旧任务或旧 AI review 重试不能继续发布] → 返回精确缺失字段和来源路径；只对新任务执行真实 canary，旧 PR 不自动迁移。若必须恢复旧任务，先补齐已持久化角色事实而不是放宽发布合同。
- [聚合根与阶段结果冲突可能暴露以前被忽略的数据问题] → 规范化后比较并记录冲突来源；禁止 last-write-wins。为 root/nested/reused/conflict 分别建立 fixture。
- [Markdown 中的竖线、换行或反引号破坏表格] → renderer 集中转义并用 golden tests 固定最终正文。
- [大量验收条目使正文变长] → 首版保留全部验收条目，不引入自定义截断或新的长度阈值；若代码平台按既有 API 限制拒绝正文，沿用当前 publication failure/reconcile 处理，并在后续独立 change 设计摘要与外部证据页。
- [public URL 非 loopback 但仍不可被 PR 读者访问] → canary 逐个点击验证；首版优先显示 artifact ID/相对路径，不声称所有非 loopback URL 可公开访问。
- [修改发布命令模型影响 bootstrap 测试与兼容构造器] → 保留 raw JSON 与现有 operationId 字段，更新所有构造点，并在编译与聚焦测试中验证接口一致性。

## Migration Plan

1. 先以失败测试引入 production-shaped、nested、reused、conflict、missing-review 和 localhost fixtures。
2. 在 engine 增加发布视图、assembler、validator/renderer，并让 reviewer 消费视图。
3. 修复 orchestrator 的 reused Coding 基底恢复，增加正常执行与恢复结果等价测试。
4. 扩展 publish command 传递已验证正文，简化 bootstrap publisher，保持 GitHub adapter 和 publication ledger 不变。
5. 运行 engine/exec/bootstrap 聚焦测试、模块测试、`openspec validate --all --strict`，确认无 schema/API/DB 迁移。
6. 部署后使用一个全新的 RD-Bot 需求任务创建 canary PR，检查所有正文章节、证据引用、operation/candidate-patch 标记和 ledger `COMMITTED`；不复用或修改 PR #28。
7. 若 canary 在远端写入前失败，可回滚应用版本；若已经创建新 PR，则保留该 PR 作为证据并按现有 reconcile/ledger 处理，禁止删除或盲目重发。

## Open Questions

无。是否支持创建后更新正文、远端 diff 统计和历史 PR 迁移均已明确留到独立 change。
