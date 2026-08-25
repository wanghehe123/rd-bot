## Why

RD-Bot 当前能够成功发布需求交付 PR，但 PR 正文的事实字段与真实角色结果协议已经漂移：生产 Coding 结果使用数组 `changedFiles`、`testCommands` 和 `testStatus`，发布适配器与部分审核测试仍读取旧的根级文本字段，导致已存在的变更和验证结果被发布为 `not reported`。PR #28 已真实暴露该问题，并同时出现空 `deliveryReview`、扁平 QA 摘要和不可访问的 localhost 证据链接，因此需要把发布正文从临时 JSON 路径拼接收敛为单一、可验证的发布视图。

## What Changes

- 在需求交付引擎中建立版本化、类型化的统一发布视图，作为确定性审核和 PR 正文渲染共同消费的事实合同。
- 从当前生产形状的 Coding/QA 阶段结果构建发布视图，并兼容阶段复用时只存在于 `multiAgentStages`、`stageResults` 或 `roleResults` 的结果；不再依赖 Coding 结果恰好停留在聚合 JSON 根上。
- 修复已成功 Coding 阶段被复用时没有恢复交付结果基底的问题，确保恢复、定界 QA 和正常执行生成相同的发布事实。
- 用确定性模板呈现需求摘要、实际变更文件、测试命令与状态、逐条验收结果、风险、审核结论和持久证据引用。
- 缺少已批准审核或关键 Coding/QA 证据时失败关闭，禁止继续发布空审核对象或 `not reported` 占位正文。
- 过滤 localhost/loopback 等仅容器或本机可访问的 URL；没有稳定公共 URL 时发布 artifact ID/相对路径，不伪造成可点击链接。
- 让 reviewer、validator、publisher 和测试夹具统一到同一生产结果协议，并增加 PR #28 形状的回归 fixture 与真实新任务验收。
- 保持现有 publication `operationId`、candidate-patch 标记、远端查询/重放边界和 GitHub body pass-through 行为不变。
- 非目标：本 change 不自动回写历史 PR，不在创建 PR 后增加第二次远端正文更新，也不新增数据库 schema 或改变 GitHub/PostgreSQL 外部副作用协议。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `requirement/delivery-platform`: 增加统一发布视图、完整可读 PR 正文、恢复路径一致性、证据链接安全和发布前失败关闭要求。

## Impact

- 主要影响 `engine` 的阶段结果聚合、确定性审核和 PR 发布命令模型，以及 `bootstrap` 的 PR 正文渲染适配器；GitHub 低层适配器继续原样转发正文。
- 预计涉及 `RequirementAgentStageOrchestrator`、`RequirementDeliveryReviewer`、`RequirementDeliveryEngine`、`RequirementPullRequestPublishCommand`、`EngineRequirementPullRequestPublisherAdapter` 及其聚焦测试。
- 不改变外部 HTTP API、数据库表、消息协议、任务状态机或现有 publication ledger 状态。
- 历史资料仅作为上下文：`docs/superpowers/specs/2026-08-13-requirement-publication-preflight-and-retry-provenance-spec.md` 经 `docs/openspec/historical-spec-provenance-audit.md` 标为需要以当前代码和测试重新验证的实施声明；本 change 不把其历史验收直接提升为当前要求。
- 当前代码锚点：`RequirementAgentStageOrchestrator` 的 Coding/QA 输出合同与阶段复用路径、`StructuredResultValidator` 的数组字段校验、`RequirementDeliveryReviewer` 的 Coding 证据检查、`EngineRequirementPullRequestPublisherAdapter` 的正文拼接与嵌套 QA 查找、`GitHubCodePlatformAdapter` 的正文透传。
- 本轮已运行：`./mvnw -q -pl bootstrap -am -Dtest=EngineRequirementPullRequestPublisherAdapterTest,GitHubCodePlatformAdapterTest,RequirementDeliveryReviewerTest,StructuredResultValidatorTest,RequirementDeliveryEngineTest#shouldExecuteRequirementTaskAndCommitPullRequest -Dsurefire.failIfNoSpecifiedTests=false test`，退出码为 0；该结果证明现有测试基线通过，但现有夹具未覆盖生产数组/嵌套结果形状。
