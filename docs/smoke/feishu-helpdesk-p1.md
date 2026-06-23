# P1 飞书工单 + RocketMQ 冒烟测试指南

P1 的真实外部依赖（飞书 Helpdesk、RocketMQ、PostgreSQL）冒烟测试默认跳过，需要显式开启并提供凭据。本文档列出每个冒烟测试的执行命令、必填配置与预期结果。

## 1. 前置条件

| 依赖 | 本地 Docker（参考） | 端口 |
|------|---------------------|------|
| PostgreSQL（含 pgvector） | `pgvector/pgvector:pg16` | 5432 |
| RocketMQ NameServer | `apache/rocketmq:5.2.0` | 9876 |
| RocketMQ Broker | `apache/rocketmq:5.2.0` | 8080-8082 / 10909-10912 |
| RocketMQ Dashboard | `apacherocketmq/rocketmq-dashboard:2.1.0` | 8080 |

飞书机器人凭据（由用户提供）：

- App ID：`cli_a9458f91d17b5cd6`
- App Secret：通过环境变量 `FEISHU_APP_SECRET` 注入，不要写入仓库。
- Helpdesk ID / Helpdesk Token：由飞书服务台后台获取。官方入口是 [服务台管理后台](https://feishu.cn/helpdesk/admin) 的 **设置中心 > API 凭证**；重置 token 会生成新 token，旧 token 自动失效。

### 1.1 飞书权限与字段配置

在飞书开放平台应用后台配置：

1. **凭据**：配置 `FEISHU_APP_ID` / `FEISHU_APP_SECRET`，用于 `POST /open-apis/auth/v3/tenant_access_token/internal` 获取 `tenant_access_token`。
2. **Helpdesk 鉴权**：从 [服务台管理后台](https://feishu.cn/helpdesk/admin) 的 **设置中心 > API 凭证** 获取 `FEISHU_HELPDESK_ID` / `FEISHU_HELPDESK_TOKEN`。RD-Bot 会生成 `X-Lark-Helpdesk-Authorization = base64(helpdeskId:helpdeskToken)`，并在查询工单、消息、自定义字段、回写接口中发送。请由服务台负责人妥善保管该凭据；重置 token 后旧 token 立即失效。
3. **事件订阅**：订阅并启用回调事件 `helpdesk.ticket.created_v1`、`helpdesk.ticket.updated_v1`、`helpdesk.ticket_message.created_v1`，回调地址为 `POST /feishu/helpdesk/events`。
4. **接口权限**：读工单详情、消息、自定义字段和事件订阅需要开通 `获取服务台资源详情(helpdesk:all:readonly)`，覆盖 `GET /open-apis/helpdesk/v1/tickets/:ticket_id`、`GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages`、`GET /open-apis/helpdesk/v1/customized_fields`、`POST /open-apis/helpdesk/v1/events/subscribe`。如果要回写工单，再开通 `更新服务台资源详情(helpdesk:all)`，覆盖 `POST /open-apis/helpdesk/v1/tickets/:ticket_id/messages` 与 `PUT /open-apis/helpdesk/v1/tickets/:ticket_id`。
5. **应用发布/安装**：权限变更后需要重新发布应用，并由租户管理员安装或升级应用权限。

自定义字段映射按飞书后台的字段 `key_name`、`display_name` 或字段 ID 配置。推荐在飞书后台新建/调整工单自定义字段时直接使用下表的 RD-Bot 标准字段作为 `key_name`，这样可以沿用 `application.yaml` 默认值；如果已有字段不能改名，则把对应配置项改成飞书字段 ID 或展示名。

| RD-Bot 标准字段 | 飞书字段含义 | 配置项 |
| --- | --- | --- |
| `problemSystem` | 故障系统/模块 | `rd.feishu.helpdesk.field-mapping.problemSystem` |
| `symptom` | 故障现象 | `rd.feishu.helpdesk.field-mapping.symptom` |
| `logs` | 错误日志/异常栈 | `rd.feishu.helpdesk.field-mapping.logs` |
| `repository` | 代码仓库 URL 或 owner/repo | `rd.feishu.helpdesk.field-mapping.repository` |
| `branch` | 基准分支 | `rd.feishu.helpdesk.field-mapping.branch` |
| `expectedResult` | 期望结果 | `rd.feishu.helpdesk.field-mapping.expectedResult` |
| `actualResult` | 实际结果 | `rd.feishu.helpdesk.field-mapping.actualResult` |

RD-Bot 进入 RAG 的最小条件：工单描述或 `symptom` 至少一个非空，并且 `logs` 或 `repository` 至少一个非空。

## 2. RocketMQ Topic 准备

RocketMQ 5.x 默认启用 autoCreateTopic，但生产建议显式创建：

```bash
# 进入 broker 容器创建 topic 与 consumer group
docker exec -it rmqbroker sh -c \
  "/home/rocketmq/rocketmq-5.2.0/bin/mqadmin updatetopic -n 127.0.0.1:9876 \
   -c DefaultCluster -t RD_BOT_REPAIR_TICKET"

docker exec -it rmqbroker sh -c \
  "/home/rocketmq/rocketmq-5.2.0/bin/mqadmin updatesubgroup -n 127.0.0.1:9876 \
   -c DefaultCluster -g GID_RD_BOT_REPAIR_WORKER"
```

## 3. PostgreSQL 冒烟（实体 CRUD + P1 状态轮转）

```bash
./mvnw -pl bootstrap -am \
  -Dtest=PostgresPersistenceCrudIntegrationTest \
  -Drd.integration.postgres.enabled=true \
  [-Drd.integration.postgres.url=jdbc:postgresql://127.0.0.1:5432/ragent] \
  [-Drd.integration.postgres.username=postgres] \
  [-Drd.integration.postgres.password=postgres] \
  test
```

**验证内容**：

- `repair_records` 状态 `QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY` 轮转可持久化。
- `repair_record_artifacts` 至少包含 `FEISHU_EVENT` / `FEISHU_TICKET_SNAPSHOT` / `FEISHU_MESSAGES` / `RAG_CONTEXT`。
- `RepairRecordRepository.query` 支持按 `ticketId` / `status` / `priority` 过滤与分页。

## 4. 飞书 Helpdesk 冒烟（只读 + 可选回写）

### 4.1 只读冒烟（推荐）

```bash
./mvnw -pl bootstrap -am \
  -Dtest=FeishuHelpdeskRealSmokeTest \
  -Drd.integration.feishu.enabled=true \
  -Drd.feishu.helpdesk.app-id=$FEISHU_APP_ID \
  -Drd.feishu.helpdesk.app-secret=$FEISHU_APP_SECRET \
  -Drd.feishu.helpdesk.helpdesk-id=$FEISHU_HELPDESK_ID \
  -Drd.feishu.helpdesk.helpdesk-token=$FEISHU_HELPDESK_TOKEN \
  -Drd.feishu.smoke.ticket-id=$EXISTING_TICKET_ID \
  test
```

**验证内容**：

- `tenant_access_token` 获取成功。
- `GET /open-apis/helpdesk/v1/tickets/:ticket_id` 返回工单详情并映射到 `TicketSnapshot`。
- `GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages` 返回消息列表。
- `GET /open-apis/helpdesk/v1/customized_fields` 返回自定义字段定义。
- `FeishuHelpdeskAuth.toString()` 不泄漏 helpdesk token。

### 4.2 回写冒烟（需额外显式开启）

回写（发消息 / 更新工单）默认关闭，需要额外开启：

```bash
-Drd.feishu.helpdesk.write-back.enabled=true
-Drd.feishu.helpdesk.staff-id=$STAFF_ID
```

> 警告：回写冒烟会真实修改飞书工单状态与发送消息，建议只在测试工单上执行。

## 5. RocketMQ 端到端冒烟

```bash
./mvnw -pl bootstrap -am \
  -Dtest=RocketMqRepairQueueRealSmokeTest \
  -Drd.integration.rocketmq.enabled=true \
  -Drd.repair.queue.mode=rocketmq \
  -Drd.rocketmq.repair.name-server=127.0.0.1:9876 \
  test
```

**验证内容**：

- `RocketMqRepairQueueAdapter.publish` 成功发布到 topic `RD_BOT_REPAIR_TICKET`。
- 消息 key = `ticketId`，tag = 优先级（`P0`/`P1`/`P2`），property 含 `traceId`/`attempt`/`source`。
- 消费端到端验证（回调被触发、状态推进）通过本地端到端联调的 `/test/repair/tickets/{id}/run` 通道完成，避免冒烟测试与 Spring 上下文中已注册的 `TicketRepairEngine` 消费者产生冲突。

### 5.1 RocketMQ + 自动执行启动参数

真实从 MQ 消费后继续触发 Docker/PR 执行，需要额外开启：

```bash
--rd.repair.queue.mode=rocketmq
--rd.rocketmq.repair.name-server=127.0.0.1:9876
--rd.repair.ticket.auto-execute.enabled=true
--rd.executor.docker.enabled=true
--rd.github.code-platform.mode=real
```

`rd.repair.ticket.auto-execute.enabled=false` 是默认值，用于避免零配置环境把 mock 执行结果误当成真实 PR。

## 6. 本地端到端联调（无需真实外部依赖）

零配置启动后，内存队列 + Mock 飞书工单即可跑通完整 P1 流程：

```bash
./mvnw -pl bootstrap spring-boot:run
```

然后调用测试通道：

```bash
# 触发 ticket-created 事件入队
curl -X POST http://localhost:8080/test/feishu/helpdesk/events/ticket-created \
  -H 'Content-Type: application/json' \
  -d '{"ticketId":"FS-MOCK-1","priority":"P1"}'

# 消费并产出 repair_record
curl -X POST http://localhost:8080/test/repair/tickets/FS-MOCK-1/run

# 查看队列快照
curl http://localhost:8080/test/repair/queue

# 查询修复记录
curl 'http://localhost:8080/repair-records?ticketId=FS-MOCK-1'
curl http://localhost:8080/repair-records/{id}/artifacts
```

## 7. 默认验证（CI 友好，无外部依赖）

```bash
./mvnw test
```

默认跑全部单测：内存队列、Mock 飞书、内存仓储。真实依赖冒烟测试全部跳过。
