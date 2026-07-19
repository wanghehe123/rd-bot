# RD-Bot 评测数据质量与证据链修复计划

**目标：** 把当前“能执行并生成分数”的评测，升级为能证明 RetrievalRun、角色上下文和交付产物真实、相关、可追溯、可复现的质量门禁。

**输入依据：** `docs/qa/2026-07-13-existing-evaluation-data-audit.md`。

**实施原则：** 先修证据真值，再修评分；先补失败测试，再改实现；fixture smoke 与真实质量基线彻底分开；不回填或篡改历史终态 Run。

## 1. 目标模型

### 1.1 三层结论

每个评测 Run 明确暴露三个互不替代的结论：

1. `executionStatus`：评测程序是否完成，沿用 `SUCCEEDED/FAILED/CANCELLED`。
2. `gateStatus`：`PASSED/NOT_PASSED/INCOMPLETE`，由确定性门禁和 Judge 完整性共同决定。
3. `judgeStatus`：`NOT_REQUESTED/AVAILABLE/FAILED/PARTIAL`。

前端不得把 `executionStatus=SUCCEEDED` 翻译为“评测成功”；统一显示“执行完成”，并在同一视觉层级显示门禁结果。

### 1.2 检索真值链

```text
RetrievalRun
  -> selected evidence artifacts (id/uri/hash/source/scope/relevance)
  -> immutable RoleContextPackage(retrievalRunId)
  -> AgentStageRun(contextPackageId + retrievalRunId)
  -> result artifact citations
  -> evaluation snapshot
  -> deterministic metrics + Judge XML
```

任何一段缺失时，对应 consumer 不得计入“完整成功检索”。

## 2. Phase 0：先锁定失败测试

### 修改文件

- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementContextRetrievalRecorderTest.java`
- `engine/src/test/java/com/wish/rd/engine/evaluation/taskrun/TaskRunEvaluationSnapshotCollectorTest.java`
- `bootstrap/src/test/java/com/wish/rd/bootstrap/persistence/impl/PostgresRoleContextPackageStoreTest.java`
- `scripts/evaluation/tests/test_rd_eval_lib.py`

### 必须先失败的测试

1. 只有一个需求材料、没有仓库/知识库证据时，`CODING_AGENT` 和 `QA_AGENT` 不得被判为 `SUFFICIENT`。
2. 0 分历史经验不得进入候选结果；跨项目经验不得返回。
3. 四角色 evidence 集合完全相同时，`role_specific_evidence_coverage_rate=0`。
4. 成功 RetrievalRun 没有 selected evidence hash 或 RoleContextPackage 绑定时，`retrieval_run_integrity_rate=0`。
5. Task-Run XML 必须包含实际 selected evidence 的 preview、URI、hash 和 consumer；不能只含计数。
6. 没有检索正文时，Judge 的 `context_precision/context_recall/faithfulness` 必须是 `SKIPPED` 或硬失败，不能 PASS。
7. `artifact://not-found` 不得让 `real_command_execution_rate` 通过。
8. OpenAI Judge 被要求但调用失败时，`gateStatus=INCOMPLETE` 且 `overallPassed=false`。
9. MiniMax 直接成功阶段必须有一条标准 ProviderAttempt 审计记录。

## 3. Phase 1：替换 Requirement RetrievalRun 空壳实现

### 3.1 编排返回真实 RetrievalOutcome

修改：

- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementContextRetrievalRecorder.java`
- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- `engine/src/main/java/com/wish/rd/engine/retrieval/DeepRetrievalOrchestrator.java`

动作：

1. 将 `RequirementContextRetrievalRecorder` 从“创建成功记录”改为对 `DeepRetrievalOrchestrator` 的需求链路适配器；若需兼容旧类名，其方法必须返回 `RetrievalOutcome`，不能为 `void`。
2. `RetrievalOutcome` 至少包含 `runId/status/consumer/role/stageRunId/selectedEvidence/qualityDecision/qualityReportArtifactId/omissions`。
3. `RequirementDeliveryEngine` 必须先创建或取得当前角色的 `AgentStageRun`，再以真实 `stageRunId` 发起角色 RetrievalRun。
4. 基础上下文和角色上下文按阶段即时检索；禁止在任务开始时用同一 materials 列表一次性复制 5 个成功 Run。
5. 任务材料可作为根证据，但不能自动满足代码、接口、日志和 QA 关键证据门禁。

### 3.2 成功门禁

一个 Requirement/Role RetrievalRun 只有同时满足以下条件才可成功：

- expected consumer 与 role 匹配；
- scope 中至少包含任务材料，配置了项目时还必须包含该项目仓库/知识库 ID；
- selected evidence 非空，每条有 URI、hash、source type；
- 角色关键证据满足：
  - Reviewer：需求正文、验收标准、项目/仓库基本信息；
  - Architect：接口/数据/代码结构证据；
  - Coding：目标文件/符号/约束证据；
  - QA：验收标准、测试入口、接口或页面证据；
- 无 scope violation；
- 质量报告明确列出缺失、选择和省略原因。

只有任务材料而无角色关键证据时，应进入 `WAITING_INPUT` 或继续规划，不得统一 `SUFFICIENT`。

## 4. Phase 2：修复历史经验检索污染

### 修改文件

- `engine/src/main/java/com/wish/rd/engine/agent/WorkflowExperienceStore.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresWorkflowExperienceStore.java`
- `bootstrap/src/main/resources/sql/postgres/<new-experience-scope-migration>.sql`
- 新增 Workflow Experience 检索通道适配器及测试。

### 数据模型补充

为经验条目补充：

- `projectId`
- `repositoryFingerprint`
- `intentId/tags`
- `sourceRevision`
- `evidenceQuality`
- `applicableRoles`

### 检索规则

1. 项目和仓库先做硬范围过滤；无跨项目授权时禁止跨项目返回。
2. 历史经验作为独立 channel 进入融合，不再先转成普通 `TaskMaterial` 混入所有角色。
3. 使用项目现有 BM25/vector/RRF 能力；中文需使用可用 tokenizer，不能仅按空白分词。
4. 设置最小相关性阈值；0 分候选必须丢弃。
5. 按角色限制经验类型，例如 QA 优先 `QA_REPORT/DELIVERY_REPORT`，Architect 优先 `TECHNICAL_DESIGN`。
6. 每个来源任务最多 1 条，同一 content hash 去重，历史经验占最终证据不超过 30%。
7. 经验只能作为查询线索；关键结论仍需当前项目的代码、知识库或验收证据支持。

## 5. Phase 3：建立 RoleContextPackage 与 RetrievalRun 的强绑定

### 修改文件

- `rag/src/main/java/com/wish/rd/rag/context/model/RoleContextPackage.java`
- `rag/src/main/java/com/wish/rd/rag/context/RoleContextBuilder.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdRoleContextPackageRow.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdRoleContextPackageMapper.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRoleContextPackageStore.java`
- `bootstrap/src/main/resources/sql/postgres/<new-role-context-binding-migration>.sql`

### 具体改动

1. 在领域模型中增加不可变 `retrievalRunId`，PostgreSQL mapper 必须读写现有 `retrieval_run_id` 列。
2. 新行要求 `retrieval_run_id NOT NULL`；历史行保留 NULL，不做伪回填。
3. Context Builder 的输入改为 `RetrievalOutcome.selectedEvidence`，不再接受未经分级的全部 materials。
4. 同一 evidence 可以被多个角色共享，但每个角色必须有至少一条角色特有证据；根需求共享不计为独立证据。
5. 内容签名纳入 `retrievalRunId`、有序 evidence hash、quality report hash 和 omission reason。
6. 保存上下文包前校验 RetrievalRun consumer/role/task 与上下文包完全一致。

## 6. Phase 4：修复 Task-Run 快照与 Judge XML

### 修改文件

- `engine/src/main/java/com/wish/rd/engine/evaluation/taskrun/TaskRunEvaluationSnapshotCollector.java`
- `scripts/evaluation/rd_eval_lib.py`
- `scripts/evaluation/tests/test_rd_eval_lib.py`

### 6.1 快照结构

每个 context view 增加：

- `retrievalRunId`
- selected evidence 的 `evidenceId/sourceType/sourceUri/contentHash/contentPreview`
- `selectionReason/relevanceScore/requiredEvidenceType`
- `omissionReason`

每个 retrieval view 增加：

- `knowledgeBaseIds/mapVersion`
- `stageRunId`
- `stopReason`
- `selectedEvidenceArtifacts`
- `scopeViolationCount`
- `qualityReportHash`

删除当前把 QUERY、PLAN、QUALITY_REPORT、OUTCOME 与正文混在一起的 `retrieved_contexts` 投影；改为按 consumer 组织、只含 selected evidence、按 hash 去重。

### 6.2 保持三个 XML 顶层段落

继续保留已经确认的：

```xml
<task_input/>
<rag_evaluation_parameters/>
<role_execution_results/>
```

在 `<rag_evaluation_parameters>` 内新增：

```xml
<retrieval_evidence consumer="QA_AGENT" run_id="..." status="SUCCEEDED">
  <evidence id="..." source_type="CODE" uri="..." hash="..." required_type="TEST_ENTRY">
    <preview>...</preview>
    <selection_reason>...</selection_reason>
  </evidence>
</retrieval_evidence>
```

Prompt 预算仍以 UTF-8 字节精确计算；优先级固定为：任务事实 > 确定性失败指标 > 每个 consumer 的关键 evidence > 各角色最终结果 > 非关键详情。重复 evidence 只保存一次正文，角色通过引用 ID 使用。

### 6.3 Judge 分数约束

- 无实际 selected evidence：三个 context 类 Judge 指标全部 `SKIPPED`，reason=`missing evaluation context`。
- RetrievalRun 完整性或 scope 门禁失败：context 类指标不得覆盖确定性失败。
- Judge 只提供语义判断，不得把失败的确定性门禁改成 PASS。
- 输出保存 `promptSchemaVersion/promptHash/promptBytes/omittedSections`，不保存密钥或完整未脱敏 Prompt。

## 7. Phase 5：升级确定性指标

在 `scripts/evaluation/rd_eval_lib.py` 新增或替换以下指标：

| 指标 | 计算方式 | 门槛 |
| --- | --- | --- |
| `retrieval_run_integrity_rate` | consumer、成功状态、selected hash、context 绑定、stage 绑定均有效的比例 | `1.0` |
| `citation_integrity_rate` | URI 可定位且 hash 匹配的最终引用比例 | `1.0` |
| `scope_leak_rate` | 越出项目/KB/repo scope 的 evidence 比例 | `0.0` |
| `critical_evidence_coverage_rate` | 各角色 required evidence type 覆盖率 | `1.0` |
| `role_specific_evidence_coverage_rate` | 除共享根需求外，拥有角色特有证据的角色比例 | `1.0` |
| `context_noise_rate` | 人工 gold 或规则判定为无关的 selected evidence 比例 | `<=0.25` |
| `context_duplicate_rate` | 跨 consumer 重复且无引用复用标记的正文比例 | `<=0.30` |
| `provider_attempt_integrity_rate` | 每个最新 Stage 有标准化 attempt、provider、status、时间和错误信息 | `1.0` |
| `test_artifact_integrity_rate` | 测试 URI 存在、hash 匹配、命令和 exit code 可核对的比例 | `1.0` |
| `pr_integrity_rate` | URL 合法、关联目标 repo/branch/commit 且可核对 | `1.0` |

保留原有 coverage 指标作为低层诊断，但不再单独充当质量门禁。

## 8. Phase 6：重建数据集

### 8.1 分离 fixture 与 quality benchmark

目录建议：

```text
scripts/evaluation/datasets/scorer-smoke/
scripts/evaluation/datasets/quality-benchmark/
scripts/evaluation/fixtures/records/
scripts/evaluation/gold/
```

- `scorer-smoke` 可以保留当前 6 条，但 UI 明确标注“评分器自检”，不计入质量基线和门禁通过总数。
- gold 与被评 record 必须物理分离；真实运行生成 record，不允许数据集内嵌完美答案。
- 每条 gold 需人工标注 expected evidence IDs、forbidden evidence、关键缺口、预期状态和角色关键证据。

### 8.2 最小覆盖矩阵

首版质量基线至少 48 条：

- RAG 20 条：单事实、多跳、否定/比较、代码+日志、知识地图扩展、噪声历史、无证据、预算、部分通道失败、跨项目隔离、路径越权、Prompt injection、中英文混合。
- 角色 16 条：四角色各 2 条成功 + 2 条应阻断/待补材料。
- E2E 8 条：成功、WAITING_INPUT、provider fallback、阶段重试、恢复、取消、QA 阻断、scope violation。
- Task-Run 4 条：至少覆盖两个真实项目，包含一条成功和一条失败链；同一 taskId 的重复 Run 只计最新一次或作为显式 A/B 对比。

聚合时先按 scenario/task 做 macro average，避免同一商品管理任务的 11 次重复把统计结果放大。

### 8.3 真实产物验证

对 command/log/PR/screenshot/trace 类证据：

- 必须存在 manifest；
- URI 必须由受控 resolver 解析；
- 内容 hash 必须匹配；
- 命令需有 exit code、开始/结束时间和工作目录；
- `SKIPPED` 不能计入通过。

## 9. Phase 7：修复 Provider 与运行可复现性

### Provider attempt

统一所有 provider adapter 的 attempt 写入协议。MiniMax、LongCat、fallback、验证失败和成功都必须产生 append-only attempt；最终 Stage 的 `providerName` 必须与最后成功 attempt 一致。

### Run provenance

每个评测 Run 记录：

- dataset ID、版本、SHA-256；
- record snapshot hash；
- scorer schema/version 与 git commit；
- Prompt schema/version/hash/字节数；
- Judge provider、model、base URL host、temperature、token budget；
- 阈值配置版本；
- 基线 Run ID；
- 目标任务数据版本/采集时间。

API 和报告只返回脱敏信息，不返回 API key。

## 10. 前端调整

修改 `frontend/src` 中评测页面：

1. 将 `SUCCEEDED` 显示为“执行完成”。
2. 独立显示 `PASSED/NOT_PASSED/INCOMPLETE`。
3. Judge 失败时显示“评测不完整”，提供重试而不是与普通 NOT_OK 混在一起。
4. 数据集增加 `SCORER_SMOKE/QUALITY_BENCHMARK/TASK_RUN` 标签。
5. Run Inspector 增加证据完整性、scope、重复率和角色特有证据视图。
6. 聚合卡片默认按唯一 task/scenario 统计，重复 A/B Run 单独分组。

## 11. 验收标准

| 编号 | 场景 | 通过标准 |
| --- | --- | --- |
| E01 | 角色绑定 | 新建四角色任务后，每个 ContextPackage 的 `retrieval_run_id` 非空且 task/role/stage 匹配 |
| E02 | 相关性 | Playwright fixture 不再检索优惠券、商品管理、production-smoke 经验；`context_noise_rate<=0.25` |
| E03 | 角色独立 | 四角色共享根需求允许，但每个角色至少 1 条特有关键证据 |
| E04 | 空壳 Run | 非空材料但无角色关键证据时，Coding/QA 不得 `SUCCEEDED+SUFFICIENT` |
| E05 | Prompt | XML 仍为三个顶层段落，包含每个 consumer 的 selected evidence URI/hash/preview，且可被 XML parser 解析 |
| E06 | 无证据 Judge | RetrievalRun 缺失时 context 三指标不得 PASS，最终 gate 为 NOT_PASSED 或 INCOMPLETE |
| E07 | 绑定完整性 | 人为删除 context 的 retrievalRunId 后 `retrieval_run_integrity_rate=0` |
| E08 | scope | 外卖项目任务返回其他项目 evidence 时 `scope_leak_rate>0` 且门禁失败 |
| E09 | 真实命令 | 不存在的 `artifact://` URI 或 hash 不匹配时测试完整性失败 |
| E10 | Provider | MiniMax 直接成功也有一条完整 ProviderAttempt，覆盖率为 1.0 |
| E11 | Judge 故障 | OpenAI Judge 失败时 `judgeStatus=FAILED`、`gateStatus=INCOMPLETE`、`overallPassed=false` |
| E12 | 数据集 | quality benchmark 至少 48 条，gold 与 records 分离，所有样本有来源和标注版本 |
| E13 | 可复现 | 同 dataset/record/scorer/prompt/judge 配置重跑，确定性指标完全一致 |
| E14 | UI | 桌面与移动端能区分执行完成、门禁通过和评测不完整，证据详情可追溯 |

## 12. 验证命令

```bash
./mvnw -q -pl rag test
./mvnw -q -pl engine -am test
./mvnw -q -pl bootstrap -am test
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib
npm --prefix frontend run typecheck
npm --prefix frontend run build

./mvnw -q -pl bootstrap -Drd.integration.task-state-atomic.enabled=true \
  -Dtest=PostgresRdTaskStateAtomicRealSmokeTest test
```

真实验收还必须创建新的任务和新的 Evaluation Run，通过 HTTP、PostgreSQL、报告文件和浏览器四类证据核对同一 `taskId/runId`。历史终态 Run 只用于回归对照，不得修改后冒充修复后的通过证据。

## 13. 推荐实施顺序

1. P0：补失败测试，修真实 RetrievalOutcome 与 ContextPackage 绑定。
2. P0：修经验检索范围和 0 分淘汰，消除上下文污染。
3. P0：让 Judge XML 携带 selected evidence，并约束无证据评分。
4. P1：升级确定性完整性指标，修 ProviderAttempt 统一写入。
5. P1：拆分 smoke 与 quality benchmark，补 48 条独立 gold。
6. P2：补 provenance、UI 语义和 A/B 统计。
7. 执行一次全新真实链验收；通过后再把新 Run 设为质量基线。
