# RD-Bot

**AI 驱动的研发交付编排平台**

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

RD-Bot 将工单或研发需求转化为**可治理的自动化交付流水线**。通过任务级 RAG 构建工程上下文，在 Docker 隔离沙箱中运行编码 Agent，自动验证结果并创建可审查 PR，同时记录完整的审计证据链。

> 不是"一个会修 Bug 的聊天机器人"，而是 **AI-native Software Delivery Harness（研发流程自动化控制面）**。

---

## ✨ 核心特性

### 🤖 多角色 Agent 流水线

```
需求评审 → 方案设计 → 编码执行 → QA 验证 → PR 交付
```

- **需求评审 Agent**：判断需求完整性、安全性和可执行性，不通过则阻断后续阶段
- **方案架构 Agent**：基于 RAG 证据生成可执行开发方案
- **编码 Agent**：在 Docker 沙箱中执行代码修改
- **QA Agent**：验证修改正确性，失败则阻断交付

### 📚 任务级 RAG 上下文构建

- 多通道并行检索：向量语义、BM25 关键词、日志中心、代码仓库
- 意图树分类锁定目标系统和知识范围
- 结构化 `ContextPackage`：不只是文本片段，而是包含相关文件、根因假设、验证命令的工程上下文
- 经验沉淀与复用：历史交付经验自动检索注入后续任务

### 🔒 安全隔离执行

- Docker 容器级隔离，Agent 不直接操作宿主机
- 仓库/分支白名单控制
- Secret 扫描：产物和 PR 内容自动检测敏感信息
- Provider 密钥仅通过环境变量注入，零硬编码

### 📋 PR-based 交付（非直接合并）

- Agent 只提交可审查变更，**永远不直接合并主干**
- PR body 自动包含：上下文摘要、风险评估、测试证据、产物链接
- 交付复核通过后才允许创建 PR

### 🛡️ 策略门控与治理

- 高风险动作必须经过 `PolicyGate` 和人工审批
- 完整状态机：25+ 状态覆盖正常流转、失败重试、人工恢复、死信队列
- 告警分类：Provider 降级、阶段失败、QA 阻断、PR 发布失败等
- 全链路审计：每个阶段都有 prompt/result/log 产物和结构化 trace

### 🔄 失败恢复与运营闭环

- 阶段级重试：自动创建新 attempt，保留完整失败历史
- 租约派发：持久化 Job + 租约领取 + 恢复调度，任务不丢失
- 人工接管：`FAILED_NEEDS_HUMAN` 状态支持运营干预后继续
- 经验自动沉淀：成功交付自动提取可复用经验

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      bootstrap (API/适配层)                   │
│  REST API · 管理前端 · Feishu/RocketMQ/GitHub 适配器 · 持久化  │
├─────────────────────────────────────────────────────────────┤
│                      engine (编排控制面)                      │
│  多角色工作流 · 状态机 · 策略门控 · 经验复用 · PR 发布          │
├────────────────────┬────────────────────┬───────────────────┤
│   rag (知识上下文)   │   exec (执行面)     │   skill (技能层)   │
│ 检索·切块·意图树     │ Docker沙箱·Provider │ 可复用能力切片     │
│ 上下文包·Trace      │ 健康治理·熔断       │ 策略判定·安装      │
└────────────────────┴────────────────────┴───────────────────┘
```

**依赖方向**：`bootstrap → engine → rag`，`bootstrap → exec → rag`，`bootstrap → skill → rag`

---

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| PostgreSQL | 14+ |
| Redis | 6+（分布式锁） |
| Docker | 20+（执行沙箱） |
| Node.js | 18+（前端开发，可选） |

### 本地启动

```bash
# 1. 初始化数据库
docker exec -i postgres psql -U postgres -d ragent < bootstrap/src/main/resources/sql/postgres/init.sql

# 2. 构建项目
./mvnw install -DskipTests

# 3. 启动后端（默认端口 18080）
./mvnw -pl bootstrap spring-boot:run

# 4. 访问管理前端
open http://127.0.0.1:18080
```

### 前端开发（可选）

```bash
cd frontend
npm install
npm run dev   # Vite 开发服务器，自动代理 /admin/* 到后端
```

### 环境变量配置

所有敏感配置通过环境变量注入，**零硬编码**：

```bash
# 数据库
export POSTGRES_URL="jdbc:postgresql://127.0.0.1:5432/ragent"
export POSTGRES_USERNAME="postgres"
export POSTGRES_PASSWORD="your-password"

# AI Provider（支持任意 Anthropic/OpenAI 兼容接口）
export LONGCAT_API_KEY="your-api-key"
export MINIMAX_API_KEY="your-api-key"

# GitHub（PR 交付）
export GITHUB_PAT="your-github-token"

# 飞书（工单接入，可选）
export FEISHU_APP_ID="your-app-id"
export FEISHU_APP_SECRET="your-app-secret"
```

---

## 📦 模块说明

| 模块 | 职责 | 关键能力 |
|------|------|----------|
| `rag` | 知识与上下文层 | 文档解析/切块/索引、意图树分类、多通道检索、查询重写、上下文打包、Trace |
| `engine` | 编排控制层 | 多角色工作流、状态机流转、策略门控、经验沉淀、PR 发布、告警 |
| `exec` | 执行层 | Docker 沙箱执行、多 Provider 适配（LongCat/MiniMax/自定义）、熔断降级 |
| `skill` | 技能层 | 可复用能力注册、策略判定、角色/作用域权限控制 |
| `bootstrap` | 启动与适配层 | Spring Boot 入口、REST API、外部系统适配器、PostgreSQL 持久化 |
| `frontend` | 管理前端 | React + Vite + TailwindCSS，任务工作台、知识库管理、评估控制台 |

---

## 🔌 Provider 可替换设计

RD-Bot 不绑定任何特定 AI 模型。Provider 通过配置热插拔：

```yaml
rd:
  executor:
    docker:
      providers:
        - name: long-cat
          protocol: anthropic-compatible
          base-url: https://api.longcat.chat/anthropic
          model: LongCat-2.0
        - name: minimax
          protocol: openai-chat-completions
          base-url: https://api.minimaxi.com/v1
          model: MiniMax-M3
        # 添加任意 OpenAI/Anthropic 兼容 Provider
        - name: your-provider
          protocol: openai-chat-completions
          base-url: https://your-api.com/v1
          model: your-model
```

支持自动 fallback：首选 Provider 失败时自动切换备选。

---

## 📡 核心 API

### 任务管理

```bash
# 创建研发任务
curl -X POST http://127.0.0.1:18080/admin/rd-tasks \
  -H 'Content-Type: application/json' \
  -d '{"title": "修复支付回调状态未更新", "description": "..."}'

# 查询任务状态
curl http://127.0.0.1:18080/admin/rd-tasks/{taskId}

# 查询阶段执行详情
curl http://127.0.0.1:18080/admin/rd-tasks/{taskId}/stages
```

### 知识库管理

```bash
# 创建知识库
curl -X POST http://127.0.0.1:18080/knowledge-base \
  -H 'Content-Type: application/json' \
  -d '{"name": "支付系统", "description": "支付 API 文档"}'

# 写入文档（自动解析、切块、索引）
curl -X POST http://127.0.0.1:18080/knowledge-base/{kbId}/docs/write \
  -H 'Content-Type: application/json' \
  -d '{"sourceName": "api.md", "content": "# 支付 API\n...", "knowledgeType": "api"}'

# RAG 检索
curl -X POST http://127.0.0.1:18080/rag/bugfix/messages \
  -H 'Content-Type: application/json' \
  -d '{"ticketId": "FI-001", "title": "支付回调异常", "description": "..."}'
```

### 管理前端

| 路径 | 功能 |
|------|------|
| `/admin/knowledge` | 知识库列表、文档管理、Chunk 管理 |
| `/admin/tasks` | 任务列表、状态追踪、阶段详情 |
| `/admin/evaluation` | 评估数据集、质量评分、对比分析 |

---

## 🧪 测试

```bash
# 运行全部单元测试
./mvnw test

# 运行特定模块测试
./mvnw -pl engine test
./mvnw -pl exec test

# 集成冒烟测试（需要 PostgreSQL）
./mvnw -pl bootstrap -Dtest=PostgresRdTaskStateAtomicRealSmokeTest test
```

---

## 🗺️ 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Java 21（Virtual Threads） |
| 框架 | Spring Boot 3.5 |
| 持久化 | PostgreSQL + Redis（Redisson 分布式锁） |
| 对象存储 | S3 兼容（RustFS/MinIO） |
| 消息队列 | RocketMQ |
| 执行沙箱 | Docker |
| 前端 | React 18 + Vite + TailwindCSS + Radix UI |
| 构建 | Maven（多模块） |

---

## 📐 设计原则

1. **模型可替换**：AI Provider 是 worker，不是系统核心。任何兼容接口都可接入。
2. **PR-based 交付**：Agent 永远不直接合并代码，所有变更必须经过人类 Review。
3. **证据驱动**：每个阶段产出结构化产物（prompt/result/log/diff），支持完整回放。
4. **端口-适配器架构**：Feishu、GitHub、RocketMQ、模型 SDK 全部通过端口接入，可 Mock 测试。
5. **状态机 + 持久化事件**：跨实例共享状态不依赖 JVM 内存，支持故障恢复。
6. **安全纵深**：Secret 扫描、仓库白名单、分支限制、Docker 网络隔离。

---

## 📄 License

[Apache License 2.0](LICENSE)
# RD-Bot

RD-Bot is a Java 21 / Spring Boot 3 research-and-development automation bot.
It is a single Spring Boot service. The code is split by layers, not by
independent microservices.

## Module Layout

- `rag`: RAG capability layer. It owns parser, chunker, document ingestion,
  intent tree classification, ambiguity guidance, multi-channel retrieval,
  query rewrite, conversation memory, prompt planning, vector store abstractions,
  knowledge base/document/chunk management, ingestion pipeline/task management,
  query-term mapping management, sample-question management, conversation
  management, message-feedback management, intent-tree management, runtime
  settings exposure, lightweight trace records, and context packaging.
- `engine`: engine orchestration layer. It wires RAG, logs, code search, and
  repair context preparation into testable end-to-end flows. It also aggregates
  the lightweight admin overview for the single service.
- `exec`: repair execution layer. It is the placeholder for future repair task
  execution contracts.
- `skill`: skill layer. It is the placeholder for future reusable repair skills.
- `bootstrap`: Spring Boot application entrypoint.

## Current RAG Flow

1. Create a knowledge base and write a document into the RAG layer.
2. Run the document through the default ingestion pipeline:
   `FETCHER -> PARSER -> CHUNKER -> INDEXER`.
3. Parse the repair request description and supplied log snippets.
4. Classify the request against the admin-managed intent tree to lock the
   target system, knowledge bases, and code repositories.
5. Return ambiguity guidance when the top intent candidates are too close.
6. Run enabled retrieval channels in parallel using Java virtual threads:
   intent-directed vector search, global vector search when intent is absent,
   BM25-style keyword search, log center search, and code repository search.
7. Deduplicate retrieved chunks and package them as a `RepairContextPackage`.
8. Rewrite and split the user question with the admin-managed query-term
   mappings, then build a repair prompt plan with knowledge, runtime-log, code,
   and split-question sections.
9. Record the prompt-flow run and node-level steps into an in-memory trace store
   so the MVP has a queryable execution path.
10. For the chat-flow test channel, load conversation memory before the current
    turn, include it in the prompt, then append the assistant response.
11. Expose a ragent-style `/rag/v3/chat` SSE wire-format endpoint backed by the
    same deterministic MVP repair flow and the shared mapping registry.

## Knowledge And Admin API

Knowledge, document, chunk, and admin overview capabilities run in the same
Spring Boot process. They are packages and controllers inside the five-layer
service, not separate Maven modules or microservices.

- `POST /knowledge-base`: create an in-memory knowledge base.
- `GET /knowledge-base?current=1&size=10&name=...`: page knowledge bases for
  the admin frontend.
- `GET /knowledge-base/{knowledgeBaseId}`: inspect one knowledge base.
- `PUT /knowledge-base/{knowledgeBaseId}`: rename a knowledge base.
- `DELETE /knowledge-base/{knowledgeBaseId}`: delete a knowledge base and its
  documents, chunks, raw previews, and vector entries.
- `POST /knowledge-base/{knowledgeBaseId}/docs/write`: write, parse, chunk, and
  index a text or Markdown document.
- `GET /knowledge-base/{knowledgeBaseId}/docs`: list documents in a knowledge
  base.
- `GET /knowledge-base/docs/search?keyword=...&limit=8`: search documents
  across knowledge bases by name, type, or raw preview text.
- `GET /knowledge-base/docs/{documentId}`: inspect one document.
- `PUT /knowledge-base/docs/{documentId}`: update document name and knowledge
  type, and synchronize the document's chunks in the vector store.
- `DELETE /knowledge-base/docs/{documentId}`: delete a document, its chunks,
  raw preview, and vector entries.
- `GET /knowledge-base/docs/{documentId}/chunks`: list indexed chunks for a
  document.
- `POST /knowledge-base/docs/{documentId}/chunks`: create a manual chunk for a
  document and index it.
- `PUT /knowledge-base/docs/{documentId}/chunks/{chunkId}`: update a chunk's
  content and replace its vector entry.
- `DELETE /knowledge-base/docs/{documentId}/chunks/{chunkId}`: delete a chunk
  and remove its vector entry.
- `PATCH /knowledge-base/docs/{documentId}/chunks/batch-enable?value=false`:
  batch-toggle selected chunks, or all chunks in the document when no IDs are
  supplied.
- `GET /knowledge-base/docs/{documentId}/preview`: preview the raw document
  content.
- `GET /knowledge-base/docs/{documentId}/chunk-logs`: inspect the ingestion
  node logs for the document.
- `PATCH /knowledge-base/docs/{documentId}/enabled?enabled=false`: toggle a
  document.
- `PATCH /knowledge-base/docs/{documentId}/enable?value=false`: ragent-style
  document toggle alias.
- `PATCH /knowledge-base/docs/chunks/{chunkId}/enabled?enabled=false`: toggle a
  chunk.
- `PATCH /knowledge-base/docs/{documentId}/chunks/{chunkId}/enable?value=false`:
  ragent-style chunk toggle scoped by document.
- `GET /admin/overview`: return counts for knowledge bases, documents, indexed
  documents, chunks, enabled chunks, and vector-store chunks.

## Knowledge Admin Frontend

The ragent-style knowledge admin frontend is served by the same Spring Boot
process as static assets, so local development does not need a separate Node
dev server.

- `GET /admin/knowledge`: knowledge-base list, search, create, rename, delete,
  and open-document actions.
- `GET /admin/knowledge/{knowledgeBaseId}`: document list, text/Markdown write,
  RustFS upload entry, preview, edit, enable/disable, and delete actions.
- `GET /admin/knowledge/{knowledgeBaseId}/docs/{documentId}`: chunk list,
  create/edit/delete, single enable/disable, batch enable/disable, and ingestion
  log view.

## Ingestion Pipeline And Task API

Ingestion management mirrors ragent's pipeline/task surface while staying
inside the single Spring Boot process. Tasks execute the selected pipeline and
write the resulting document, chunks, vector entries, and node logs into the
same in-memory `KnowledgeWorkspace`.

- `GET /ingestion/pipelines?pageNo=1&pageSize=10&keyword=...`: page pipelines.
- `GET /ingestion/pipelines/{id}`: inspect one pipeline.
- `POST /ingestion/pipelines`: create a pipeline from ordered node configs.
- `PUT /ingestion/pipelines/{id}`: update a pipeline.
- `DELETE /ingestion/pipelines/{id}`: delete a non-default pipeline.
- `POST /ingestion/tasks`: execute a text/Markdown ingestion task.
- `POST /ingestion/tasks/upload`: upload a file to RustFS/S3 and execute a
  document ingestion task from the uploaded file content.
- `GET /ingestion/tasks?pageNo=1&pageSize=10&status=COMPLETED`: page tasks.
- `GET /ingestion/tasks/{id}`: inspect one task.
- `GET /ingestion/tasks/{id}/nodes`: inspect node-level task logs.

## Query-Term Mapping API

Query-term mappings are also embedded in the single Spring Boot process. The
current MVP keeps them in memory and applies enabled rules, ordered by priority,
when `/rag/v3/chat` builds its prompt. The SSE `meta` event includes
`rewrittenQuestion` so the test channel can prove the mapping was applied.

- `GET /mappings`: list all query-term mappings.
- `GET /mappings/{id}`: inspect one mapping.
- `POST /mappings`: create a mapping.
- `PUT /mappings/{id}`: update a mapping.
- `DELETE /mappings/{id}`: delete a mapping.

## RAG Runtime Settings API

Runtime settings mirror ragent's read-only `/rag/settings` surface. The MVP
aggregates Spring properties and deterministic local defaults in the same
process, masks provider API keys, and exposes upload, RAG, memory, rate-limit,
and AI model sections without adding a separate configuration service.

- `GET /rag/settings`: inspect upload limits, default vector config, query
  rewrite switch, memory limits, rate-limit settings, local AI defaults, and
  masked provider keys.

## Sample Question API

Sample questions mirror ragent's welcome-page and admin management surface. They
are in-memory for the MVP and remain inside the same Spring Boot process.

- `GET /rag/sample-questions`: return up to three welcome-page sample questions.
- `GET /sample-questions?keyword=...&current=1&size=10`: page and filter sample
  questions by title, description, or question content.
- `GET /sample-questions/{id}`: inspect one sample question.
- `POST /sample-questions`: create a sample question.
- `PUT /sample-questions/{id}`: update a sample question.
- `DELETE /sample-questions/{id}`: delete a sample question.

## Conversation API

Conversation management mirrors ragent's chat sidebar and message-history
surface. `/rag/v3/chat` writes user and assistant turns into the same in-memory
registry used by these endpoints.

- `GET /conversations`: list conversations for the default local test user.
- `GET /conversations/{conversationId}/messages`: list user and assistant
  messages in ascending order.
- `PUT /conversations/{conversationId}`: rename a conversation.
- `DELETE /conversations/{conversationId}`: delete a conversation and its
  messages.

## Message Feedback API

Message feedback mirrors ragent's assistant-message vote surface. The MVP stores
feedback in memory, validates that votes are `1` or `-1`, only accepts feedback
for assistant messages, and updates the conversation message `vote` field so the
chat sidebar/message-history test path can display the latest user vote.

- `POST /conversations/messages/{messageId}/feedback`: submit or update feedback
  for an assistant message. URL-encode `#` in message IDs when using raw curl,
  for example `conversation-v3-test%232`.

## Intent Tree API

Intent nodes mirror ragent's intent-tree admin surface. The MVP keeps them in
memory, supports enable/disable/delete operations, and `/rag/v3/chat` builds its
classifier from the same registry.

- `GET /intent-tree/trees`: return the full intent tree.
- `POST /intent-tree`: create an intent node.
- `PUT /intent-tree/{id}`: update an intent node.
- `DELETE /intent-tree/{id}`: delete an intent node and its descendants.
- `POST /intent-tree/batch/enable`: enable nodes by ID.
- `POST /intent-tree/batch/disable`: disable nodes by ID.
- `POST /intent-tree/batch/delete`: delete nodes by ID.

## Trace API

Trace data is embedded in the same Spring Boot process. The current MVP stores
run and node records in memory, which is enough for local development and test
channel verification.

- `GET /rag/traces/runs`: list trace runs.
- `GET /rag/traces/runs/{traceId}`: inspect one trace run with ordered nodes.
- `GET /rag/traces/runs/{traceId}/nodes`: list node-level trace records.

## Boundary Decisions

- MCP is not migrated.
- LLM chat and answer generation are not migrated.
- Log center and code repository access are represented by ports and mock
  implementations in the test channel only.
- Knowledge and admin are intentionally embedded in the single Spring Boot
  service to keep local memory usage low on a 16 GB development machine.
- Knowledge document and chunk lifecycle management is in-memory for the MVP.
  It mirrors the ragent REST route shape for detail/search/update/delete,
  manual chunk CRUD, and batch enablement, but does not include JDBC
  persistence, Feishu import, scheduled refresh, or source-file download yet.
- The knowledge admin frontend is a lightweight static app under
  `bootstrap/src/main/resources/static/admin`. It intentionally avoids a
  separate Vite/Node runtime so the MVP remains one Spring Boot service on a
  16 GB development machine. The visual surface mirrors ragent's knowledge
  list, document list, and chunk management flows, but broader ragent admin
  pages remain outside this frontend slice.
- Conversation memory is currently an in-memory MVP. It mirrors the ragent
  load-before-append flow and uses Java virtual threads for memory load, but it
  does not include JDBC persistence or summary compression yet. The same
  in-memory registry also backs the conversation list and message-history APIs.
- Intent-tree management is in-memory. It clears no Redis cache because the MVP
  uses the registry directly in-process.
- RAG settings are read-only and property-backed. They expose the ragent settings
  shape but do not persist admin edits yet.
- Message feedback is in-memory and synchronous. It mirrors ragent's validation
  and upsert semantics, but does not include MQ fan-out or JDBC persistence yet.
- Ingestion pipeline/task management is in-memory. The MVP supports JSON text
  and Markdown sources plus multipart upload to RustFS-compatible S3. It uses
  ragent-compatible RustFS defaults: `rustfs.url=http://localhost:9000`,
  `rustfs.access-key-id=rustfsadmin`, `rustfs.secret-access-key=rustfsadmin`,
  and `rustfs.bucket=biz`. URL, Feishu, database persistence, and scheduling
  workers are not included yet.
- `/rag/v3/chat` currently returns deterministic SSE-formatted `meta`, `delta`,
  and `done` events. `meta.rewrittenQuestion` is built from the in-memory
  mapping registry, and `meta.intentSystemId` comes from the in-memory intent
  tree. The endpoint proves the controller and flow contract, but it is not
  connected to a real streaming LLM client yet. Chat task state is tracked in an
  in-memory registry; `/rag/v3/stop` writes cancellation state there, and
  `/rag/v3/tasks/{taskId}` exposes the test/debug view. Global rate-limit
  rejection is also in-process and uses `rag.rate-limit.global.*`.
- The execution-layer task entity is not implemented; `RepairTaskContextPort`
  is the handoff boundary for later design.
- The in-memory vector store and deterministic text scoring are for MVP tests
  and local development only.

## Test Channel

Start the single Spring Boot service:

```bash
./mvnw install -DskipTests
./mvnw -pl bootstrap spring-boot:run
```

Open the knowledge admin frontend:

```text
http://127.0.0.1:8080/admin/knowledge
http://127.0.0.1:8080/admin/knowledge/kb-1
http://127.0.0.1:8080/admin/knowledge/kb-1/docs/doc-1
```

Then call:

```bash
curl -X POST http://127.0.0.1:8080/test/rag/full-flow
curl -X POST http://127.0.0.1:8080/test/ingestion/default-pipeline
curl -X POST http://127.0.0.1:8080/test/rag/prompt-flow
curl -X POST http://127.0.0.1:8080/test/rag/chat-flow
curl -s -X POST http://127.0.0.1:8080/knowledge-base \
  -H 'Content-Type: application/json' \
  -d '{"name":"支付系统","description":"支付 API 文档"}'
curl -s -X POST http://127.0.0.1:8080/ingestion/tasks \
  -H 'Content-Type: application/json' \
  -d '{"pipelineId":"default-document-pipeline","knowledgeBaseId":"kb-1","knowledgeType":"api","mimeType":"text/markdown","source":{"type":"inline","location":"inline://payment-api.md","fileName":"payment-api.md","content":"# 支付 API\n\nOrderService.create 必须校验 orders.amount。"},"chunkingMode":"STRUCTURE_AWARE","chunkSize":72,"overlapSize":8}'
curl http://127.0.0.1:8080/knowledge-base/docs/search?keyword=OrderService
curl -s -X POST http://127.0.0.1:8080/knowledge-base/docs/doc-1/chunks \
  -H 'Content-Type: application/json' \
  -d '{"chunkId":"manual-check","index":99,"content":"手工补充 Chunk：orders.amount 为空时返回参数错误"}'
curl -s -X PATCH "http://127.0.0.1:8080/knowledge-base/docs/doc-1/chunks/batch-enable?value=false" \
  -H 'Content-Type: application/json' \
  -d '{"chunkIds":["manual-check"]}'
curl -s -X PUT http://127.0.0.1:8080/knowledge-base/docs/doc-1 \
  -H 'Content-Type: application/json' \
  -d '{"docName":"payment-api-v2.md","knowledgeType":"api-v2"}'
curl -s -X POST http://127.0.0.1:8080/ingestion/tasks/upload \
  -F pipelineId=default-document-pipeline \
  -F knowledgeBaseId=kb-1 \
  -F knowledgeType=api \
  -F chunkingMode=STRUCTURE_AWARE \
  -F chunkSize=72 \
  -F overlapSize=8 \
  -F file=@docs/smoke/payment-upload-smoke.md
curl http://127.0.0.1:8080/ingestion/tasks
curl http://127.0.0.1:8080/ingestion/pipelines
curl -s -X POST http://127.0.0.1:8080/mappings \
  -H 'Content-Type: application/json' \
  -d '{"sourceTerm":"创建订单","targetTerm":"POST /api/orders","priority":20,"enabled":true,"remark":"payment order api"}'
curl http://127.0.0.1:8080/mappings
curl -s -X POST http://127.0.0.1:8080/sample-questions \
  -H 'Content-Type: application/json' \
  -d '{"title":"支付排障","description":"订单金额为空","question":"支付系统下单接口 500，金额为空怎么修复？"}'
curl http://127.0.0.1:8080/rag/sample-questions
curl -s -X POST http://127.0.0.1:8080/intent-tree \
  -H 'Content-Type: application/json' \
  -d '{"intentCode":"refund-system","name":"退款系统","level":0,"description":"退款 退单 refund","kbId":"refund-system","examples":["退款接口失败"],"enabled":1,"sortOrder":5}'
curl http://127.0.0.1:8080/intent-tree/trees
curl "http://127.0.0.1:8080/rag/v3/chat?question=%E6%94%AF%E4%BB%98%E7%B3%BB%E7%BB%9F%E4%B8%8B%E5%8D%95%E6%8E%A5%E5%8F%A3%20500%E3%80%82%E9%87%91%E9%A2%9D%E4%B8%BA%E7%A9%BA%E6%80%8E%E4%B9%88%E4%BF%AE%E5%A4%8D%EF%BC%9F&conversationId=conversation-v3-test"
curl http://127.0.0.1:8080/conversations
curl http://127.0.0.1:8080/conversations/conversation-v3-test/messages
curl http://127.0.0.1:8080/rag/settings
curl -s -X POST "http://127.0.0.1:8080/conversations/messages/conversation-v3-test%232/feedback" \
  -H 'Content-Type: application/json' \
  -d '{"vote":1,"reason":"helpful","comment":"定位准确"}'
curl -X POST "http://127.0.0.1:8080/rag/v3/stop?taskId=task-v3-test"
curl http://127.0.0.1:8080/rag/v3/tasks/task-v3-test
curl http://127.0.0.1:8080/rag/traces/runs/trace-ticket-prompt-flow
curl http://127.0.0.1:8080/rag/traces/runs/trace-ticket-prompt-flow/nodes
```

`/test/ingestion/default-pipeline` runs the ragent-style document ingestion
chain and returns task ID, pipeline ID, node types, chunk count, and indexed
chunk IDs.

`/test/rag/full-flow` writes a mock payment-system document, parses and chunks
it, indexes it, runs intent-guided retrieval with mocked log/code channels, and
returns the packaged context summary.

`/test/rag/prompt-flow` runs document ingestion, intent-guided retrieval,
query rewrite, split-question generation, and prompt-plan construction. It
returns `traceId`, prompt scene, rewritten question, prompt sections, user
prompt, and evidence chunk IDs. The deterministic test trace ID is
`trace-ticket-prompt-flow`.

`/test/rag/chat-flow` runs a deterministic two-turn repair chat. It proves the
second turn loads prior user/assistant history, includes `对话记忆` in the prompt,
and appends the second assistant response back into memory.

`/rag/v3/chat` mirrors ragent's public chat endpoint shape and returns
SSE-formatted `meta`, `delta`, and `done` events. It reads from the shared
`KnowledgeWorkspace` vector store, so documents written or uploaded through the
ingestion APIs can be retrieved by the chat path. The `meta` event includes the
rewritten question produced by the active query-term mappings and the selected
intent system ID from the active intent tree. The chat path registers a task,
marks it `DONE` when the deterministic response is produced, and records
`REJECTED` when the in-process global rate limit is full. `/rag/v3/stop` records
`CANCELLED` state for a task ID while preserving the public stop response shape.
`/rag/v3/tasks/{taskId}` is a local test/debug channel for inspecting that state.

`/conversations` and `/conversations/{conversationId}/messages` expose the
conversation metadata and message history written by `/rag/v3/chat`.

## Verification

Run:

```bash
./mvnw test
```

The tests cover document write ingestion, parser/chunker/indexer node logs,
ingestion pipeline/task admin endpoints, multipart upload into RustFS-compatible
object storage, uploaded-document retrieval through `/rag/v3/chat`,
intent recognition, ambiguity guidance, parallel retrieval, log/code ports,
query rewrite, prompt-plan construction, knowledge/admin REST access, chunk
toggling, document detail/search/update/delete, manual chunk create/update/delete,
batch chunk enablement, knowledge-base list/detail/rename/delete endpoints,
knowledge admin static frontend routes, admin overview, conversation memory,
chat-flow REST access, ragent v3 chat/stop endpoints, conversation list/message
endpoints, intent-tree admin endpoints, query-term mapping admin endpoints, RAG
settings, assistant-message feedback, chat task state, rate-limit rejection,
trace REST access, sample-question welcome/admin endpoints, REST test channel
access, and context packaging.

Browser smoke verification for the knowledge admin frontend writes screenshots
to:

- `lark-output/knowledge-admin-desktop.png`
- `lark-output/knowledge-admin-mobile.png`
