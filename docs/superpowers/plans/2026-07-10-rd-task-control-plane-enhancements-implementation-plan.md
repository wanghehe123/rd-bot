# RD Task Control Plane Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 为 Bug/需求任务补齐真实阶段记录、项目级飞书告警、图片材料执行链路、项目模板和 AI 结构化草稿。

**Architecture:** 沿用现有 `rag` 领域端口、`engine` 编排和 `bootstrap` 适配器边界。生产共享配置与投递记录落 PostgreSQL；二进制进入 `ObjectStorageService`；Bug 和需求共用阶段/产物协议但使用不同角色顺序；AI 填表通过独立端口返回可校验草稿，不直接创建任务。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis-Plus、PostgreSQL/JSONB、S3-compatible ObjectStorage、React 18、TypeScript、Vite、JUnit 5。

---

## 文件结构

新增领域文件：

- `engine/src/main/java/com/wish/rd/engine/bugfix/observability/BugFixStageRecorder.java`
- `engine/src/main/java/com/wish/rd/engine/draft/TaskDraftEngine.java`
- `engine/src/main/java/com/wish/rd/engine/draft/TaskDraftModelPort.java`
- `engine/src/main/java/com/wish/rd/engine/draft/model/TaskDraftRequest.java`
- `engine/src/main/java/com/wish/rd/engine/draft/model/TaskDraftResult.java`
- `rag/src/main/java/com/wish/rd/rag/project/alert/*`
- `rag/src/main/java/com/wish/rd/rag/project/template/*`
- `exec/src/main/java/com/wish/rd/exec/repair/execution/model/RepairInputAttachment.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/{entity,mapper,impl}` 对应 alert config、delivery、template。
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/project/RdProjectAlertConfigController.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/project/RdProjectTaskTemplateController.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/TaskDraftController.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/TaskMaterialAttachmentResolver.java`
- `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/AnthropicTaskDraftModelAdapter.java`
- `frontend/src/services/projectService.ts`
- `frontend/src/services/rdTaskService.ts`

主要修改文件：

- `engine/.../AgentRole.java`、`RdBotFixEngine.java`、`RequirementDeliveryEngine.java`
- `exec/.../RepairAlertType.java`、`RepairJobCommand.java`、`RepairWorkspaceFactory.java`
- `bootstrap/.../RdTaskExecutionOverviewController.java`、`RdTaskController.java`
- `bootstrap/.../FeishuImClient.java`、`FeishuImRepairAlertSink.java`
- `frontend/.../RdTaskListPage.tsx`、`RdTaskDetailPage.tsx`、`ProjectListPage.tsx`
- `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`

项目规则禁止未授权提交，因此本计划不执行 `git commit`。

### Task 1: Bug 阶段协议与记录器

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/model/AgentRole.java`
- Create: `engine/src/main/java/com/wish/rd/engine/bugfix/observability/BugFixStageRecorder.java`
- Test: `engine/src/test/java/com/wish/rd/engine/bugfix/observability/BugFixStageRecorderTest.java`

- [x] **Step 1: 写失败测试**

测试以下期望 API：

```java
BugFixStageRecorder recorder = new BugFixStageRecorder(runStore, artifactStore, idGenerator);
AgentStageRun first = recorder.start("100", AgentRole.BUG_RAG_RETRIEVER, "rag input");
AgentStageRun succeeded = recorder.succeed(first, "rag result", "", "[]");
AgentStageRun retry = recorder.start("100", AgentRole.BUG_RAG_RETRIEVER, "rag retry input");

assertEquals(AgentStageStatus.SUCCEEDED, succeeded.status());
assertEquals(2, retry.attemptNo());
assertNotEquals(first.stageRunId(), retry.stageRunId());
```

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl engine -Dtest=BugFixStageRecorderTest test
```

预期：因 `BUG_RAG_RETRIEVER` 或 `BugFixStageRecorder` 不存在而失败。

- [x] **Step 3: 最小实现**

`AgentRole` 增加四个 Bug 角色和：

```java
public static List<AgentRole> bugFixOrder() {
    return List.of(BUG_EVIDENCE_COLLECTOR, BUG_RAG_RETRIEVER, BUG_ACCEPTANCE_PLANNER, BUG_CODING_AGENT);
}
```

记录器必须只通过 Store 创建、transition 和 save，不绕过状态机。

- [x] **Step 4: 运行 GREEN**

重复 Step 2，预期 PASS。

### Task 2: RdBotFixEngine 写入真实阶段

**Files:**
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Test: `engine/src/test/java/com/wish/rd/engine/RdBotFixEngineTest.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewController.java`
- Test: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskExecutionOverviewControllerTest.java`

- [x] **Step 1: 写失败测试**

运行一次成功 Bug 流程后断言四角色及终态；另建 Bug/需求 task 断言概览使用各自顺序。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl engine -Dtest=RdBotFixEngineTest test
./mvnw -q -pl bootstrap -am -Dtest=RdTaskExecutionOverviewControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 实现边界调用**

`RdBotFixEngine.runAfterAcquire` 在四个真实边界 start/succeed/fail；执行器结果 provider 元数据从结构化 JSON提取，缺失时保持空值。Controller 使用：

```java
List<AgentRole> order = "BUG_FIX".equals(task.taskType())
        ? AgentRole.bugFixOrder()
        : AgentRole.requirementDeliveryOrder();
```

- [x] **Step 4: 运行 GREEN 与 engine 回归**

```bash
./mvnw -q -pl engine test
```

### Task 3: 项目告警配置和投递持久化

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/model/RdProjectAlertConfig.java`
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/model/RdAlertRecipient.java`
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/model/RdProjectAlertEventType.java`
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/RdProjectAlertConfigStore.java`
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/RdProjectAlertConfigService.java`
- Create: `rag/src/main/java/com/wish/rd/rag/project/alert/RdAlertDeliveryStore.java`
- Create: PostgreSQL row/mapper/store and controller files under `bootstrap`
- Modify: `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- Test: rag service tests, bootstrap store/controller tests

- [x] **Step 1: 写 service、store、controller 失败测试**

覆盖默认空配置、多收件人归一、事件去重、阈值正数校验、PostgreSQL JSONB 往返。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl rag -Dtest=RdProjectAlertConfigServiceTest test
./mvnw -q -pl bootstrap -am -Dtest=RdProjectAlertConfigControllerTest,PostgresRdProjectAlertConfigStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 创建幂等 SQL**

建 `rd_project_alert_configs`、`rd_alert_deliveries`，为 `idempotency_key` 建唯一索引，为 `task_id/created_at` 建查询索引。

- [x] **Step 4: 实现 Store/Service/API 并转绿**

API 请求和响应使用 record，Controller 只做适配。

### Task 4: 项目感知飞书路由与业务事件

**Files:**
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/alert/model/RepairAlertType.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/agent/model/AgentWorkflowAlertType.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/EngineAgentWorkflowAlertSink.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImClient.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/feishu/im/FeishuImRepairAlertSink.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
- Modify: `engine/src/main/java/com/wish/rd/engine/bugfix/RdBotFixEngine.java`
- Test: `bootstrap/.../ProjectAwareFeishuRepairAlertSinkTest.java`

- [x] **Step 1: 写失败测试**

测试项目匹配、多收件人、OPEN_ID URL 参数、失败阈值、幂等、投递失败审计和 secret 脱敏。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl bootstrap -am -Dtest=ProjectAwareFeishuRepairAlertSinkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 实现通用发送方法**

```java
FeishuImSendResult sendTextMessage(String receiveIdType, String receiveId, String text)
```

只允许 `chat_id/open_id`；旧 `sendTextMessage(chatId,text)` 委托到新方法。

- [x] **Step 4: 发出完成/阻塞/失败事件并转绿**

Bug 在 COMMITTED 发布 `TASK_COMPLETED`，失败发布 `TASK_FAILED`；需求在 COMPLETED 发布完成，在等待人工或人工失败发布阻塞；已有 QA/预算事件继续复用。

### Task 5: 二进制任务材料

**Files:**
- Modify: `rag/src/main/java/com/wish/rd/rag/runtime/model/TaskMaterialType.java`
- Modify: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/executor/impl/TaskMaterialAttachmentResolver.java`
- Test: `bootstrap/.../RdTaskControllerTest.java`
- Test: `bootstrap/.../TaskMaterialAttachmentResolverTest.java`

- [x] **Step 1: 写失败测试**

使用 `MockMultipartFile` 上传图片到 Bug/需求 task，断言 `artifactUri`、原始 hash、MIME；覆盖大小、数量、非法类型和跨任务读取。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl bootstrap -am -Dtest=RdTaskControllerTest,TaskMaterialAttachmentResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 实现对象存储与 content API**

禁止 `new String(file.getBytes(), UTF_8)` 处理图片；使用 `ObjectStorageService.upload`。content API 返回 `ByteArrayResource` 和原 MIME。

- [x] **Step 4: 转绿并运行 bootstrap focused tests**

重复 Step 2，预期 PASS。

### Task 6: Docker 工作区附件

**Files:**
- Create: `exec/src/main/java/com/wish/rd/exec/repair/execution/model/RepairInputAttachment.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/execution/model/RepairJobCommand.java`
- Modify: `exec/src/main/java/com/wish/rd/exec/repair/docker/RepairWorkspaceFactory.java`
- Modify: `bootstrap/.../EngineBugFixExecutorAdapter.java`
- Modify: `bootstrap/.../EngineRequirementExecutorAdapter.java`
- Modify: two executor configuration classes
- Test: `exec/.../RepairWorkspaceFactoryTest.java`
- Test: bootstrap adapter tests

- [x] **Step 1: 写失败测试**

断言附件落到 `input/attachments`、manifest 在 context、`../evil.png` 不逃逸、旧构造器 attachments 为空。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl exec -Dtest=RepairWorkspaceFactoryTest,RepairExecutionContractTest test
```

- [x] **Step 3: 实现附件 record、兼容构造器、resolver 注入**

附件内容防御性复制；每个文件最大 10 MiB；prompt 附加固定说明：`附件位于 /work/input/attachments，请先检查附件 manifest`。

- [x] **Step 4: 运行 GREEN**

```bash
./mvnw -q -pl exec test
./mvnw -q -pl bootstrap -am -Dtest=EngineBugFixExecutorAdapterTest,EngineRequirementExecutorAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 7: 项目任务模板

**Files:**
- Create: `rag/src/main/java/com/wish/rd/rag/project/template/*`
- Create: bootstrap PostgreSQL row/mapper/store/controller
- Modify: SQL
- Test: service/store/controller tests

- [x] **Step 1: 写失败测试**

覆盖 BUG_FIX/REQUIREMENT 两类、验收标准列表防御性复制、项目存在校验、upsert 往返。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl rag -Dtest=RdProjectTaskTemplateServiceTest test
./mvnw -q -pl bootstrap -am -Dtest=RdProjectTaskTemplateControllerTest,PostgresRdProjectTaskTemplateStoreTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 实现领域和持久化并转绿**

表唯一键 `(project_id, task_type)`；生产只提供 PostgreSQL Store。

### Task 8: AI 结构化草稿

**Files:**
- Create: engine `draft` files
- Create: `bootstrap/.../AnthropicTaskDraftModelAdapter.java`
- Create: `bootstrap/.../TaskDraftConfiguration.java`
- Create: `bootstrap/.../TaskDraftController.java`
- Test: engine/exec/bootstrap focused tests

- [x] **Step 1: 写失败测试**

端口返回合法 JSON时生成 available 草稿；非法 JSON、字段缺失、模型不可用时返回 `available=false`；验证不会创建任务。

- [x] **Step 2: 运行 RED**

```bash
./mvnw -q -pl engine -Dtest=TaskDraftEngineTest test
./mvnw -q -pl bootstrap -am -Dtest=TaskDraftControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 3: 实现结构化协议**

通过独立 `TaskDraftModelPort` 调用 Anthropic-compatible 模型，严格校验固定字段、数组、尾随 token 与 `confidence`；模型 prompt 明确“不得编造，未知项放 missingFields”。

- [x] **Step 4: 转绿**

重复 Step 2，并运行 `./mvnw -q -pl exec test`。

### Task 9: React 管理端交互

**Files:**
- Modify: `frontend/src/services/rdTaskService.ts`
- Modify: `frontend/src/services/projectService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Modify: `frontend/src/pages/admin/project/ProjectListPage.tsx`

- [x] **Step 1: 先建立可测试纯函数**

提取 `applyTaskTemplate(current, template)`，只填空字段；用 TypeScript 编译错误或轻量 Node 断言证明签名未实现。

- [x] **Step 2: 实现服务和 UI**

项目页增加 Bell/FileText 图标按钮；任务弹窗增加模板、AI、附件；详情页增加图片缩略图和补充材料。保持既有紧凑运营台风格，不嵌套卡片。

- [x] **Step 3: 类型与构建**

```bash
cd frontend
npm run typecheck
npm run build
```

预期均为 exit 0；允许已有 Vite chunk size warning，但不得新增 TypeScript 错误。

### Task 10: 真实验收、subagent CR 和经验固化

**Files:**
- Update: `docs/qa/rd-task-control-plane-enhancements-acceptance-plan-2026-07-10.md`
- Create: `docs/qa/rd-task-control-plane-enhancements-acceptance-report-2026-07-10.md`
- Create: `docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-lessons-spec.md`

- [x] **Step 1: 全量自动化验证**

```bash
./mvnw -q test
cd frontend && npm run typecheck && npm run build
```

- [x] **Step 2: 安装新模块并启动真实服务**

```bash
./mvnw -q install -DskipTests
./mvnw -q -pl bootstrap spring-boot:run
```

按验收文档执行真实 HTTP/DB/对象存储路径；外部密钥只检查 `SET/EMPTY`。

- [x] **Step 3: 浏览器验收**

桌面 `1440x900` 与移动 `390x844` 验证任务表单、详情图片、项目模板/告警弹窗，无重叠、无空白图片、无 console error。

- [x] **Step 4: gpt-5.6-luna subagent 逐项 CR/验收**

分别派发 CR-A、CR-B、CR-C、CR-D、QA-E；每个 agent 读取方案、验收表和相关 diff，输出文件行号与复跑命令。修复 Critical/Important 后重跑对应组。

- [x] **Step 5: 写最终报告和 lessons spec**

报告逐 ID 填 `PASS/FAIL/BLOCKED_EXTERNAL`；spec 固化阶段真值、二进制边界、项目告警幂等、AI 不编造/不自动执行和 stale module 启动规则。

## 计划自检

- 方案四项需求均有对应 Task 和验收 ID。
- 所有生产共享配置均有 PostgreSQL Store 和 SQL。
- 图片不写数据库、不转 base64 prompt。
- Bug/需求角色顺序独立，没有把 Bug 伪装成需求角色。
- 外部 Feishu/模型不可用时不会把 SKIPPED 写成 PASS。
- 未安排 git commit，符合仓库 `AGENTS.md`。
