## ADDED Requirements

### Requirement: 发布前形成统一且可验证的交付视图

系统 SHALL 在需求交付结果进入 PR 发布前形成版本化的统一发布视图。该视图 MUST 从已持久化且成功的 Coding 与 QA 角色结果中提取需求摘要、实际变更文件、真实测试命令及状态、风险、逐条验收结果和持久证据引用；确定性审核与 PR 正文生成 MUST 消费同一份发布视图，MUST NOT 各自解释不同的临时 JSON 路径。

#### Scenario: 正常执行使用生产结果形状

- **WHEN** Coding 结果以数组形式提供 `changedFiles`、`testCommands`，并提供 `testStatus`，QA 结果提供逐条 `acceptanceResults`
- **THEN** 系统把这些字段完整规范化到统一发布视图，审核与 PR 正文中的文件、命令、状态和验收条目保持一致

#### Scenario: 已成功 Coding 阶段在恢复流程中被复用

- **WHEN** 恢复、定界 QA 或重试流程复用一个已成功的 Coding 阶段，且 Coding 结果仅存在于已持久化阶段结果中
- **THEN** 系统从独立于截断审计预览的持久发布事实投影恢复完整 Coding 发布事实，并生成与正常连续执行等价的统一发布视图

#### Scenario: 同一事实存在于兼容位置

- **WHEN** 当前或兼容结果把角色事实放在聚合根对象、`multiAgentStages`、`stageResults` 或 `roleResults` 中
- **THEN** 系统按确定且有测试覆盖的优先级解析一次，并在检测到互相冲突的关键事实时阻断发布而不是静默选择

#### Scenario: 当前角色失败但兼容位置仍有旧成功结果

- **WHEN** 最高优先级阶段容器明确记录任一必需角色失败，而低优先级兼容位置仍保留旧成功结果
- **THEN** 系统以当前失败为准阻断发布，MUST NOT 用旧成功结果恢复该角色

#### Scenario: 聚合根明确声明失败

- **WHEN** 根对象显式提供 `multiAgentStatus` 且值不是文本 `SUCCESS`，即使各阶段条目分别声称成功
- **THEN** 系统以明确的聚合失败为准阻断审核与发布；旧兼容形状缺少该字段时仍按角色事实校验

### Requirement: PR 正文确定性呈现交付事实

系统 SHALL 从已通过审核的统一发布视图确定性生成 PR 正文。正文 MUST 使用人类可读的独立章节呈现需求摘要、实际变更、验证结果、逐条验收、风险、审核结论和 provenance；关键事实 MUST NOT 被空 JSON、单段压缩文本或 `not reported` 占位替代。

#### Scenario: 完整交付生成可读正文

- **WHEN** 发布视图包含有效的 Coding、QA 和已批准审核事实
- **THEN** PR 正文逐项列出实际变更文件、执行过的测试命令与状态、每条验收标准的结果和证据引用，并显示明确的审核结论

#### Scenario: Agent 提供候选正文

- **WHEN** Coding Agent 提供非空候选 `prBody`
- **THEN** 系统只把它作为受控的叙述性摘要输入，关键变更、验证、验收、审核和 provenance 章节仍由统一发布视图确定性生成

#### Scenario: 发布到代码平台

- **WHEN** 系统已经生成并验证最终 PR 正文
- **THEN** 代码平台适配器原样提交该正文，并保留 `taskId`、`operationId` 和 candidate-patch 身份标记

### Requirement: 缺少关键交付事实时发布失败关闭

系统 MUST 在远端 PR 写入前验证统一发布视图。缺少已批准的确定性审核、实际变更文件、Coding 测试状态、必要测试命令、QA 通过状态、逐条验收结果或强制证据引用时，系统 MUST 阻断发布并返回可诊断原因；系统 MUST NOT 发布空 `deliveryReview`、虚构的成功结论或关键字段为 `not reported` 的正文。

#### Scenario: 缺少已批准审核

- **WHEN** 正常发布或 AI review 重试路径没有可验证的已批准确定性审核结果
- **THEN** 系统在调用远端代码平台前失败关闭，并指出审核结果缺失或无效

#### Scenario: 审核不属于当前任务或当前发布事实

- **WHEN** 已批准审核的 `taskId`、reviewer 类型或规范化发布事实 SHA-256 与当前统一发布视图不匹配
- **THEN** 系统在 AI review 调用、分支推送和 PR 创建前失败关闭，MUST NOT 复用该审核

#### Scenario: Coding 事实不完整

- **WHEN** 成功交付结果没有非空实际变更文件，或测试状态要求命令但没有真实测试命令
- **THEN** 系统阻断发布并指出缺失的 Coding 证据字段

#### Scenario: QA 事实不完整

- **WHEN** QA 声明通过，但验收结果缺少标准、命令、状态、退出码或强制证据引用
- **THEN** 系统阻断发布并指出无效的验收条目

### Requirement: PR 仅引用持久且对读者有意义的证据

系统 SHALL 区分持久证据标识与仅在 Agent、容器或宿主本机有效的运行时 URL。PR 正文 MUST NOT 把 localhost、loopback 或其它仅本机可访问地址发布为读者可用链接；没有稳定公共 URL 时，系统 SHALL 显示持久 artifact ID 或相对证据路径。

#### Scenario: 结果包含 localhost URL

- **WHEN** 角色结果或证据元数据包含 `localhost`、`127.0.0.1`、`::1` 或等价 loopback URL
- **THEN** 系统不把该 URL 渲染为 PR 证据链接，并保留对应的持久 artifact ID 或相对路径（若存在）

#### Scenario: 结果包含稳定证据引用

- **WHEN** 验收结果引用持久 artifact ID、相对证据路径或经配置确认可访问的公共 URL
- **THEN** PR 正文按验收条目保留该引用，且正文中的验收数量与发布视图一致

### Requirement: PR 正文优化不得扩大远端副作用协议

系统 SHALL 保持现有 PR publication 身份、ledger 状态和远端查询/重放边界。首版正文优化 MUST NOT 自动回写历史 PR，MUST NOT 为获取远端 diff 统计而在创建 PR 后新增第二次正文更新，也 MUST NOT 以正文内容变化替代现有 `operationId` 和 candidate-patch 身份。

#### Scenario: 远端已存在匹配 PR

- **WHEN** publication reconcile 找到 task、operation 和 candidate-patch 标记匹配的既有 PR
- **THEN** 系统继续按现有 ledger/reconcile 规则复用该 PR，而不是因新正文模板重新创建或盲目更新 PR

#### Scenario: 历史 PR 使用旧正文模板

- **WHEN** change 上线前已经存在使用旧正文模板的 PR
- **THEN** 系统不自动修改该 PR；正文优化仅应用于后续按现有 publication 协议创建的新 PR
