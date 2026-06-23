# P1 飞书工单 + RocketMQ 真实验证报告

## 结论

- 测试时间：2026-06-22 11:10-11:23（Asia/Shanghai）。
- 测试范围：P1 飞书工单契约、事件入队、RocketMQ 发布、工单消费到 RAG 上下文、repair record 持久化与查询、默认测试门控、真实 PostgreSQL 冒烟。
- 最终结果：默认测试、RocketMQ 真实发布、PostgreSQL 真实 CRUD、HTTP 端到端 Mock 飞书流程均通过。
- 修复的问题：真实飞书模式下 `FeishuHelpdeskClient` 因多构造器缺少 `@Autowired` 无法装配。已补测试并修复。
- 未完整执行：真实飞书 Helpdesk 只读详情调用。原因是当前只有 App ID/Secret，还缺 Helpdesk ID、Helpdesk Token、真实 ticketId。

## 环境

Docker 运行状态已确认：

- `rmqnamesrv`：`apache/rocketmq:5.2.0`，`9876` 映射到宿主机。
- `rmqbroker`：`apache/rocketmq:5.2.0`，`10909` / `10911-10912` 映射到宿主机。
- `rocketmq-dashboard`：`apacherocketmq/rocketmq-dashboard:2.1.0`。
- `postgres`：`pgvector/pgvector:pg16`，`5432` 映射到宿主机。

已创建/确认 RocketMQ 资源：

```text
topic: RD_BOT_REPAIR_TICKET
consumer group: GID_RD_BOT_REPAIR_WORKER
name server: 127.0.0.1:9876
```

## 默认测试

命令：

```bash
./mvnw test
```

结果：

```text
Tests run: 104, Failures: 0, Errors: 0, Skipped: 5
Reactor: RD-Bot / rag / engine / exec / skill / bootstrap 全部 SUCCESS
```

说明：

- 新增了 `FeishuHelpdeskBeanWiringTest`，默认测试会覆盖 `rd.feishu.helpdesk.enabled=true` 下真实 Feishu Bean 的 Spring 装配。
- 5 个 skipped 为属性门控的真实基础设施冒烟测试，符合 P1 计划默认行为。

## RocketMQ 真实冒烟

前置操作：

```bash
docker exec rmqbroker /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updatetopic \
  -n rmqnamesrv:9876 -c DefaultCluster -t RD_BOT_REPAIR_TICKET

docker exec rmqbroker /home/rocketmq/rocketmq-5.2.0/bin/mqadmin updatesubgroup \
  -n rmqnamesrv:9876 -c DefaultCluster -g GID_RD_BOT_REPAIR_WORKER
```

真实发布命令：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=RocketMqRepairQueueRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.rocketmq.enabled=true \
  -Drd.repair.queue.mode=rocketmq \
  -Drd.rocketmq.repair.name-server=127.0.0.1:9876 \
  test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
published to topic=RD_BOT_REPAIR_TICKET tag=P1
ticketId=SMOKE-e7b19842
msgId=C612000104EE18FF02E46E9616520000
```

Broker 侧曾用 `topicStatus` 验证 offset 更新，发布后的队列有新增 `Max Offset`。

## PostgreSQL 真实冒烟

命令：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=PostgresPersistenceCrudIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.postgres.enabled=true \
  test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
Hikari connected to local PostgreSQL
```

覆盖点：

- `repair_records` 状态轮转：`QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY`。
- `repair_record_artifacts` 写入：`FEISHU_EVENT` / `FEISHU_TICKET_SNAPSHOT` / `FEISHU_MESSAGES` / `RAG_CONTEXT`。
- `RepairRecordRepository.query` 按 `ticketId`、`status`、`priority` 分页过滤。

## HTTP 端到端 Mock 飞书流程

启动命令：

```bash
./mvnw install -DskipTests
./mvnw -f bootstrap/pom.xml spring-boot:run \
  -Dspring-boot.run.jvmArguments='-Dserver.port=18080 -Drd.storage.mode=memory -Drd.ai.providers.mock.api-key=abcdef1234567890'
```

真实 HTTP 调用链路：

1. `POST /test/feishu/helpdesk/mock-ticket`
2. `POST /test/feishu/helpdesk/events/ticket-created`
3. `GET /test/repair/queue`
4. `POST /test/repair/tickets/{ticketId}/run`
5. `GET /repair-records?ticketId=...`
6. `GET /repair-records/{id}/artifacts`

最终结果摘要：

```json
{
  "ticketId": "FS-E2E-1782098615445",
  "traceId": "trace-FS-E2E-1782098615445",
  "publish": {
    "status": 200,
    "success": true,
    "targetTopic": "in-memory://RD_BOT_REPAIR_TICKET",
    "targetTag": "P1",
    "messageId": "mem-1"
  },
  "queueMessageKeys": [
    "attempt",
    "createdAt",
    "eventId",
    "eventType",
    "priority",
    "source",
    "ticketId",
    "traceId"
  ],
  "run": {
    "status": 200,
    "repairRecordId": "7474663351725985792",
    "repairStatus": "CONTEXT_READY",
    "artifactCount": 4,
    "contextSummaryLength": 242
  },
  "recordQuery": {
    "status": 200,
    "total": 1,
    "recordStatus": "CONTEXT_READY",
    "priority": "P1",
    "traceId": "trace-FS-E2E-1782098615445"
  },
  "artifactTypes": [
    "FEISHU_EVENT",
    "FEISHU_MESSAGES",
    "FEISHU_TICKET_SNAPSHOT",
    "RAG_CONTEXT"
  ],
  "result": "PASS"
}
```

关键断言：

- 入队消息只包含路由元数据：`ticketId`、`priority`、`traceId`、`attempt`、`source`、`eventId`、`eventType`、`createdAt`。
- 入队消息未包含标题、描述、日志、token 或 Helpdesk 信息。
- 消费后记录状态为 `CONTEXT_READY`。
- 生产查询接口 `/repair-records` 可按 `ticketId` 查到记录，并返回脱敏视图。
- 产物接口返回 4 类 P1 产物。

## 飞书真实 Helpdesk 状态

已验证：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=FeishuHelpdeskRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.feishu.enabled=true \
  -Drd.feishu.helpdesk.app-id=cli_a9458f91d17b5cd6 \
  test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 1
```

含义：

- 真实 Feishu 模式下 Spring 装配已可启动。
- 只读真实 API 未调用，因为还缺：
  - `rd.feishu.helpdesk.app-secret`
  - `rd.feishu.helpdesk.helpdesk-id`
  - `rd.feishu.helpdesk.helpdesk-token`
  - `rd.feishu.smoke.ticket-id`
- App Secret 未写入仓库或报告。

## 测试中发现并修复的问题

### 1. 真实 Feishu 模式 Spring 装配失败

现象：

```text
Error creating bean with name 'feishuHelpdeskClient'
Failed to instantiate [FeishuHelpdeskClient]: No default constructor found
```

根因：

- `FeishuHelpdeskClient` 有生产构造器和测试可见构造器两个构造器。
- 生产构造器没有 `@Autowired`，Spring 在多构造器组件上未选择该构造器。

修复：

- 给 `FeishuHelpdeskClient` 生产构造器增加 `@Autowired`。
- 新增 `FeishuHelpdeskBeanWiringTest`，默认测试覆盖真实 Feishu Bean 装配。

### 2. 文档中的单测试冒烟命令需补充 Maven 参数

现象：

```text
No tests matching pattern "RocketMqRepairQueueRealSmokeTest" were executed
```

根因：

- `-pl bootstrap -am -Dtest=...` 会让 surefire 测试过滤器传播到前置模块。
- 前置模块没有同名测试时会失败，命令到不了 `bootstrap`。

可用命令：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=RocketMqRepairQueueRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  ...
  test
```

同理适用于 PostgreSQL / Feishu 单测试冒烟命令。

### 3. 本地 HTTP 启动需要先刷新多模块产物

现象：

- `./mvnw -pl bootstrap spring-boot:run` 可能拿到本地 Maven 仓库旧的 sibling module 产物。
- `./mvnw -pl bootstrap -am spring-boot:run` 会把目标错误应用到父 POM，报父 POM 无 main class。

可用流程：

```bash
./mvnw install -DskipTests
./mvnw -f bootstrap/pom.xml spring-boot:run ...
```

## 最终判断

P1 在本机可验证范围内已经通过：

- 代码默认测试全绿。
- RocketMQ 真实 broker 发布成功。
- PostgreSQL 真实持久化和 P1 repair record 查询成功。
- HTTP 端到端 Mock 飞书流程成功。
- 真实 Feishu 模式装配缺陷已修复并纳入默认测试。

剩余只依赖外部飞书 Helpdesk 业务参数。提供 Helpdesk ID、Helpdesk Token 和一个真实 ticketId 后，可继续执行只读真实飞书冒烟。
