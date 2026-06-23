# P0 Knowledge Productionization QA Report

日期：2026-06-21

## 范围

- Feishu Wiki 任务：`https://my.feishu.cn/wiki/W7bzwwbAciPkqZkECSXc146znfb`
- 本轮交付范围：P0 知识生产化链路，包含 PostgreSQL 持久化、项目自管理 SQL、Snowflake `bigint` ID、知识库/文档/chunk/摄取任务/修复记录表、Feishu 文档导入外壳、pgvector 写入与检索、管理台前端入口。
- 参考配置：`/Users/wish233/IdeaProjects/ragent/bootstrap/src/main/resources/application.yaml`
  - PostgreSQL：`jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8`
  - pgvector 维度：`1536`
  - Redis/RustFS 配置已参考；当前 P0 HTTP 验收链路未依赖 Redis/RustFS 写入。

## 主要实现

- 新增 `SnowflakeIdGenerator`，知识库、文档、chunks、摄取任务、修复记录等新增持久化实体均使用 Snowflake 字符串 ID，数据库列为 `BIGINT`。
- 新增 Store/Repository 端口与内存实现：
  - `KnowledgeBaseStore`
  - `KnowledgeDocumentStore`
  - `KnowledgeChunkStore`
  - `IngestionTaskStore`
  - `RepairRecordRepository`
- 新增 PostgreSQL 实现：
  - `PostgresKnowledgeBaseStore`
  - `PostgresKnowledgeDocumentStore`
  - `PostgresKnowledgeChunkStore`
  - `PostgresIngestionTaskStore`
  - `PostgresRepairRecordRepository`
  - `PostgresVectorStore`
- 新增项目自管理 SQL：
  - `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql`
- 新增 Feishu 导入接口：
  - `POST /knowledge-base/{knowledgeBaseId}/docs/import/feishu`
- 管理台前端增加 Feishu 导入入口，并重新构建静态产物到 `bootstrap/src/main/resources/static/admin/`。

## 数据库初始化

已通过 Docker PostgreSQL 执行：

```bash
docker exec -i postgres psql -U postgres -d ragent < bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql
```

验证结果：

```text
vector_extension=0.8.2
```

## HTTP 实测

启动命令：

```bash
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar \
  --server.address=127.0.0.1 \
  --server.port=18080 \
  --rd.knowledge.store=postgres \
  --rd.storage.mode=memory \
  '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8' \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --rag.default.dimension=1536
```

接口链路覆盖：

- `POST /knowledge-base` 创建知识库
- `POST /knowledge-base/{id}/docs/write` 写入 Markdown 文档
- `GET /knowledge-base/docs/{documentId}/chunks` 验证切分结果
- `GET /knowledge-base/docs/{documentId}/preview` 验证原文预览
- `POST /knowledge-base/{id}/docs/import/feishu` 导入 Feishu 文档
- 重复调用 Feishu 导入，验证同 revision/source 去重
- `GET /knowledge-base/docs/search?keyword=OrderService&limit=5` 验证文档检索
- `GET /rag/v3/chat` 验证 RAG 检索能够命中写入内容
- `GET /admin/index.html` 验证前端静态入口可访问

HTTP 实测结果：

```json
{
  "tag": 1782019793547,
  "knowledgeBaseId": "7474332748799414272",
  "manualDocumentId": "7474332749017518080",
  "manualChunkCount": 2,
  "feishuDocumentId": "7474332749344673792",
  "duplicateFeishuReturnedSameDocument": true,
  "documentCountInKnowledgeBase": 2,
  "overviewCounts": {
    "knowledgeBaseCount": 3,
    "documentCount": 2,
    "indexedDocumentCount": 2,
    "chunkCount": 3
  },
  "ragContainsOrderEvidence": true,
  "adminIndexStatus": 200,
  "adminIndexBytes": 437
}
```

说明：`knowledgeBaseCount=3` 包含两次失败前创建的空 QA 知识库；本次验收以 `knowledgeBaseId=7474332748799414272` 定位。

## PostgreSQL/pgvector 落库核对

查询：

```sql
SELECT count(*) FROM knowledge_bases WHERE id = 7474332748799414272;
SELECT count(*) FROM knowledge_documents WHERE knowledge_base_id = 7474332748799414272;
SELECT count(*) FROM knowledge_chunks WHERE knowledge_base_id = 7474332748799414272;
SELECT count(*) FROM knowledge_vectors WHERE metadata_json->>'knowledgeBaseId' = '7474332748799414272';
SELECT count(*) FROM knowledge_documents WHERE knowledge_base_id = 7474332748799414272 AND source_type = 'FEISHU';
```

结果：

```text
knowledge_bases=1
knowledge_documents=2
knowledge_chunks=3
knowledge_vectors=3
feishu_documents=1
vector_extension=0.8.2
```

## 自动化验证

后端：

```bash
./mvnw test
```

结果：

```text
BUILD SUCCESS
rag: Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
engine: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
exec: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
bootstrap: Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
```

前端：

```bash
npm run typecheck
npm run build
```

结果：

```text
tsc --noEmit: PASS
vite build: PASS
admin-knowledge.css: 17.33 kB
admin-knowledge.js: 232.47 kB
```

## 已知边界

- Feishu 导入当前按 P0 文档要求实现为导入外壳和 mock client；真实 Feishu Ticket/Doc 字段、认证、状态枚举和回写格式等待用户提供后再接真实适配器。
- pgvector 当前使用确定性词项哈希向量，保证无外部 embedding 凭证时也能完成写入、检索和联调；后续可替换为真实 embedding provider，`VectorStore` 端口不需要改变。
- 前端依赖安装阶段曾报告 npm audit 存在 1 个 moderate 和 1 个 high 级别问题，本轮未自动修复，避免引入无关依赖升级。
