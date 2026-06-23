# PostgreSQL 实体 CRUD 连通性测试报告

日期：2026-06-21

分支：`codex/p0-task_state_machine`（报告脱敏展示）

目标库：本地 Docker PostgreSQL 容器 `postgres`，数据库 `ragent`

## 结论

本次已真实连接本地 PostgreSQL 执行 CRUD 连通性测试。P0 PostgreSQL 持久化实体和后台用户实体均可通过当前业务端口或服务完成其已暴露的写入、查询、更新、删除或替换语义。

测试过程中发现并修复了一个真实问题：`repair_records` 更新状态时，MyBatis-Plus 默认 `updateById` 会把 `jsonb` 列按 varchar 参数回写，导致 PostgreSQL 报错。已改为 `RepairRecordMapper.updateStatus` 专用 SQL，仅更新 `status`、`rag_summary`、`updated_at`。

## 覆盖范围

| Java 行实体 | 数据表 | 测试入口 | 覆盖结果 |
| --- | --- | --- | --- |
| `AdminUserDO` | `admin_users` | `PostgresUserAdminService` | 创建、分页查询、更新、删除通过 |
| `KnowledgeBaseRow` | `knowledge_bases` | `KnowledgeBaseStore` | 创建、按 ID 查询、列表查询、更新、删除通过 |
| `KnowledgeDocumentRow` | `knowledge_documents` | `KnowledgeDocumentStore` | 创建、按 ID 查询、来源查询、列表查询、原文查询、更新、删除通过 |
| `KnowledgeChunkRow` | `knowledge_chunks` | `KnowledgeChunkStore` | 创建、按 ID 查询、按文档查询、全量查询、更新、按 ID 删除、按文档删除通过 |
| `KnowledgeVectorRow` | `knowledge_vectors` | `VectorStore` | 索引、全量查询、关键词检索、向量检索、替换、删除通过 |
| `IngestionTaskRow` | `ingestion_tasks` | `IngestionTaskStore` | 创建、按 ID 查询、列表查询、更新通过；当前端口未暴露删除 |
| `IngestionTaskNodeRow` | `ingestion_task_nodes` | `IngestionTaskStore` | 节点写入、节点查询、节点列表替换通过；当前端口未暴露单节点更新/删除 |
| `RdTaskRow` | `rd_tasks` | `RdTaskStore` | 创建、按 ID 查询、列表查询、更新通过；当前端口未暴露删除 |
| `RepairRecordRow` | `repair_records` | `RepairRecordRepository` | 创建、按 ID 查询、按工单查询、状态更新通过；当前端口未暴露删除 |
| `RepairRecordArtifactRow` | `repair_record_artifacts` | `RepairRecordRepository` | 新增产物、按修复记录查询通过；当前端口未暴露更新/删除 |

## 执行命令

默认保护验证，确认普通测试不会连接外部数据库：

```bash
./mvnw -pl bootstrap -am -Dtest=PostgresPersistenceCrudIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`，构建成功。

真实 PostgreSQL 连通性测试：

```bash
./mvnw -pl bootstrap -am -Dtest=PostgresPersistenceCrudIntegrationTest \
  -Drd.integration.postgres.enabled=true \
  '-Drd.integration.postgres.url=jdbc:postgresql://127.0.0.1:5432/ragent?client_encoding=UTF8' \
  -Drd.integration.postgres.username=postgres \
  -Drd.integration.postgres.password=postgres \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

第一次结果：失败，`repair_records.executor_json` 等 `jsonb` 字段被默认更新 SQL 按 varchar 参数回写。

修复后结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，构建成功。

默认全量测试：

```bash
./mvnw test
```

结果：构建成功。汇总为 `rag` 26 个测试通过，`engine` 6 个测试通过，`exec` 1 个测试通过，`bootstrap` 36 个测试中 34 个通过、2 个按条件跳过。

## 数据清理验证

测试数据统一使用 `crud-it-*` 标记。真实测试结束后已查询本地库，以下表均无残留测试数据：

| 表 | 残留数 |
| --- | ---: |
| `admin_users` | 0 |
| `knowledge_bases` | 0 |
| `knowledge_documents` | 0 |
| `knowledge_chunks` | 0 |
| `knowledge_vectors` | 0 |
| `ingestion_tasks` | 0 |
| `ingestion_task_nodes` | 0 |
| `rd_tasks` | 0 |
| `repair_records` | 0 |
| `repair_record_artifacts` | 0 |

## 代码变更

- 新增测试：`bootstrap/src/test/java/com/wish/rd/bootstrap/PostgresPersistenceCrudIntegrationTest.java`
- 修复 `repair_records` 状态更新：`PostgresRepairRecordRepository` 改用 `RepairRecordMapper.updateStatus`
- 新增专用 SQL：`RepairRecordMapper.updateStatus`

## 剩余注意点

- 当前“完整 CRUD”以现有业务端口为准。若产品要求每张表都有显式删除或单行更新接口，还需要为 `rd_tasks`、`ingestion_tasks`、`repair_records`、`repair_record_artifacts` 等补充端口方法。
- 测试输出中存在 JDK/Mockito 动态 agent warning，以及 `PostgresVectorStore` 的 deprecation 编译提示，本次未处理，因为不影响 PostgreSQL CRUD 连通性。
