# OpenViking 投影协议（WP-0 冻结）

日期：2026-08-13  
状态：已冻结；经固定 digest 的 OpenViking v0.4.13 真实合同验证  
范围：URI、ownership marker、HTTP 信封、错误分类、单实例 Compose、LOCAL 检索基线。不实现 Worker、Outbox、管理页或检索切流。

相关文档：

- 实施计划：`docs/superpowers/plans/2026-08-13-openviking-rag-integration-implementation-plan.md`
- 仓库约束：`RULE.md` §3.5.6
- 真实 JSON 信封：`bootstrap/src/test/resources/openviking/contracts/`
- URI 构造：`rag/src/main/java/com/wish/rd/rag/knowledge/projection/OpenVikingProjectionUris.java`

计划 §22 的架构裁决本 WP 只记录、不重开：PostgreSQL 是 desired/canonical 真值；OpenViking 是可重建外部投影；HTTP 2xx 与 `task completed` 都不是单独成功条件。

## 1. 已验证事实（v0.4.13）

合同环境：`deploy/openviking/docker-compose.yml`，`--without-bot`，Studio 绑定 `127.0.0.1:1933`，数据卷 `rd-bot-openviking-data`（不复用 `~/.openviking`）。

钉死镜像：

| 服务 | 镜像 |
| --- | --- |
| OpenViking | `ghcr.io/volcengine/openviking:v0.4.13@sha256:92ad51e68b028d17642d2ece77f83800c71cdcb511217eb04349f4f651cc97e8` |
| Mock LLM | `python:3.13-alpine@sha256:540c7d91f98ff6880174c40e99067bf5941eb54d818a7a5e094d188b196a934d` |

该 OpenViking 索引 digest 对应 arm64 manifest `sha256:093edc23…`、amd64 `sha256:4bca8fa5…`。禁止改用 `latest`。

关键运行时行为（以真实 HTTP 为准，不以官方文档猜测 DTO）：

1. `add_resource.to` **始终建成目录**，即使最后一段看起来像 `source.md`。L2 文件是 `{to}/{uploadedFileName}`。
2. `result.root_uri` 必须与请求的 `to` **精确相等**，否则视为合同错误。
3. `wait=false` 时 HTTP 200 只表示受理；后台任务在 `GET /api/v1/tasks/{task_id}`。
4. 日常数据 API 必须用 account 的 user/admin key。ROOT key 访问租户数据返回 HTTP 403 `PERMISSION_DENIED`。
5. 同 URI 并发 `add_resource` 返回 HTTP 409 `CONFLICT`，`details.conflict_type=path_busy`，`retryable=true`。
6. 递归删除已不存在的 URI 仍返回 HTTP 200，`estimated_deleted_count=0`（幂等）。随后 `GET /api/v1/fs/stat` 为 404 `NOT_FOUND`。
7. **未观测到** 终态 `result.status=failed` 的任务 JSON。暂停 mock-LLM 时任务停留在 `running` 并重试；恢复后 `completed`。禁止发明 failed-task 字段。Poller 若见到 `status=failed` 应视为终态，并在存在时持久化 `result.error`。

早期探针 JSON（`add_resource_wait_false.json` 等）曾把 `to` 写成 `…/documents/{id}/source.md`。那次探针证明了「末段像文件名仍是目录」，L2 变成 `…/source.md/{uploadedFileName}`。冻结协议改为目录 `to`，不再把文件名编进资源根。fixtures 冻结的是信封字段名与错误码；URI 字面值以本 spec 与 live smoke 为准。

## 2. URI 与 ownership

### 2.1 生产文档 URI

| 用途 | 形状 |
| --- | --- |
| `add_resource.to` / 资源根 | `viking://resources/rd-bot/kb/{knowledgeBaseId}/documents/{documentId}` |
| L2 规范文件 | `{root}/source.md` |
| L0 abstract | `GET /api/v1/content/abstract?uri={root}` |
| L1 overview | `GET /api/v1/content/overview?uri={root}` |
| L2 read | `GET /api/v1/content/read?uri={root}/source.md` |

规则：

- `{knowledgeBaseId}` 与 `{documentId}` 必须是数据库数字 ID：`[1-9][0-9]*`。
- 名称、来源 URL、revision、checksum **不得**进入路径。
- 上传文件名固定为 `source.md`。
- `create_parent=true` 为合同要求；`args.parse_mode=no_split` 仍把 `to` 建成目录。

### 2.2 合同测试根

```text
viking://resources/rd-bot/wp0-contract/{runId}/
viking://resources/rd-bot/wp0-contract/{runId}/documents/{documentId}
```

`runId`：`[A-Za-z0-9][A-Za-z0-9_-]*`。清理只能删除本次 `runId` 根内自己创建的 URI。`requireCleanupUri` 会先做 percent-decode 与 `.`/`..` 解析，并拒绝空段。无论 `ownedRoot` 是什么，禁止删除：

- `viking://resources/`
- `viking://resources/rd-bot/`
- `viking://resources/rd-bot/kb/…` 生产知识库路径

### 2.3 Marker 与 tags

Ownership marker：`rd-bot:{kbId}:{docId}`。

写入 OpenViking 的 tags（`tag_mode=replace`）：

```text
rd.owner=rd-bot
rd.kb_id={knowledgeBaseId}
rd.doc_id={documentId}
rd.sync_version={positiveLong}
rd.checksum={canonicalBodySha256Hex}
```

checksum 只覆盖规范正文，必须是 64 位小写 SHA-256 hex，不能把这些 tag 自己算进去。后续 Worker 只有同时满足下列条件才允许把账本标为 `IN_SYNC`（WP-0 不实现该写入）：

- HTTP 2xx 且 `root_uri` 精确等于请求 `to`
- task `status=completed` 且 `stage=completed`
- `queue_status.Semantic.error_count=0` 且 `queue_status.Embedding.error_count=0`
- attrs tags 含期望 owner/kb/doc/sync_version/checksum
- L0/L1 可读；L2 含规范正文；`POST /api/v1/search/find` 能在 owned/test 根下命中

## 3. HTTP 信封与鉴权

通用成功信封：`status=ok`，业务字段在 `result`。  
通用失败信封：`status=error`，`error.code` / `error.message` / `error.details`。部分 400（如缺少 `path`/`temp_file_id`）可能省略 `result`。

鉴权头：`X-API-Key`。日志、测试失败消息、fixtures、管理 UI 不得出现 `user_key` / `admin_key` / 真实 API key。`application.yaml` 只保存环境变量名。

本地 Compose `ov.conf` 的 `root_api_key` 是合同占位符 `rd-bot-local-openviking-root`，不是生产密钥。

Account：`rd-bot`。ROOT key 只允许创建 account/user。数据面使用该 account 下的 user key。

## 4. 冻结操作序列

真实合同（`OpenVikingRealContractSmokeTest`）必须可重复覆盖：

1. `GET /health` → 200，`status=ok`，`version=v0.4.13`
2. `GET /ready` → `status=ready`，本环境 `checks.embedding=ok`，`checks.ollama=not_configured`
3. `POST /api/v1/resources/temp_upload` → `result.temp_file_id` 形如 `upload_{hex}.md`
4. `POST /api/v1/resources`：`wait=false`，`create_parent=true`，`processing_mode=semantic_and_vectors`，`to` 为目录根
5. `GET /api/v1/tasks/{task_id}` 轮询至 `completed`
6. `GET /api/v1/fs/attrs?uri={root}` 校验 tags
7. L0 abstract、L1 overview、L2 read
8. `POST /api/v1/search/find`（`target_uri` 为本 run 根）
9. 同一 `to` 再上传并更新 tags（`rd.sync_version=2`）
10. `DELETE /api/v1/fs?uri={root}&recursive=true`，再删一次幂等，然后 stat 404

## 5. 错误分类

字段与 HTTP 码以 `bootstrap/src/test/resources/openviking/contracts/` 为准。后续 Adapter 必须按下列已观测码翻译，禁止按文档补字段。

| 观测 | HTTP / 客户端 | `error.code` 或异常 | WP 分类 |
| --- | --- | --- | --- |
| 未发送前连接/超时（测试用 `192.0.2.1`） | 无响应体 | `HttpTimeoutException` / `ConnectException` | `RETRYABLE_NOT_SENT` |
| 请求可能已发出后的超时 | 无可靠 body | 同上 | `UNKNOWN_REMOTE_RESULT` |
| 缺 key / 错误 key | 401 | `UNAUTHENTICATED` | `CONFIGURATION_BLOCKED` |
| ROOT key 调数据 API | 403 | `PERMISSION_DENIED` | `CONFIGURATION_BLOCKED` |
| 私网/非公开 URL 导入 | 403 | `PERMISSION_DENIED` | `CONFIGURATION_BLOCKED` |
| 非法 URI | 400 | `INVALID_URI` | `CONTRACT_OR_DATA_ERROR` |
| 缺少 path/temp_file_id | 400 | `INVALID_ARGUMENT` | `CONTRACT_OR_DATA_ERROR` |
| 同路径并发占用 | 409 | `CONFLICT` + `path_busy` + `retryable=true` | `RETRYABLE_BUSY` |
| 创建已存在 account | 409 | `ALREADY_EXISTS` | 幂等忽略（bootstrap） |
| 任务不存在 | 404 | `NOT_FOUND`，`details.type=task` | `UNKNOWN`（不得当成失败） |
| 删除后 stat | 404 | `NOT_FOUND` | 删除核验的 `ABSENT` |
| 幂等删除 | 200 | `estimated_deleted_count=0` | 成功 |
| HTTP 429 | （本轮未采集到真实 body） | 若出现 | `RETRYABLE`；DTO 待采集后再冻字段 |
| task `status=failed` | （本轮未采集到真实 body） | 若出现则终态 | 持久化已有 `result.error`；不得编造字段 |

WP-0 不实现 Worker。分类表供 WP-3/WP-4 使用。

## 6. LOCAL 检索基线

样本：`rag/src/test/resources/openviking-baseline/`，**32** 篇短文档，每篇一个唯一 token `RD_WP0_*` 与一条 gold 问题。

冻结门槛（`OpenVikingLocalRetrievalBaselineTest`，`KnowledgeWorkspace.inMemory()`）：

- 语料规模 20–50
- Recall@8 = 1.0
- overlap 向量检索 p95 < 50ms

WP-7 Shadow 评测不得低于该 Recall@8。p95 只约束当前 in-memory overlap search，不代表 OpenViking 检索延迟。

## 7. 配置默认值

`bootstrap/src/main/resources/application.yaml`：

- `rd.knowledge.projection.mode=OFF`
- `rd.openviking.enabled=false`
- `rd.rag.knowledge-provider-mode=LOCAL`

普通 `./mvnw test` 不得启动真实 OpenViking。live smoke 仅 `-Drd.openviking.smoke=true`。

## 8. 验证

始终可跑（无 Docker）：

```bash
./mvnw -q -pl rag -am \
  -Dtest=OpenVikingProjectionUrisTest,OpenVikingLocalRetrievalBaselineTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl bootstrap -am \
  -Dtest=OpenVikingContractJsonFixturesTest,OpenVikingRealContractSmokePreconditionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实合同：

```bash
scripts/openviking/up.sh
./mvnw -q -pl bootstrap -am \
  -Drd.openviking.smoke=true \
  -Dtest=OpenVikingContractJsonFixturesTest,OpenVikingRealContractSmokeTest,OpenVikingRealContractSmokePreconditionsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Compose 入口：`scripts/openviking/up.sh` / `scripts/openviking/down.sh`。`down.sh` 不删除 volume，避免误清合同数据；重建数据需显式 `docker volume rm rd-bot-openviking-data`。

## 9. 验收反例

以下不能称为 WP-0 完成：

- 只按 OpenViking 文档手写 DTO，没有真实 JSON fixtures
- live smoke 默认跟着 `./mvnw test` 跑
- 清理删除 `viking://resources/rd-bot/` 或任意生产 KB URI
- 把 `to=…/source.md` 冻成资源根（v0.4.13 会把它建成目录）
- 把 HTTP 200 或 `task completed` 写成 `IN_SYNC`
- 发明未观测的 `task failed` JSON
- 把客户端超时冻成带 `exception=TimeoutError` 的 HTTP 信封；超时没有响应体，见 `client_timeout.json` 的 `observed=no_http_body`
- 日志或 fixtures 含 `user_key`
- 把 mock-LLM 或本地 `root_api_key` 当成生产模型/密钥
