# RD 任务控制面增强测试验收计划

日期：2026-07-10

关联方案：`docs/superpowers/specs/2026-07-10-rd-task-control-plane-enhancements-design.md`

## 1. 验收原则

- 每项能力先有自动化失败用例，再实现并转绿。
- 单元测试只证明局部语义；最终必须补真实 HTTP、PostgreSQL、对象存储和浏览器路径。
- 外部 Feishu/模型密钥不可用时，必须完成本地协议级测试并把真实外部项标为 `BLOCKED_EXTERNAL`，不能写成通过。
- 所有证据写入本文件的“执行结果”列或最终报告，包含命令、响应码、关键字段和资源 ID。

状态枚举：`NOT_RUN / PASS / FAIL / BLOCKED_EXTERNAL`。

## 2. A 组：Bug 阶段记录

| ID | 验收点 | 自动化证据 | 实链证据 | 状态 |
| --- | --- | --- | --- | --- |
| A-01 | Bug 流程创建 `BUG_EVIDENCE_COLLECTOR`、`BUG_RAG_RETRIEVER`、`BUG_ACCEPTANCE_PLANNER`、`BUG_CODING_AGENT` 四阶段 | `RdBotFixEngineTest` | task `7481166106292523008` execution-overview 返回四角色 | PASS |
| A-02 | 四阶段按合法状态推进并保存开始/结束时间 | `RdBotFixEngineTest`、`BugFixStageRecorderTest` | 概览返回 3 个成功阶段和 1 个运行阶段 | PASS |
| A-03 | 阶段保存上下文包、输入、prompt、结果产物并绑定 ID | `BugFixStageRecorderTest` | `contextPackageId/promptArtifactId/resultArtifactId` 断言 | PASS |
| A-04 | 失败后重试创建新 attempt，不复活旧终态 | `RdBotFixEngineTest` | planner 失败后同 task 生成 attempt 2 | PASS |
| A-05 | Bug 概览只按 Bug 阶段排序；需求概览保持四角色 | `RdTaskExecutionOverviewControllerTest` | 混入需求角色时被过滤 | PASS |
| A-06 | Bug 成功任务不再出现空 `0/24` | `RdTaskExecutionOverviewControllerTest` | 实链执行中为 `21/24`，四阶段均可见 | PASS |

自动化命令：

```bash
./mvnw -q -pl engine -Dtest=BugFixStageRecorderTest,RdBotFixEngineTest test
./mvnw -q -pl bootstrap -am -Dtest=RdTaskExecutionOverviewControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP 断言：

```bash
curl -fsS 'http://127.0.0.1:18080/admin/rd-tasks/<bugTaskId>/execution-overview' \
  | jq '{taskType,progressCompleted,progressTotal,roles:[.stageRuns[].role]}'
```

必须满足：`taskType=BUG_FIX`、roles 精确包含四个 Bug 阶段、`progressCompleted > 0`。

## 3. B 组：项目飞书告警

| ID | 验收点 | 自动化证据 | 实链证据 | 状态 |
| --- | --- | --- | --- | --- |
| B-01 | 项目告警配置可创建、查询、更新并持久化，预算字段为 CNY | `RdProjectAlertConfigControllerTest`、`PostgresRdProjectAlertConfigStoreTest` | 18082 PUT/GET、DB 反查、重启读取 | PASS |
| B-02 | 支持多个 `CHAT_ID/OPEN_ID` 收件人和六类事件 | `RdProjectAlertConfigServiceTest` | GET 返回 2 收件人、6 事件 | PASS |
| B-03 | 完成、阻塞、失败、重试耗尽、预算、QA 事件正确映射 | `ProjectAwareFeishuRepairAlertSinkTest` | Stub 捕获 `chat_id/open_id` 请求 | PASS |
| B-04 | `failureThreshold` 与项目 CNY 预算阈值生效 | `ProjectAwareFeishuRepairAlertSinkTest` | 高/低全局阈值和失败次数测试 | PASS |
| B-05 | 同一事件同一收件人只投递一次 | `ProjectAwareFeishuRepairAlertSinkTest` | PostgreSQL 唯一键 + Feishu `uuid` + PENDING 重试 | PASS |
| B-06 | 飞书失败不改变任务状态，并写投递审计 | `ProjectAwareFeishuRepairAlertSinkTest`、`PostgresRdAlertDeliveryStoreTest` | 预留失败跳过发送，错误脱敏 | PASS |
| B-07 | 真实飞书收到一条脱敏测试告警 | - | 本机未提供已授权真实测试收件人；不得向伪 ID 发送 | BLOCKED_EXTERNAL |
| B-08 | USD 存量阈值一次性迁移为 CNY，且收件人两个列表均可为空 | CNY converter、alert-config controller/store tests、`alertRecipients.test.ts` | project `7479447343427883008`：`7.5000 USD -> 54.0000 CNY`；浏览器独立增删群聊/用户列表 | PASS |

自动化命令：

```bash
./mvnw -q -pl rag -Dtest=RdProjectAlertConfigServiceTest test
./mvnw -q -pl bootstrap -am -Dtest=RdProjectAlertConfigControllerTest,PostgresRdProjectAlertConfigStoreTest,PostgresRdAlertDeliveryStoreTest,ProjectAwareFeishuRepairAlertSinkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP/DB 断言：

```bash
curl -fsS -X PUT 'http://127.0.0.1:18080/admin/projects/<projectId>/alert-config' \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true,"recipients":[{"type":"CHAT_ID","value":"<redacted>"}],"eventTypes":["TASK_COMPLETED","TASK_BLOCKED","TASK_FAILED","RETRY_EXHAUSTED","BUDGET_EXCEEDED","QA_FAILED"],"budgetThresholdCny":36,"failureThreshold":2}'

docker exec postgres psql -U postgres -d ragent -Atc \
  "select project_id,enabled,failure_threshold from rd_project_alert_configs where project_id=<projectId>;"
```

## 4. C 组：图片材料与执行附件

| ID | 验收点 | 自动化证据 | 实链证据 | 状态 |
| --- | --- | --- | --- | --- |
| C-01 | Bug 和需求任务都能上传 PNG/JPEG/WebP/GIF | `RdTaskControllerTest` | Bug task `7481166106292523008` 图片上传 200 | PASS |
| C-02 | 超过 10 MiB、非法 MIME、超过 10 个附件被拒绝 | `RdTaskControllerTest` | 伪 PNG 实链 400；大小/数量自动化覆盖 | PASS |
| C-03 | 图片原字节进入对象存储，DB 只保存 metadata/hash/URI | `TaskMaterialAttachmentResolverTest` | 原字节 SHA-256 `bee39f...` 往返一致 | PASS |
| C-04 | content 接口校验任务归属、hash 并返回原 MIME | `RdTaskControllerTest` | PNG hash 一致；HTML 强制 attachment | PASS |
| C-05 | 工作区写入安全且唯一的附件文件和 manifest | `RepairWorkspaceFactoryTest`、`TaskMaterialAttachmentResolverTest` | materialId 前缀防同名覆盖 | PASS |
| C-06 | 路径穿越和 symlink 不会逃逸 attachment 目录 | `RepairWorkspaceFactoryTest` | task、目录和目标 symlink 负向测试 | PASS |
| C-07 | 任务详情页显示缩略图且可补充材料 | frontend build + browser | 图片 naturalWidth=2576；390/1440 无溢出、console 0 error | PASS |

自动化命令：

```bash
./mvnw -q -pl exec -Dtest=RepairWorkspaceFactoryTest test
./mvnw -q -pl bootstrap -am -Dtest=RdTaskControllerTest,TaskMaterialAttachmentResolverTest -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP 断言：

```bash
curl -fsS -X POST 'http://127.0.0.1:18080/admin/rd-tasks/<taskId>/materials/upload' \
  -F 'file=@qa-runs/fixtures/task-screenshot.png;type=image/png' \
  -F 'materialType=SCREENSHOT' | tee /tmp/rd-material.json

curl -fsS "http://127.0.0.1:18080/admin/rd-tasks/<taskId>/materials/$(jq -r .materialId /tmp/rd-material.json)/content" \
  -o /tmp/rd-material-returned.png

shasum -a 256 qa-runs/fixtures/task-screenshot.png /tmp/rd-material-returned.png
```

两个 hash 必须相同。

## 5. D 组：项目模板与 AI 草稿

| ID | 验收点 | 自动化证据 | 实链证据 | 状态 |
| --- | --- | --- | --- | --- |
| D-01 | 项目可分别保存 Bug/需求模板并在重启后读取 | `RdProjectTaskTemplateControllerTest`、`PostgresRdProjectTaskTemplateStoreTest` | 18082 PUT/GET、DB 反查、重启读取 | PASS |
| D-02 | 模板应用不覆盖用户非空字段 | 函数式 state 更新 | 浏览器实际值保持“用户已经填写，禁止覆盖” | PASS |
| D-03 | AI 补全返回固定结构、证据、缺失字段和置信度 | `TaskDraftEngineTest` | 严格结构和 0..1 confidence | PASS |
| D-04 | 非法模型 JSON、尾随 token、code fence 被拒绝 | `TaskDraftEngineTest` | 返回 `available=false` 且不生成内容 | PASS |
| D-05 | 无密钥时不返回伪 AI 内容 | `TaskDraftControllerTest` | 18082 POST 返回 `available=false` | PASS |
| D-06 | AI 草稿不会自动创建或执行任务 | `TaskDraftControllerTest` | PostgreSQL task count `97 -> 97` | PASS |
| D-07 | 前端模板、AI、附件在移动端和桌面端无重叠 | frontend build + browser | 1440x900、390x844，overlap=0、scrollWidth=clientWidth | PASS |

自动化命令：

```bash
./mvnw -q -pl engine -Dtest=TaskDraftEngineTest test
./mvnw -q -pl rag -Dtest=RdProjectTaskTemplateServiceTest test
./mvnw -q -pl bootstrap -am -Dtest=RdProjectTaskTemplateControllerTest,PostgresRdProjectTaskTemplateStoreTest,TaskDraftControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 6. E 组：回归、安全与全量验收

| ID | 验收点 | 命令 | 状态 |
| --- | --- | --- | --- |
| E-01 | engine 模块全绿 | `./mvnw -q -pl engine test` | PASS |
| E-02 | exec 模块全绿 | `./mvnw -q -pl exec test` | PASS |
| E-03 | rag 模块全绿 | `./mvnw -q -pl rag test` | PASS |
| E-04 | bootstrap 模块及依赖全绿 | `./mvnw -q -pl bootstrap -am test` | PASS |
| E-05 | Maven 全量全绿 | `./mvnw -q test`：888 tests，0 failure/error，22 skipped | PASS |
| E-06 | 前端类型与构建通过 | `npm run typecheck && npm run build` | PASS |
| E-07 | 新增 SQL 可重复执行 | 本地 PostgreSQL 连续执行，均 exit 0；同时修复历史字段宽度 | PASS |
| E-08 | 变更无明文 secret | diff secret scan + 人工复核 | PASS |
| E-09 | 未引入生产内存真值 | policy test + PostgreSQL 条件 Bean | PASS |

## 7. Subagent CR 与验收矩阵

全部代码和本地主验收完成后，使用 `gpt-5.6-luna`，每项独立上下文：

| Agent | 范围 | 必须核对 |
| --- | --- | --- |
| CR-A | A-01 至 A-06 | 状态机、attempt、阶段真实性、需求链无回归 |
| CR-B | B-01 至 B-07 | 路由、幂等、投递审计、secret、失败不反向污染任务 |
| CR-C | C-01 至 C-07 | 二进制安全、对象存储、路径安全、工作区可见、前端预览 |
| CR-D | D-01 至 D-07 | 模板覆盖语义、模型结构校验、无密钥降级、无自动执行 |
| QA-E | E-01 至 E-09 | 命令证据、HTTP/DB/浏览器证据、报告与代码一致 |

每个 subagent 输出：`PASS/FAIL`、文件行号、阻断问题、缺失测试、复跑命令。主 Agent 必须修复 Critical/Important 后重跑相关验收。

## 8. 执行结果

本节在实现与验收过程中持续更新，不允许用“代码看起来正确”替代证据。

| ID | 最终状态 | 证据摘要 | 证据路径/标识 |
| --- | --- | --- | --- |
| A-01..A-06 | PASS | 四阶段、上下文包、产物、失败重试、角色隔离均通过 | task `7481166106292523008` + 自动化 |
| B-01..B-06 | PASS | 真实配置持久化；stub 覆盖事件、阈值、幂等、审计、脱敏 | project `7479447343427883008` |
| B-07 | BLOCKED_EXTERNAL | 未使用伪收件人向真实飞书发送 | 需已授权 `CHAT_ID/OPEN_ID` |
| C-01..C-07 | PASS | 图片原字节、hash、HTML attachment、非法 MIME、工作区与浏览器通过 | material `7481166138022432768` |
| D-01..D-07 | PASS | 双模板重启持久化、无密钥降级、任务数不变、浏览器交互通过 | project `7479447343427883008` |
| E-01..E-09 | PASS | 全量 Maven、前端、SQL 双执行、secret/policy 检查通过 | 本报告命令证据 |
