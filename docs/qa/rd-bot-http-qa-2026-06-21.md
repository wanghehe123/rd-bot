# RD-Bot 前端与 RAG HTTP QA 报告

## 测试结论

- 测试日期：2026-06-21
- 测试方式：启动本地 Spring Boot 服务后，通过真实 HTTP 请求调用接口，不使用 MockMvc。
- 服务地址：`http://localhost:18080`
- 最终结果：`89 / 89` 通过，失败 `0`。
- 覆盖范围：`bootstrap` 模块中控制器暴露的前端路由、静态资源、知识库管理、摄取管理、RAG v3、RAG trace、会话、反馈、意图树、用户管理、查询改写映射、示例问题和测试通道接口。

## 测试环境

- 工作目录：`/Users/wish233/Documents/RD-Bot`
- 启动模块：`bootstrap`
- Java 运行时：Spring Boot 日志显示 `Java 23.0.2`
- Spring Boot：`3.5.7`
- 对象存储模式：`rd.storage.mode=memory`
- Mock provider key：`rd.ai.providers.mock.api-key=abcdef1234567890`，接口返回已脱敏为 `abcdef***7890`

## 执行命令

```bash
./mvnw install -DskipTests
./mvnw -f bootstrap/pom.xml spring-boot:run -Dspring-boot.run.jvmArguments='-Dserver.port=18080 -Drd.storage.mode=memory -Drd.ai.providers.mock.api-key=abcdef1234567890'
node /private/tmp/rd_bot_http_qa.js > /private/tmp/rd_bot_http_qa_results.json
```

执行过程备注：
- 直接对父 POM 执行 `spring-boot:run` 会失败，原因是父 POM 没有 main class。
- 直接对 `bootstrap/pom.xml` 启动时曾命中本地 Maven 仓库里的旧 `engine` 产物，导致迁包后的类找不到。
- 已通过 `./mvnw install -DskipTests` 刷新多模块本地产物，然后成功启动 `bootstrap` 并完成最终 HTTP QA。
- 首次脚本断言曾把 CSS 类名误写为 `.app-shell`；实际前端类名为 `.admin-shell`。修正脚本断言后重新执行了完整测试，以下记录只采用最终完整通过的 HTTP 结果。

## 最终运行摘要

```json
{
  "startedAt": "2026-06-21T03:20:51.932Z",
  "finishedAt": "2026-06-21T03:20:52.026Z",
  "total": 89,
  "passed": 89,
  "failed": 0,
  "kbId": "kb-2",
  "docId": "doc-4",
  "pipelineId": "pipeline-2",
  "taskId": "ingestion-task-3",
  "uploadTaskId": "ingestion-task-4",
  "chatTaskId": "task_redacted",
  "conversationId": "qa-http-conversation",
  "traceId": "trace-ticket-prompt-flow"
}
```

## 关键功能证据

### 前端

- `/admin`、`/admin/dashboard`、`/admin/knowledge`、`/admin/intent-tree`、`/admin/intent-list`、`/admin/users` 均返回 SPA shell。
- `/admin/knowledge/kb-2` 和 `/admin/knowledge/kb-2/docs/doc-4` 均返回 SPA shell，证明动态详情路由可由单体服务承接。
- `/admin/admin-knowledge.css` 返回 `text/css`，且包含 `.admin-shell`。
- `/admin/admin-knowledge.js` 返回 `text/javascript`，且包含前端 API client。

### RAG v3

真实 SSE 调用：

```text
GET /rag/v3/chat?question=支付系统创建订单失败，orders.amount 为空怎么修复？&conversationId=qa-http-conversation&deepThinking=false
```

响应关键片段：

```text
event: meta
data: {"conversationId":"qa-http-conversation","taskId":"task_redacted",...}

event: delta
data: 已经定位到 OrderService.create 缺少 orders.amount 校验。

event: done
data: {"status":"DONE"}
```

后续验证：
- `GET /rag/v3/tasks/task_redacted` 返回 `status=DONE`。
- `POST /rag/v3/stop?taskId=qa-stop-task` 返回 `STOPPED`。
- `GET /rag/v3/tasks/qa-stop-task` 返回 `CANCELLED`。

### RAG Trace

- `POST /test/rag/prompt-flow` 生成 `trace-ticket-prompt-flow`。
- `GET /rag/traces/runs` 可查询到该 trace。
- `GET /rag/traces/runs/trace-ticket-prompt-flow` 返回 `status=SUCCESS`。
- `GET /rag/traces/runs/trace-ticket-prompt-flow/nodes` 返回 trace 节点，覆盖 `INGESTION`、`RETRIEVAL`、`REWRITE`、`PROMPT`。

## 接口逐项结果

| 用例 | 方法 | 路径 | 状态码 | 结果 | 断言 |
| --- | --- | --- | --- | --- | --- |
| 前端入口 /admin | GET | `/admin` | 200 | 通过 | HTML title found |
| 前端 Dashboard 路由 | GET | `/admin/dashboard` | 200 | 通过 | SPA shell returned |
| 前端知识库路由 | GET | `/admin/knowledge` | 200 | 通过 | SPA shell returned |
| 前端意图树路由 | GET | `/admin/intent-tree` | 200 | 通过 | SPA shell returned |
| 前端意图列表路由 | GET | `/admin/intent-list` | 200 | 通过 | SPA shell returned |
| 前端用户管理路由 | GET | `/admin/users` | 200 | 通过 | SPA shell returned |
| 前端 CSS 静态资源 | GET | `/admin/admin-knowledge.css` | 200 | 通过 | CSS loaded |
| 前端 JS 静态资源 | GET | `/admin/admin-knowledge.js` | 200 | 通过 | JS loaded |
| RAG 运行配置 | GET | `/rag/settings` | 200 | 通过 | runtime settings returned |
| 创建知识库 | POST | `/knowledge-base` | 200 | 通过 | created kb-2 |
| 前端知识库详情路由 | GET | `/admin/knowledge/kb-2` | 200 | 通过 | SPA shell returned |
| 分页查询知识库 | GET | `/knowledge-base?current=1&size=10&name=QA` | 200 | 通过 | created KB present in page |
| 查询知识库详情 | GET | `/knowledge-base/kb-2` | 200 | 通过 | KB detail matched |
| 写入知识文档 | POST | `/knowledge-base/kb-2/docs/write` | 200 | 通过 | created doc-4 |
| 前端文档详情路由 | GET | `/admin/knowledge/kb-2/docs/doc-4` | 200 | 通过 | SPA shell returned |
| 查询知识库文档列表 | GET | `/knowledge-base/kb-2/docs` | 200 | 通过 | document listed |
| 查询文档详情 | GET | `/knowledge-base/docs/doc-4` | 200 | 通过 | document detail matched |
| 搜索文档 | GET | `/knowledge-base/docs/search?keyword=OrderService&limit=4` | 200 | 通过 | document found by keyword |
| 查询文档 Chunk | GET | `/knowledge-base/docs/doc-4/chunks` | 200 | 通过 | 1 chunks returned |
| 预览文档 | GET | `/knowledge-base/docs/doc-4/preview` | 200 | 通过 | preview content matched |
| 查询文档切分日志 | GET | `/knowledge-base/docs/doc-4/chunk-logs` | 200 | 通过 | 4 logs returned |
| 设置文档 enabled=false | PATCH | `/knowledge-base/docs/doc-4/enabled?enabled=false` | 200 | 通过 | document disabled |
| 设置文档 enable=true | PATCH | `/knowledge-base/docs/doc-4/enable?value=true` | 200 | 通过 | document enabled |
| 设置 Chunk enabled=false | PATCH | `/knowledge-base/docs/chunks/doc-4-0/enabled?enabled=false` | 200 | 通过 | chunk disabled |
| 设置指定文档 Chunk enable=true | PATCH | `/knowledge-base/docs/doc-4/chunks/doc-4-0/enable?value=true` | 200 | 通过 | chunk enabled |
| 新增手工 Chunk | POST | `/knowledge-base/docs/doc-4/chunks` | 200 | 通过 | manual chunk created |
| 更新手工 Chunk | PUT | `/knowledge-base/docs/doc-4/chunks/qa-manual-chunk` | 200 | 通过 | manual chunk updated |
| 批量禁用 Chunk | PATCH | `/knowledge-base/docs/doc-4/chunks/batch-enable?value=false` | 200 | 通过 | one chunk updated |
| 删除手工 Chunk | DELETE | `/knowledge-base/docs/doc-4/chunks/qa-manual-chunk` | 200 | 通过 | manual chunk deleted |
| 更新文档元数据 | PUT | `/knowledge-base/docs/doc-4` | 200 | 通过 | document metadata updated |
| 管理后台概览 | GET | `/admin/overview` | 200 | 通过 | overview returned |
| 创建摄取管道 | POST | `/ingestion/pipelines` | 200 | 通过 | created pipeline-2 |
| 分页查询摄取管道 | GET | `/ingestion/pipelines?pageNo=1&pageSize=10&keyword=QA` | 200 | 通过 | pipeline listed |
| 查询摄取管道详情 | GET | `/ingestion/pipelines/pipeline-2` | 200 | 通过 | pipeline detail matched |
| 更新摄取管道 | PUT | `/ingestion/pipelines/pipeline-2` | 200 | 通过 | pipeline updated |
| 创建摄取任务 | POST | `/ingestion/tasks` | 200 | 通过 | completed ingestion-task-3 |
| 查询摄取任务详情 | GET | `/ingestion/tasks/ingestion-task-3` | 200 | 通过 | task detail matched |
| 查询摄取任务节点 | GET | `/ingestion/tasks/ingestion-task-3/nodes` | 200 | 通过 | task nodes returned |
| 分页查询摄取任务 | GET | `/ingestion/tasks?pageNo=1&pageSize=10&status=COMPLETED` | 200 | 通过 | task listed |
| 上传文件并创建摄取任务 | POST | `/ingestion/tasks/upload?pipelineId=default-document-pipeline&knowledgeBaseId=kb-2&knowledgeType=api&chunkingMode=STRUCTURE_AWARE&chunkSize=72&overlapSize=8` | 200 | 通过 | completed ingestion-task-4 |
| 创建查询改写映射 | POST | `/mappings` | 200 | 通过 | created 4 |
| 查询改写映射列表 | GET | `/mappings` | 200 | 通过 | mapping listed |
| 查询改写映射详情 | GET | `/mappings/4` | 200 | 通过 | mapping detail matched |
| 更新查询改写映射 | PUT | `/mappings/4` | 200 | 通过 | mapping updated |
| 创建示例问题 | POST | `/sample-questions` | 200 | 通过 | created 2 |
| 分页查询示例问题 | GET | `/sample-questions?keyword=金额&current=1&size=10` | 200 | 通过 | sample listed |
| 查询示例问题详情 | GET | `/sample-questions/2` | 200 | 通过 | sample detail matched |
| RAG 欢迎示例问题 | GET | `/rag/sample-questions` | 200 | 通过 | welcome list contains sample |
| 更新示例问题 | PUT | `/sample-questions/2` | 200 | 通过 | sample updated |
| 创建意图节点 | POST | `/intent-tree` | 200 | 通过 | created 5 |
| 创建批量意图节点 A | POST | `/intent-tree` | 200 | 通过 | created 6 |
| 创建批量意图节点 B | POST | `/intent-tree` | 200 | 通过 | created 7 |
| 查询意图树 | GET | `/intent-tree/trees` | 200 | 通过 | intent listed |
| 更新意图节点 | PUT | `/intent-tree/5` | 200 | 通过 | intent updated |
| 批量启用意图 | POST | `/intent-tree/batch/enable` | 200 | 通过 | batch enable done |
| 批量禁用意图 | POST | `/intent-tree/batch/disable` | 200 | 通过 | batch disable done |
| 批量删除意图 | POST | `/intent-tree/batch/delete` | 200 | 通过 | batch delete done |
| 创建用户 | POST | `/users` | 200 | 通过 | created user-3 |
| 分页查询用户 | GET | `/users?current=1&size=10&keyword=qa-user` | 200 | 通过 | user listed |
| 更新用户 | PUT | `/users/user-3` | 200 | 通过 | user updated |
| 修改当前用户密码 | PUT | `/user/password` | 200 | 通过 | password update accepted |
| RAG v3 SSE 对话 | GET | `/rag/v3/chat?question=%E6%94%AF%E4%BB%98%E7%B3%BB%E7%BB%9F%E5%88%9B%E5%BB%BA%E8%AE%A2%E5%8D%95%E5%A4%B1%E8%B4%A5%EF%BC%8Corders.amount%20%E4%B8%BA%E7%A9%BA%E6%80%8E%E4%B9%88%E4%BF%AE%E5%A4%8D%EF%BC%9F&conversationId=qa-http-conversation&deepThinking=false` | 200 | 通过 | SSE meta/delta/done returned |
| 查询 RAG 任务状态 | GET | `/rag/v3/tasks/task_redacted` | 200 | 通过 | RAG task DONE |
| 停止 RAG 任务 | POST | `/rag/v3/stop?taskId=qa-stop-task` | 200 | 通过 | stop accepted |
| 查询停止后的任务状态 | GET | `/rag/v3/tasks/qa-stop-task` | 200 | 通过 | stopped task recorded |
| 查询会话列表 | GET | `/conversations` | 200 | 通过 | conversation listed |
| 查询会话消息 | GET | `/conversations/qa-http-conversation/messages` | 200 | 通过 | 2 messages returned |
| 提交消息反馈 | POST | `/conversations/messages/qa-http-conversation%232/feedback` | 200 | 通过 | feedback saved |
| 重命名会话 | PUT | `/conversations/qa-http-conversation` | 200 | 通过 | conversation renamed |
| RAG 全链路测试通道 | POST | `/test/rag/full-flow` | 200 | 通过 | full flow OK |
| RAG 对话记忆测试通道 | POST | `/test/rag/chat-flow` | 200 | 通过 | chat memory flow OK |
| RAG Prompt 流程测试通道 | POST | `/test/rag/prompt-flow` | 200 | 通过 | prompt flow trace trace-ticket-prompt-flow |
| 默认摄取流程测试通道 | POST | `/test/ingestion/default-pipeline` | 200 | 通过 | default ingestion flow OK |
| 查询 RAG Trace 列表 | GET | `/rag/traces/runs` | 200 | 通过 | trace listed |
| 查询 RAG Trace 详情 | GET | `/rag/traces/runs/trace-ticket-prompt-flow` | 200 | 通过 | trace detail matched |
| 查询 RAG Trace 节点 | GET | `/rag/traces/runs/trace-ticket-prompt-flow/nodes` | 200 | 通过 | trace nodes returned |
| 删除会话 | DELETE | `/conversations/qa-http-conversation` | 200 | 通过 | conversation deleted |
| 删除用户 | DELETE | `/users/user-3` | 200 | 通过 | user deleted |
| 删除意图节点 | DELETE | `/intent-tree/5` | 200 | 通过 | intent deleted |
| 删除批量意图节点 A | DELETE | `/intent-tree/6` | 200 | 通过 | batch intent A deleted |
| 删除示例问题 | DELETE | `/sample-questions/2` | 200 | 通过 | sample deleted |
| 删除查询改写映射 | DELETE | `/mappings/4` | 200 | 通过 | mapping deleted |
| 删除摄取管道 | DELETE | `/ingestion/pipelines/pipeline-2` | 200 | 通过 | pipeline deleted |
| 删除主文档 | DELETE | `/knowledge-base/docs/doc-4` | 200 | 通过 | main document deleted |
| 删除摄取文档 | DELETE | `/knowledge-base/docs/doc-5` | 200 | 通过 | ingested document deleted |
| 删除上传文档 | DELETE | `/knowledge-base/docs/doc-6` | 200 | 通过 | uploaded document deleted |
| 更新知识库 | PUT | `/knowledge-base/kb-2` | 200 | 通过 | KB updated |
| 删除知识库 | DELETE | `/knowledge-base/kb-2` | 200 | 通过 | KB deleted |
| 删除后查询知识库为空 | GET | `/knowledge-base?current=1&size=10&name=QA支付系统V2` | 200 | 通过 | deleted KB absent |

## 覆盖说明

- 本报告覆盖的是成功路径 HTTP 行为：每类资源均按创建、查询、更新、启停或删除链路进行真实调用。
- 未单独展开异常路径矩阵，例如重复用户名、缺失参数、非法 ID、无效反馈票值等；这些属于负向 QA，可作为下一轮补充。
- 当前服务未启用登录态或鉴权拦截，因此本轮没有认证、授权、权限隔离类测试。
- 对象存储使用内存模式，验证的是上传接口到摄取链路的集成行为，不代表外部 RustFS/S3 连通性。
