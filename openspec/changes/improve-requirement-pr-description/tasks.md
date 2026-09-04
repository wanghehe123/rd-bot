## 1. 统一发布视图与兼容解析

- [x] 1.1 在 `engine/src/test/resources/requirement-publication/pr-28-production-result.json` 增加脱敏的 PR #28 形状 fixture，并在新建的 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryPublicationViewAssemblerTest.java` 先写失败测试，覆盖生产数组字段、`multiAgentStages`/`stageResults`/`roleResults`/根 fallback、单文件旧标量兼容、重复角色、解析失败和关键事实冲突；运行 `./mvnw -q -pl engine -Dtest=RequirementDeliveryPublicationViewAssemblerTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认因类型/assembler 尚不存在而失败。
- [x] 1.2 在 `engine/src/main/java/com/wish/rd/engine/requirement/model/RequirementDeliveryPublicationView.java` 定义 schemaVersion=1 的不可变发布视图及 Coding、QA、acceptance、review 子记录，并在 `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryPublicationViewAssembler.java` 实现阶段优先级、生产数组校验、受限旧标量兼容、规范化比较和字段级错误；重跑 `RequirementDeliveryPublicationViewAssemblerTest`，确认所有 fixture 通过且冲突/缺字段失败关闭。
- [x] 1.3 更新 `exec/src/test/java/com/wish/rd/exec/repair/result/StructuredResultValidatorTest.java`，明确 `changedFiles[]`、`testCommands[]`、`testStatus` 与发布视图生产合同一致，并运行 `./mvnw -q -pl exec -Dtest=StructuredResultValidatorTest -Dsurefire.failIfNoSpecifiedTests=false test`；不得放宽 `StructuredResultValidator` 接受旧 `testSummary` 代替命令。

## 2. 确定性审核与正文渲染

- [x] 2.1 先把 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewerTest.java` 的旧文本 Coding fixture 改为生产数组形状，并增加空变更、缺测试命令、QA 条目缺字段和未通过 QA 的失败测试；运行 `./mvnw -q -pl engine -Dtest=RequirementDeliveryReviewerTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认旧 reviewer 对至少一个生产形状判断错误。
- [x] 2.2 修改 `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryReviewer.java`，让 reviewer 消费 `RequirementDeliveryPublicationView` 而不是重新解析 raw JSON，移除以非空 `prBody`/文本 `changedFiles`/`testSummary` 代替交付证据的判断；重跑 `RequirementDeliveryReviewerTest`，确认只接受完整 Coding/QA 事实并返回字段级拒绝原因。
- [x] 2.3 新建 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementPullRequestBodyRendererTest.java`，先写 PR #28 golden 输出、Markdown 特殊字符转义、稳定去重排序、逐条验收、明确 review、provenance、localhost/127.0.0.1/IPv6 loopback 过滤和 artifact ID 保留测试；运行 `./mvnw -q -pl engine -Dtest=RequirementPullRequestBodyRendererTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认 renderer 尚不存在时失败。
- [x] 2.4 新建 `engine/src/main/java/com/wish/rd/engine/requirement/RequirementPullRequestBodyRenderer.java`，按 `Summary`、`Changes`、`Verification`、`Acceptance`、`Delivery Review`、`Evidence`、`RD-Bot Provenance` 固定章节生成 Markdown；只从显式 QA artifact 字段收集证据，Agent `prBody` 仅作为 narrative，关键字段不提供 `not reported` fallback；重跑 renderer 测试并检查 golden 正文不含空 review 或 loopback 链接。

## 3. 阶段复用与审核恢复一致性

- [x] 3.1 在 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestratorTest.java` 先增加失败测试：已成功 `CODING_AGENT` 被复用且 QA 新执行时，最终 `deliveryResultJson` 仍包含原 Coding `changedFiles[]`、`testCommands[]`、`testStatus`、risk 和 narrative，并与连续执行的发布视图等价；运行该测试确认当前基底丢失。
- [x] 3.2 修改 `engine/src/main/java/com/wish/rd/engine/requirement/RequirementAgentStageOrchestrator.java`，为成功 Coding 阶段持久化不可变 `PUBLICATION_FACTS_JSON` 投影；复用时优先从该投影恢复完整 `deliveryResultJson` 基底，再兼容读取未截断的旧 `RESULT_JSON`，并合并后续 QA 和 `multiAgentStages`；重跑 `RequirementAgentStageOrchestratorTest`，确认不重跑 Coding 且发布事实不丢失。
- [x] 3.3 在 `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java` 与 AI review gate 测试中先增加正常发布、确定性审核重试和 AI review retry fixture：缺失/空/rejected、taskId 不匹配或事实哈希过期的 `deliveryReview` 均不得调用 publisher；运行聚焦测试确认旧 `skipDeterministicReview` 路径会把未绑定审核传向发布。
- [x] 3.4 修改 `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`，在 review 前组装统一视图并计算确定性 `publicationFactsHash`，正常路径附加绑定 taskId 与事实哈希的新 approved review，AI review retry 只复用同任务且哈希一致的 approved review；assembler/reviewer 错误必须在远端调用前收敛为可诊断交付失败，重跑聚焦测试验证 publisher 与 AI reviewer 均未被错误调用。

## 4. 发布命令与 bootstrap 适配器收口

- [x] 4.1 先更新 `bootstrap/src/test/java/com/wish/rd/bootstrap/executor/EngineRequirementPullRequestPublisherAdapterTest.java`：构造 production-shaped raw JSON 与显式最终正文，断言 adapter 只使用 `pullRequestBody`，正文为空时失败，raw JSON 中的旧标量、嵌套字段或任意 URL 均不能改变最终正文；运行 `./mvnw -q -pl bootstrap -am -Dtest=EngineRequirementPullRequestPublisherAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认当前 adapter 仍自行拼接正文而失败。
- [x] 4.2 扩展 `engine/src/main/java/com/wish/rd/engine/requirement/model/RequirementPullRequestPublishCommand.java` 以携带经验证的 `pullRequestBody`，保留 `deliveryResultJson` 与 `operationId`；更新所有构造点和测试 fixture，并让 `RequirementDeliveryEngine` 在 operationId 确定后调用 renderer 生成正文。
- [x] 4.3 简化 `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineRequirementPullRequestPublisherAdapter.java`：删除 ad hoc 根字段读取、nested QA 特判、空 review JSON 序列化和递归 URL 抓取，只验证并透传命令中的最终正文；重跑 `EngineRequirementPullRequestPublisherAdapterTest`，确认 PR #28 fixture 显示真实文件、命令、逐条验收和 approved review。
- [x] 4.4 更新 `bootstrap/src/test/java/com/wish/rd/bootstrap/GitHubCodePlatformAdapterTest.java` 与 publication 相关 engine 测试，断言 `GitHubCodePlatformAdapter` 继续原样发送正文，task/operation/candidate-patch marker、open-PR reuse 和既有 metadata 不变；运行 `./mvnw -q -pl bootstrap -am -Dtest=GitHubCodePlatformAdapterTest,EngineRequirementPullRequestPublisherAdapterTest,RequirementDeliveryEngineTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## 5. 回归验证与规格一致性

- [x] 5.1 运行聚焦合同测试：`./mvnw -q -pl bootstrap -am -Dtest=RequirementDeliveryPublicationViewAssemblerTest,RequirementPullRequestBodyRendererTest,RequirementAgentStageOrchestratorTest,RequirementDeliveryReviewerTest,RequirementDeliveryEngineTest,RequirementDeliveryResumeFromCheckpointTest,RequirementDeliveryAiReviewGateTest,EngineRequirementPullRequestPublisherAdapterTest,EngineRequirementPublicationReconcileAdapterTest,GitHubCodePlatformAdapterTest,StructuredResultValidatorTest,FeishuImMessageControllerTest#shouldCreateRequirementTaskFromMentionedText -Dsurefire.failIfNoSpecifiedTests=false test`；记录各模块实际测试数与零失败证据。
- [x] 5.2 运行后端模块回归 `./mvnw -q -pl bootstrap -am test`，确认 engine/exec/rag/bootstrap 全部通过；若存在与本 change 无关的既有失败，记录精确测试和基线证据，不得通过放宽新发布合同使其变绿。
- [x] 5.3 运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`、`git diff --check` 和 `git status --short`，确认 delta、design、tasks 与实际实现一致，`RULE.md` 未删除/改名/清空，且没有数据库 migration、公开 API、publication ledger 或 GitHub 二次更新的越界改动。

## 6. 真实 canary 验收（需显式外部写入授权）

- [ ] 6.1 在获得用户对新 GitHub PR 外部写入的明确授权、确认目标项目和运行配置后，使用一个全新的 RD-Bot 需求任务执行真实四角色交付；不得复用、修改、关闭或删除 PR #28，也不得盲目重放任何 `UNKNOWN_REMOTE_RESULT` operation。
- [ ] 6.2 记录 canary 的 taskId、operationId、candidate-patch identity、PR URL、publication ledger 终态和四角色 attempt 终态；只有 ledger 为 `COMMITTED`、角色结果成功且远端 PR marker 匹配时，才把远端发布视为成功。
- [ ] 6.3 人工打开 canary PR，核对 Summary、Changes、Verification、逐条 Acceptance、Delivery Review、Evidence 和 Provenance；逐个检查公开链接可访问、loopback URL 不存在、正文没有 `{}`/`not reported`，并在 `docs/superpowers/specs/2026-08-23-requirement-pr-description-implementation-acceptance.md` 记录 task/operation/PR/ledger/角色终态、实际命令和证据路径后再申请 verify/archive。
