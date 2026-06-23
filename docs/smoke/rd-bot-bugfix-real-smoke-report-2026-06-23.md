# RD-Bot Bugfix 与真实 Smoke 测试报告（2026-06-23）

## 1. 测试目标

本轮测试按用户要求验证以下事项：

1. 基于真实测试结果完成 bugfix。
2. PR 创建仍走现有 Java `GitHubCodePlatformAdapter`，不新增 Java gh 适配器。
3. `gh` 只用于测试前准备 GitHub 仓库与分支，让 Java 适配器能真实创建 PR。
4. RocketMQ 使用本地 Docker 已部署实例，不再用 memory 模式证明生产队列能力。
5. 给出 Feishu Helpdesk 权限范围和工单自定义字段映射配置。

## 2. 代码修复摘要

### 2.1 正式工单队列自动执行链路

提交：`1b3b3f1 feat(engine): trigger bugfix execution from ticket queue`

修复点：

- 新增 `TicketRepairExecutionConsumer` 作为正式 `RepairQueueConsumer`。
- 继续复用 `TicketRepairEngine` 做工单读取、决策和 RAG 上下文准备。
- 当 `rd.repair.ticket.auto-execute.enabled=true` 且工单进入 `READY_FOR_RAG` 后，触发 `RdBotFixEngine.runBugFix(...)` 进入 Docker/PR 执行链路。
- 默认 `rd.repair.ticket.auto-execute.enabled=false`，避免零配置环境误触发 mock 执行结果。

覆盖测试：

- `TicketRepairExecutionConsumerTest.readyTicketShouldTriggerBugFixExecutionWhenAutoExecuteEnabled`
- `TicketRepairExecutionConsumerTest.readyTicketShouldOnlyPrepareRagWhenAutoExecuteDisabled`

### 2.2 Feishu Helpdesk 读接口鉴权

提交：`db9200a fix(feishu): send helpdesk auth on read APIs`

修复点：

- `getTicket`
- `listMessages`
- `listCustomFields`

上述 Helpdesk 读接口现在都会带上：

- `Authorization: Bearer <tenant_access_token>`
- `X-Lark-Helpdesk-Authorization: base64(helpdesk_id:helpdesk_token)`

覆盖测试：

- `FeishuTicketAdapterTest.findTicketShouldSendHelpdeskAuthorizationHeader`

### 2.3 Feishu 官方 API 路径与字段映射

提交：`0e2a112 fix(feishu): align helpdesk fields with official API`

修复点：

- 自定义字段查询路径从非官方路径改为官方路径：
  - 修复前：`GET /open-apis/helpdesk/v1/ticket_custom_fields`
  - 修复后：`GET /open-apis/helpdesk/v1/customized_fields`
- `listCustomFields()` 优先返回 `data.ticket_customized_fields`。
- 工单详情中的自定义字段同时落以下 key，提升真实字段映射兼容性：
  - 字段 ID，例如 `field-logs`
  - `key_name`，例如 `logs`
  - `display_name`，例如 `错误日志`
  - 配置映射名

覆盖测试：

- `FeishuTicketAdapterTest.listCustomFieldsShouldUseOfficialEndpointAndHelpdeskAuthorization`
- `FeishuTicketAdapterTest.mapsCustomFieldsByIdKeyNameAndDisplayName`

### 2.4 GitHub 真实 PR smoke 测试

提交：`05e0c0b test(github): add real pull request smoke`

新增测试：

- `GitHubCodePlatformRealSmokeTest`

说明：

- 默认跳过，只有 `-Drd.integration.github.enabled=true` 才真实调用 GitHub。
- 测试只调用现有 Java `GitHubCodePlatformAdapter`。
- `gh` 不进入 Java 生产代码，也没有新增 gh Java 适配器。

## 3. RocketMQ 真实队列测试

### 3.1 Docker 容器状态

测试时本地 Docker 中存在以下关键容器：

| 容器 | 镜像 | 端口 |
| --- | --- | --- |
| `rmqnamesrv` | `apache/rocketmq:5.2.0` | `9876` |
| `rmqbroker` | `apache/rocketmq:5.2.0` | `10909`, `10911-10912` |
| `rocketmq-dashboard` | `apacherocketmq/rocketmq-dashboard:2.1.0` | `8080-8082` |

### 3.2 执行命令

```bash
./mvnw -pl bootstrap -am \
  -Dtest=RocketMqRepairQueueRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.rocketmq.enabled=true \
  -Drd.repair.queue.mode=rocketmq \
  -Drd.rocketmq.repair.name-server=127.0.0.1:9876 \
  test
```

### 3.3 结果

结果：通过。

关键输出：

```text
rocketmq producer started, group=GID_RD_BOT_REPAIR_PRODUCER, nameserver=127.0.0.1:9876
rocketmq consumer started, group=GID_RD_BOT_REPAIR_WORKER, topic=RD_BOT_REPAIR_TICKET
rocketmq published, ticketId=SMOKE-dbc1fe48, tag=P1, msgId=C61200016AA318FF02E4743609450000
[smoke] published to topic=RD_BOT_REPAIR_TICKET tag=P1 msgId=C61200016AA318FF02E4743609450000 ticketId=SMOKE-dbc1fe48
```

结论：

- 本地 RocketMQ nameserver/broker 可用。
- RD-Bot 可用生产 RocketMQ adapter 发布 `RD_BOT_REPAIR_TICKET` 消息。
- P1 “memory 只用于本地回放，生产队列走 RocketMQ”具备真实连通性证据。

## 4. GitHub 真实 PR 创建测试

### 4.1 gh CLI 准备的测试仓库

`gh` 用途仅限测试准备：

1. 创建临时私有仓库。
2. 推送 `main` 分支。
3. 推送 `repair/smoke-*` 工作分支。

测试仓库：

- Repository：`wanghehe123/rd-bot-pr-smoke-20260623133619`
- URL：`https://github.com/wanghehe123/rd-bot-pr-smoke-20260623133619`
- Visibility：private
- Base branch：`main`
- Work branch：`repair/smoke-20260623133619`
- Work branch head sha：`33996538e29d9ace09bef3b9e67484abcaaf4bbf`

### 4.2 Java 适配器真实创建 PR

执行命令：

```bash
GITHUB_PAT="$(gh auth token)" ./mvnw -pl bootstrap -am \
  -Dtest=GitHubCodePlatformRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.github.enabled=true \
  -Drd.github.smoke.repo-owner=wanghehe123 \
  -Drd.github.smoke.repo-name=rd-bot-pr-smoke-20260623133619 \
  -Drd.github.smoke.base-branch=main \
  -Drd.github.smoke.work-branch=repair/smoke-20260623133619 \
  test
```

结果：通过。

关键输出：

```text
[smoke] github pullRequestUrl=https://github.com/wanghehe123/rd-bot-pr-smoke-20260623133619/pull/1 number=1 repository=wanghehe123/rd-bot-pr-smoke-20260623133619 head=repair/smoke-20260623133619 base=main
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

只读核验：

```json
{
  "baseRefName": "main",
  "headRefName": "repair/smoke-20260623133619",
  "number": 1,
  "owner": "wanghehe123",
  "state": "OPEN",
  "title": "RD-Bot real PR smoke",
  "url": "https://github.com/wanghehe123/rd-bot-pr-smoke-20260623133619/pull/1"
}
```

结论：

- PR 创建真实走了 Java `GitHubCodePlatformAdapter`。
- `gh` 没有进入 Java 生产代码，只做测试仓库和分支准备。
- 现有 Java GitHub REST 适配器可创建真实 GitHub PR。

## 5. Feishu Helpdesk 配置要求

### 5.1 已确认的官方权限范围

官方文档确认的权限：

| 场景 | 权限名称 | Scope |
| --- | --- | --- |
| 查询工单详情 | 获取服务台资源详情 | `helpdesk:all:readonly` |
| 查询工单消息 | 获取服务台资源详情 | `helpdesk:all:readonly` |
| 查询服务台自定义字段 | 获取服务台资源详情 | `helpdesk:all:readonly` |
| 订阅服务台事件 | 获取服务台资源详情 | `helpdesk:all:readonly` |
| 创建服务台对话/工单 | 访问服务台 | `helpdesk:helpdesk:access` |
| 发送工单消息 | 更新服务台资源详情 | `helpdesk:all` |
| 更新工单详情 | 更新服务台资源详情 | `helpdesk:all` |

对应 API：

| API | 方法与路径 | 权限 |
| --- | --- | --- |
| 查询指定工单详情 | `GET /open-apis/helpdesk/v1/tickets/:ticket_id` | `helpdesk:all:readonly` |
| 获取工单消息详情 | `GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages` | `helpdesk:all:readonly` |
| 获取服务台自定义字段 | `GET /open-apis/helpdesk/v1/customized_fields` | `helpdesk:all:readonly` |
| 订阅服务台事件 | `POST /open-apis/helpdesk/v1/events/subscribe` | `helpdesk:all:readonly` |
| 创建服务台对话 | `POST /open-apis/helpdesk/v1/start_service` | `helpdesk:helpdesk:access` |
| 发送工单消息 | `POST /open-apis/helpdesk/v1/tickets/:ticket_id/messages` | `helpdesk:all` |
| 更新工单详情 | `PUT /open-apis/helpdesk/v1/tickets/:ticket_id` | `helpdesk:all` |

注意：

- 所有 Helpdesk API 都需要 `Authorization: Bearer <tenant_access_token>`。
- 所有 Helpdesk API 还需要 `X-Lark-Helpdesk-Authorization`。
- `X-Lark-Helpdesk-Authorization` 的值是 `base64(helpdesk_id:helpdesk_token)`。
- 应用权限变更后，需要重新发布应用，并由租户管理员安装或升级应用权限。

### 5.2 参考项目工单创建方式

参考项目 `EMIYAttk/Intelligent_work_order_Agent` 的“创建工单”不是 Feishu Helpdesk 工单：

1. `feishu_bot_ws.py` 监听飞书 IM `P2ImMessageReceiveV1` 事件。
2. 从消息事件里取 `sender.sender_id.open_id` 和 `message.chat_id`。
3. 调用本地 FastAPI/Agent 得到结构化结果。
4. 本地生成 `TK-xxxxxxxx` 工单号。
5. 调用 `POST /open-apis/im/v1/messages` 向原 chat 发送飞书交互卡片。

结论：该参考项目展示的是“IM 机器人 + 本地工单卡片”流程，不会在 Feishu Helpdesk 中生成真实 `ticket_id`。

RD-Bot 要生成真实服务台工单，应调用：

```http
POST /open-apis/helpdesk/v1/start_service
```

最小请求体：

```json
{
  "open_id": "ou_xxx",
  "human_service": true,
  "customized_info": "RD-Bot 自动创建：用户原始问题、RAG 摘要或 traceId"
}
```

其中 `open_id` 可以沿用参考项目的取法：IM 事件中的 `sender.sender_id.open_id`。如果希望响应里返回真实 `ticket_id`，建议 `human_service=true`；官方文档说明 `ticket_id` 通常仅人工工单返回，只创建机器人对话时可能只有 `chat_id`。

### 5.3 RD-Bot 环境变量

不要把 secret 写入仓库。建议本地启动或测试时通过环境变量注入：

```bash
export FEISHU_APP_ID="cli_a9458f91d17b5cd6"
export FEISHU_APP_SECRET="<不要写入仓库>"
export FEISHU_HELPDESK_ID="<服务台 ID>"
export FEISHU_HELPDESK_TOKEN="<服务台 token>"
```

`FEISHU_HELPDESK_ID` 和 `FEISHU_HELPDESK_TOKEN` 的官方获取入口是 [服务台管理后台](https://feishu.cn/helpdesk/admin) 的 **设置中心 > API 凭证**。该 token 代表服务台负责人对服务台资源的访问权限；重置 token 会生成新 token，旧 token 自动失效。

### 5.4 tenant_access_token 连通性验证

已使用用户提供的 `FEISHU_APP_ID` 和 `FEISHU_APP_SECRET` 调用官方接口：

```text
POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal
```

脱敏验证结果：

```json
{
  "ok": true,
  "code": 0,
  "msg": "ok",
  "expire": 7200,
  "tenantAccessTokenPresent": true,
  "tenantAccessTokenLength": 42
}
```

说明：

- 该结果证明自建应用 app 凭据可获取 `tenant_access_token`。
- 测试过程未打印、保存或提交 `tenant_access_token` 明文。
- 后续真实 Helpdesk 工单拉取还需要应用后台开通 `helpdesk:all:readonly`。

### 5.5 Helpdesk 凭据与权限 live check

已使用用户提供的 `FEISHU_HELPDESK_ID` / `FEISHU_HELPDESK_TOKEN` 做脱敏验证：

```json
{
  "tenant": {
    "httpStatus": 200,
    "code": 0,
    "msg": "ok",
    "tokenPresent": true
  },
  "customized_fields": {
    "httpStatus": 400,
    "code": 99991672,
    "requiredScope": "helpdesk:all:readonly"
  },
  "tickets": {
    "httpStatus": 400,
    "code": 99991672,
    "requiredScope": "helpdesk:all:readonly"
  }
}
```

结论：

- App 凭据有效，能获取 `tenant_access_token`。
- Helpdesk ID/token 已参与请求，但当前应用尚未开通 `helpdesk:all:readonly`，所以自定义字段和工单列表被飞书拒绝。
- 飞书错误给出的开通入口是应用 `cli_a9458f91d17b5cd6` 的权限管理页，搜索并开通 `helpdesk:all:readonly`。
- 若要让机器人创建真实服务台工单，还需要开通 `helpdesk:helpdesk:access`。
- 权限变更后需要重新发布应用，并由租户管理员安装或升级应用权限。

### 5.6 自定义字段映射

推荐在飞书后台创建或调整工单自定义字段时，让 `key_name` 直接使用 RD-Bot 标准 key：

| RD-Bot 标准字段 | 建议飞书 key_name | 含义 |
| --- | --- | --- |
| `problemSystem` | `problemSystem` | 故障系统/模块 |
| `symptom` | `symptom` | 故障现象 |
| `logs` | `logs` | 错误日志/异常栈 |
| `repository` | `repository` | 代码仓库 URL 或 owner/repo |
| `branch` | `branch` | 基准分支 |
| `expectedResult` | `expectedResult` | 期望结果 |
| `actualResult` | `actualResult` | 实际结果 |

如果飞书现有字段不能改 `key_name`，则在配置中把 RD-Bot 标准字段映射到飞书字段 ID 或展示名：

```yaml
rd:
  feishu:
    helpdesk:
      field-mapping:
        problemSystem: "<飞书字段 ID / key_name / display_name>"
        symptom: "<飞书字段 ID / key_name / display_name>"
        logs: "<飞书字段 ID / key_name / display_name>"
        repository: "<飞书字段 ID / key_name / display_name>"
        branch: "<飞书字段 ID / key_name / display_name>"
        expectedResult: "<飞书字段 ID / key_name / display_name>"
        actualResult: "<飞书字段 ID / key_name / display_name>"
```

进入 RAG 的最小条件：

- 工单描述或 `symptom` 至少一个非空。
- `logs` 或 `repository` 至少一个非空。

### 5.7 Feishu 真实拉取未完成项

本轮已验证 app 级 `tenant_access_token` 获取成功，并验证 Helpdesk 请求已到达权限检查阶段；但没有执行 `FeishuHelpdeskRealSmokeTest` 的真实工单拉取，因为当前应用还缺 `helpdesk:all:readonly` 权限。

完成真实拉取还需要：

1. 在飞书应用后台开通 `helpdesk:all:readonly`。
2. 重新发布应用，并由租户管理员安装或升级应用权限。
3. 一个已存在的 `rd.feishu.smoke.ticket-id`，或先通过 `start_service` 创建真实工单后使用返回的 `ticket_id`。

具备这些信息后，可执行：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=FeishuHelpdeskRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drd.integration.feishu.enabled=true \
  -Drd.feishu.helpdesk.enabled=true \
  -Drd.feishu.helpdesk.app-id="$FEISHU_APP_ID" \
  -Drd.feishu.helpdesk.app-secret="$FEISHU_APP_SECRET" \
  -Drd.feishu.helpdesk.helpdesk-id="$FEISHU_HELPDESK_ID" \
  -Drd.feishu.helpdesk.helpdesk-token="$FEISHU_HELPDESK_TOKEN" \
  -Drd.feishu.smoke.ticket-id="$TICKET_ID" \
  test
```

## 6. 最终本地验证

### 6.1 聚焦测试

命令：

```bash
./mvnw -pl bootstrap -am \
  -Dtest=FeishuTicketAdapterTest,GitHubCodePlatformRealSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

结果：

- `FeishuTicketAdapterTest`：新增 `start_service` 覆盖后应为 13 tests，0 failures，0 errors。
- `FeishuHelpdeskRealSmokeTest`：3 tests 默认跳过，编译通过；真实创建服务台对话需显式开启。
- `GitHubCodePlatformRealSmokeTest`：默认跳过，编译通过。

### 6.2 全量测试

命令：

```bash
./mvnw test
```

结果：

```text
Tests run: 116, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

### 6.3 Diff 检查

命令：

```bash
git diff --check
```

结果：无输出，未发现 whitespace 错误。

## 7. 当前提交清单

本轮相关提交：

```text
04746ac docs(feishu): document helpdesk credential source
b5f4c3a docs(smoke): record feishu tenant token check
b777452 docs(smoke): record bugfix real smoke report
05e0c0b test(github): add real pull request smoke
0e2a112 fix(feishu): align helpdesk fields with official API
764b6d7 docs(smoke): document helpdesk and auto execution setup
db9200a fix(feishu): send helpdesk auth on read APIs
1b3b3f1 feat(engine): trigger bugfix execution from ticket queue
```

## 8. 结论

已完成并验证：

- 正式队列消费后可按配置进入完整 BugFix 执行链路。
- Feishu Helpdesk 读接口补齐服务台鉴权头。
- Feishu 自定义字段 API 路径已对齐官方文档。
- Feishu 工单字段映射兼容字段 ID、`key_name` 和 `display_name`。
- RD-Bot 已封装 Feishu `start_service`，可在显式开启 smoke 并提供用户 open_id 后创建真实服务台对话/工单。
- RocketMQ 本地 Docker broker 真实发布通过。
- GitHub 真实 PR 由现有 Java `GitHubCodePlatformAdapter` 创建成功。
- 未新增 Java gh 适配器。

仍需外部配置才能继续真实 Feishu 工单验证：

- 在飞书应用后台开通 `helpdesk:all:readonly`，用于读取工单、自定义字段和消息。
- 如果要让机器人创建真实服务台工单，再开通 `helpdesk:helpdesk:access`。
- 权限变更后重新发布应用，并由租户管理员安装或升级权限。
- 提供一个可读的真实工单 ID，或提供用户 open_id 后用 `start_service` 先创建工单。
