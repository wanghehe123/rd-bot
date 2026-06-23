# Feishu Helpdesk 权限与字段映射检查清单

本文档用于把 RD-Bot 接入真实飞书服务台前的配置项一次性对齐。不要把
`FEISHU_APP_SECRET`、`FEISHU_HELPDESK_TOKEN` 或 `tenant_access_token` 写入仓库。

## 1. 必开权限

在飞书开放平台进入应用 `cli_a9458f91d17b5cd6` 的 **权限管理**，按目标能力开通：

| 目标能力 | 必要性 | Scope | RD-Bot 用到的接口 |
| --- | --- | --- | --- |
| 读取工单、消息、自定义字段 | 必需 | `helpdesk:all:readonly` | `GET /open-apis/helpdesk/v1/tickets/:ticket_id`、`GET /open-apis/helpdesk/v1/tickets/:ticket_id/messages`、`GET /open-apis/helpdesk/v1/customized_fields` |
| 机器人创建真实服务台对话/工单 | 需要机器人主动建单时必需 | `helpdesk:helpdesk:access` | `POST /open-apis/helpdesk/v1/start_service` |
| 回写工单消息或状态 | 需要回写时必需 | `helpdesk:all` | `POST /open-apis/helpdesk/v1/tickets/:ticket_id/messages`、`PUT /open-apis/helpdesk/v1/tickets/:ticket_id` |

权限变更后必须重新发布应用，并由租户管理员安装或升级应用权限；只在开发后台点选权限但不发布，真实 OpenAPI 仍会返回权限不足。

当前 live check 结果：

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

这说明应用凭据和 Helpdesk 凭据已经能到达飞书权限检查，当前阻塞不是 RD-Bot 代码问题，而是应用还缺 `helpdesk:all:readonly`。

## 2. Helpdesk API 凭据

服务台凭据在 **服务台管理后台 > 设置中心 > API 凭证** 获取：

- `FEISHU_HELPDESK_ID`
- `FEISHU_HELPDESK_TOKEN`

RD-Bot 会在请求中生成：

```text
X-Lark-Helpdesk-Authorization = base64(helpdesk_id:helpdesk_token)
```

该 header 与 `Authorization: Bearer <tenant_access_token>` 必须同时存在；只提供 app 凭据无法读取 Helpdesk 资源。

## 3. 字段映射配置方式

RD-Bot 标准字段如下：

| RD-Bot 标准字段 | 作用 | 是否建议必填 |
| --- | --- | --- |
| `problemSystem` | 故障系统/模块 | 建议 |
| `symptom` | 故障现象 | 建议 |
| `triggerWay` | 触发方式 | 可选 |
| `logs` | 错误日志/异常栈 | `logs` 与 `repository` 至少一个必填 |
| `repository` | 代码仓库 URL 或 `owner/repo` | `logs` 与 `repository` 至少一个必填 |
| `branch` | 基准分支 | 建议，缺省时走配置默认 base branch |
| `expectedResult` | 期望结果 | 建议 |
| `actualResult` | 实际结果 | 建议 |

推荐做法：在飞书服务台后台创建工单自定义字段时，让字段的 `key_name` 与 RD-Bot 标准字段一致。这样 `application.yaml` 默认配置即可工作：

```yaml
rd:
  feishu:
    helpdesk:
      field-mapping:
        problemSystem: problemSystem
        symptom: symptom
        triggerWay: triggerWay
        logs: logs
        repository: repository
        branch: branch
        expectedResult: expectedResult
        actualResult: actualResult
```

如果现有字段不能改 `key_name`，就把右侧值改成飞书字段的 `field_id`、`ticket_customized_field_id`、`key_name` 或 `display_name`。当前 mapper 会把这几种名字都放进 `TicketSnapshot.customFields`：

```yaml
rd:
  feishu:
    helpdesk:
      field-mapping:
        problemSystem: "field_system_id"
        symptom: "故障现象"
        triggerWay: "triggerWay"
        logs: "field_logs_id"
        repository: "代码仓库"
        branch: "branch"
        expectedResult: "期望结果"
        actualResult: "实际结果"
```

进入 RAG 修复流程的最小信息条件：

- 工单描述 `desc/description` 或 `symptom` 至少一个非空。
- `logs` 或 `repository` 至少一个非空。

如果这两条不满足，RD-Bot 会认为工单信息不足，不会进入有效 RAG 修复。

## 4. 机器人如何创建真实工单

参考项目 `EMIYAttk/Intelligent_work_order_Agent` 的建单方式是本地生成 `TK-*` 编号并发 IM 卡片，不会创建飞书服务台真实 `ticket_id`。

RD-Bot 创建真实服务台对话/工单应调用：

```http
POST /open-apis/helpdesk/v1/start_service
Authorization: Bearer <tenant_access_token>
X-Lark-Helpdesk-Authorization: base64(helpdesk_id:helpdesk_token)
Content-Type: application/json; charset=utf-8
```

最小请求体：

```json
{
  "open_id": "ou_xxx",
  "human_service": true,
  "customized_info": "RD-Bot 自动创建：用户原始问题、RAG 摘要或 traceId"
}
```

配置要点：

- `open_id` 来自飞书 IM 事件中的 `sender.sender_id.open_id`。
- 要返回真实 `ticket_id`，使用 `human_service=true`。
- 用户必须在该服务台可见范围内，否则会被飞书拒绝。
- 应用必须开通 `helpdesk:helpdesk:access`。

## 5. 验证命令

### 5.1 权限开通前的只读检查

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

预期：

- 权限未开通时，飞书返回 `99991672` 和缺少的 scope。
- 开通 `helpdesk:all:readonly` 并发布安装后，应能读取指定工单、消息和自定义字段。

### 5.2 机器人真实创建工单

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
  -Drd.feishu.smoke.create-ticket.enabled=true \
  -Drd.feishu.smoke.open-id="$USER_OPEN_ID" \
  test
```

预期：

- 开通 `helpdesk:helpdesk:access` 后，接口返回 `chat_id`。
- 使用 `human_service=true` 时，人工工单路径通常还会返回 `ticket_id`。
