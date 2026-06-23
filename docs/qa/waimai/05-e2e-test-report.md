# Waimai x RD-Bot 端到端测试报告

## 1. 结论摘要

本次测试覆盖了 waimai 项目静态分析、依赖安装、基线构建、seed/启动验证、文档生成、RD-Bot 文档入库、真实 bug 工单创建、RAG 修复上下文生成，以及 P2 Docker executor 的可行性验证。

最终结论：

- 文档生成：完成，生成 4 份 Markdown 文档。
- 文档入库：完成，真实调用 RD-Bot HTTP API 入库。
- 真实 bug：完成，使用 waimai 后端 ESM 启动失败作为 P0 bug。
- 工单/RAG 通道：完成，真实 HTTP 运行到 `CONTEXT_READY`。
- P2 executor mock 验证：通过，`DockerClaudeCodeExecutor` 相关 focused tests 全绿。
- 真实 Docker Claude Code 自动修复：未完成，当前环境和系统实现存在阻塞。
- 是否自动修复成功：没有。系统没有完成真实代码修复，也没有产出可应用 patch/PR。

主要阻塞：

1. 本地没有 `rd-bot/claude-code:local` 镜像。
2. 镜像构建被 Docker 镜像源限流或基础镜像拉取阻塞。
3. 当前 HTTP 工单修复通道只跑到 RAG 上下文，不触发 `RdBotFixEngine` 的 executor。
4. 当前生产 `RepairWorkspaceFactory` 只创建空 `repo/` 目录，不会自动 clone/copy `repositoryUrl` 指向的 waimai 项目。

## 2. 测试对象

原始项目：

```text
/Volumes/WishDisk/test/ima/waimai
```

测试副本：

```text
/Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e
```

RD-Bot 工作区：

```text
/Users/wish233/Documents/RD-Bot
```

## 3. 环境信息

命令输出：

```text
node: v24.12.0
npm: 11.7.0
java: openjdk version "23.0.2" 2025-01-21
docker: Docker version 29.1.3, build f52814d
claude: 2.1.170 (Claude Code)
npm @anthropic-ai/claude-code latest: 2.1.185
```

RD-Bot 启动配置：

```bash
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar \
  --server.port=18080 \
  --rd.storage.mode=memory \
  --rd.knowledge.store=memory \
  --rd.executor.docker.enabled=false \
  --rd.repair.queue.mode=memory
```

启动结果：

```text
Tomcat started on port 18080
Started RdBotApplication
bound repair queue consumer to in-memory adapter
```

## 4. Waimai 基线验证

### 4.1 依赖安装

Server：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/server
npm install
```

结果：

```text
added 144 packages
1 moderate severity vulnerability
```

Client：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/client
npm install
```

结果：

```text
added 161 packages
2 vulnerabilities (1 moderate, 1 high)
```

### 4.2 Server build

命令：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/server
npm run build
```

结果：失败。

关键错误：

```text
src/database.ts(14,7): error TS4023: Exported variable 'db' has or is using name 'BetterSqlite3.Database'
src/middleware/auth.ts(37,5): error TS2741: Property 'id' is missing
src/routes/merchants.ts(...): error TS2339: Property 'userId' does not exist on type 'Request'
```

判断：

- TypeScript 类型层已经失败。
- `auth.ts` 的 JWT payload/type 与实际代码不一致。
- merchants 路由没有使用扩展后的 `AuthRequest` 类型。

### 4.3 Client build

命令：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/client
npm run build
```

结果：失败。

关键错误：

```text
error TS1127: Invalid character.
```

典型文件：

```text
client/src/pages/Login.tsx
client/src/layouts/CustomerLayout.tsx
client/src/layouts/MerchantLayout.tsx
client/src/pages/customer/Home.tsx
client/src/pages/merchant/Menu.tsx
client/src/pages/rider/Available.tsx
client/src/pages/admin/Dashboard.tsx
```

抽样证据：

```text
import { useAuth } from
\342\200\246[omitted 3005 bytes, 122 lines total]
```

判断：

- 多个前端源码文件包含真实 `…[omitted ...]` 占位符，无法被 TypeScript 解析。

### 4.4 Server seed

命令：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/server
npm run seed
```

结果：失败。

关键错误：

```text
ReferenceError: __dirname is not defined in ES module scope
at server/src/database.ts:5:27
```

判断：

- `server/package.json` 使用 `"type": "module"`。
- `server/src/database.ts` 使用 CommonJS 的 `__dirname`。
- Node 24 + tsx 直接在模块加载阶段失败。

### 4.5 Server dev start

命令：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/server
npm run dev
```

结果：失败。

关键错误：

```text
ReferenceError: __dirname is not defined in ES module scope
at server/src/database.ts:5:27
```

判断：

- 后端无法启动，因此无法直接执行 waimai 自身 HTTP API 冒烟测试。

## 5. 生成的项目文档

生成路径：

```text
/Users/wish233/Documents/RD-Bot/docs/qa/waimai/01-system-design.md
/Users/wish233/Documents/RD-Bot/docs/qa/waimai/02-database-design.md
/Users/wish233/Documents/RD-Bot/docs/qa/waimai/03-api-contract.md
/Users/wish233/Documents/RD-Bot/docs/qa/waimai/04-defect-catalog.md
/Users/wish233/Documents/RD-Bot/docs/qa/waimai/05-e2e-test-report.md
```

说明：

- `docs/` 在 RD-Bot 中被 `.gitignore` 忽略，适合作为本次 QA 产物目录。
- 前 4 份文档已真实入库 RD-Bot。

## 6. RD-Bot 文档入库验证

创建知识库：

```text
knowledgeBaseId: 7474705345491898368
```

写入文档：

| 文档 | documentId | chunkCount |
| --- | --- | ---: |
| `01-system-design.md` | `7474705345605144576` | 5 |
| `02-database-design.md` | `7474705345672253440` | 5 |
| `03-api-contract.md` | `7474705345714196480` | 6 |
| `04-defect-catalog.md` | `7474705345739362304` | 4 |

入库后概览：

```json
{
  "knowledgeBaseCount": 1,
  "documentCount": 4,
  "indexedDocumentCount": 4,
  "chunkCount": 20,
  "enabledChunkCount": 20,
  "vectorChunkCount": 20
}
```

结论：

- 文档入库链路成功。
- 文档成功分块和索引。
- 后续 RAG 修复上下文可以检索到这些文档。

## 7. 真实 Bug 工单

工单 ID：

```text
WM-BUG-ESM-DATABASE-001
```

标题：

```text
waimai 后端启动失败：database.ts 在 ESM 项目中使用 __dirname
```

复现命令：

```bash
cd /Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e/server
npm run dev
```

实际结果：

```text
ReferenceError: __dirname is not defined in ES module scope
```

期望结果：

```text
后端能启动并进入 SQLite schema 初始化；seed 不应因 __dirname 失败。
```

优先级：

```text
P0
```

选择该 bug 的原因：

- 缺陷真实存在。
- 命令稳定复现。
- 修复点明确。
- 是 waimai 后端无法工作的第一阻塞。
- 适合作为自动修复系统的最小闭环验证目标。

## 8. RD-Bot 工单/RAG 通道运行

调用链：

1. `POST /test/feishu/helpdesk/mock-ticket`
2. `POST /test/feishu/helpdesk/events/ticket-created`
3. `POST /test/repair/tickets/WM-BUG-ESM-DATABASE-001/run`

结果：

```text
registered: 200
published: 200
run: 200
repairRecordId: 7474705530712363008
status: CONTEXT_READY
```

队列结果：

```json
{
  "success": true,
  "messageId": "mem-1",
  "targetTopic": "in-memory://RD_BOT_REPAIR_TICKET",
  "targetTag": "P0",
  "ticketId": "WM-BUG-ESM-DATABASE-001"
}
```

产生的 artifact：

| artifactType | 说明 |
| --- | --- |
| `FEISHU_EVENT` | Mock ticket-created event |
| `FEISHU_TICKET_SNAPSHOT` | 工单快照 |
| `FEISHU_MESSAGES` | 用户消息 |
| `RAG_CONTEXT` | RAG 检索上下文 |

RAG 命中证据：

- 命中了 `04-defect-catalog.md` 中的 ESM `__dirname` 缺陷说明。
- 命中了 `01-system-design.md` 中 `database.ts` 模块设计和风险。
- 命中了前端和 schema 不一致等相关上下文。

结论：

- P1 工单接入和 RAG 上下文准备链路可用。
- 这条 HTTP 通道当前只把记录推进到 `CONTEXT_READY`，不会触发 Claude/Docker executor。

## 9. P2 Docker executor 验证

### 9.1 Focused mock verification

命令：

```bash
./mvnw -pl exec,bootstrap -am \
  -Dtest=DockerClaudeCodeExecutorTest,ProcessContainerRunnerTest,DockerExecutorConfigurationTest,DockerAssetPolicyTest,P2RepairExecutionFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
DockerClaudeCodeExecutorTest: 15 tests, 0 failures
DockerExecutorConfigurationTest: 5 tests, 0 failures
P2RepairExecutionFlowTest: 2 tests, 0 failures
DockerAssetPolicyTest: 4 tests, 0 failures
ProcessContainerRunnerTest: 7 tests, 0 failures
BUILD SUCCESS
```

结论：

- `RdBotFixEngine + EngineBugFixExecutorAdapter + DockerClaudeCodeExecutor + CodePlatformPort` 的 mock seam 是绿色的。
- DeepSeek provider chain 配置和 Docker env 转发策略有测试保护。

### 9.2 真实 Docker image 检查

命令：

```bash
docker image inspect rd-bot/claude-code:local
```

结果：

```text
No such image: rd-bot/claude-code:local
```

### 9.3 Docker image build 尝试 1

命令：

```bash
docker build \
  -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg CLAUDE_CODE_VERSION=2.1.185 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

结果：失败并中止。

关键错误：

```text
unexpected status from GET request to https://docker.xuanyuan.me/v2/docker/dockerfile/manifests/1.7?ns=docker.io: 429 Too Many Requests
```

### 9.4 Docker image build 尝试 2

命令：

```bash
DOCKER_BUILDKIT=0 docker build \
  -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg CLAUDE_CODE_VERSION=2.1.185 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

结果：长时间无进展后中止。

关键输出：

```text
The legacy builder is deprecated
Can't add file ... Dockerfile to tar: io: read/write on closed pipe
```

本地已有镜像：

```text
redis:latest
pgvector/pgvector:pg16
rustfs/rustfs:1.0.0-alpha.72
zookeeper:latest
apache/rocketmq:5.2.0
mysql/mysql-server:5.7
```

没有可用 Node/Claude Code 镜像。

结论：

- 真实 Docker executor 未能启动，因为必须的 `rd-bot/claude-code:local` 镜像不存在，且本地构建被镜像源/基础镜像拉取阻塞。

## 10. DeepSeek Provider 使用情况

当前 `bootstrap/src/main/resources/application.yaml` 已配置 DeepSeek provider：

```yaml
rd:
  executor:
    docker:
      providers:
        - name: deepseek
          base-url: https://api.deepseek.com/anthropic
          auth-token-env: DEEPSEEK_API_KEY
```

安全说明：

- API key 没有写入报告。
- 没有写入配置文件。
- 当前设计是通过宿主机环境变量 `DEEPSEEK_API_KEY` 传入容器。
- 因 Docker 镜像未能构建，真实容器未启动，DeepSeek key 没有被实际消费。

结论：

- DeepSeek 作为 Claude Code provider 的配置路径存在。
- 本次真实自动修复未执行到 provider 调用阶段。

## 11. 为什么没有自动修复成功

自动修复没有成功，不是因为 RAG 找不到上下文，而是因为执行链路没有跑到真实代码修复阶段。

具体原因：

1. `/test/repair/tickets/{ticketId}/run` 当前是 P1 工单/RAG 通道，只产生 RAG 上下文，不调用 executor。
2. `RdBotFixEngine` 可以串起 executor，但当前没有公开 HTTP 入口接收这个真实 waimai bug 并运行。
3. `DockerClaudeCodeExecutor` 需要 `rd-bot/claude-code:local` 镜像，本机没有。
4. 镜像构建被 Docker 镜像源 429 或基础镜像拉取阻塞。
5. 即使镜像可用，当前 `RepairWorkspaceFactory` 默认只创建空 repo 目录，不会自动把 waimai 仓库复制到 `/work/repo`。

## 12. 需要补齐的系统能力

要让 RD-Bot 对 waimai 真正自动修复，需要补齐：

1. 在 P1/P2 间增加编排入口：工单 `CONTEXT_READY` 后由上游触发 `RdBotFixEngine` 或等价 executor orchestration。
2. 增加目标仓库准备步骤：根据工单 repository 字段 clone/copy 到 repair workspace 的 `repo/`。
3. 支持本地目录仓库 URL：例如 `file:///Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e`。
4. 确保 Docker image 可构建或预置。
5. 在真实 executor 运行时设置 `DEEPSEEK_API_KEY` 环境变量。
6. 运行后把 `patch.diff`、`test.log`、`claude-events.jsonl`、`result.json` 写回修复记录 artifact。
7. 对 mock GitHub 和真实 GitHub 模式分别给出本地验收路径。

## 13. Waimai 修复建议

建议按以下顺序修复 waimai：

1. 修复 `server/src/database.ts` 的 ESM 路径解析。
2. 修复 `server/src/seed.ts` 与 `schema.sql` 的字段/表名不一致。
3. 统一 JWT payload 和 auth middleware。
4. 统一后端 routes 与 schema。
5. 修复前端 TSX 文件中的 `…[omitted ...]` 占位内容。
6. 统一 `client/src/api.ts` 与后端路由。
7. 增加测试：
   - `npm run build` for server。
   - `npm run build` for client。
   - `npm run seed`。
   - 后端 `/api/health`。
   - 登录 -> 访问受保护接口。
   - 创建订单 -> 商家接单 -> 骑手配送。

## 14. 本次命令清单

Waimai：

```bash
npm install
npm run build
npm run seed
npm run dev
```

RD-Bot：

```bash
./mvnw -pl bootstrap -am -DskipTests package
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar --server.port=18080 --rd.storage.mode=memory --rd.knowledge.store=memory --rd.executor.docker.enabled=false --rd.repair.queue.mode=memory
```

RD-Bot focused tests：

```bash
./mvnw -pl exec,bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,ProcessContainerRunnerTest,DockerExecutorConfigurationTest,DockerAssetPolicyTest,P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

RD-Bot full verification：

```bash
./mvnw test
git diff --check
```

结果：

```text
./mvnw test: BUILD SUCCESS
surefire aggregate: tests=244 failures=0 errors=0 skipped=5
git diff --check: no output
```

Docker：

```bash
docker image inspect rd-bot/claude-code:local
docker build -f bootstrap/src/main/resources/executor/claude/Dockerfile --build-arg CLAUDE_CODE_VERSION=2.1.185 -t rd-bot/claude-code:local bootstrap/src/main/resources/executor/claude
DOCKER_BUILDKIT=0 docker build -f bootstrap/src/main/resources/executor/claude/Dockerfile --build-arg CLAUDE_CODE_VERSION=2.1.185 -t rd-bot/claude-code:local bootstrap/src/main/resources/executor/claude
```

## 15. 最终判定

本次端到端测试证明：

- RD-Bot 文档入库系统可用。
- RD-Bot RAG 能基于入库文档为真实 bug 生成上下文。
- RD-Bot P2 executor 的 mock 代码路径是绿色的。
- 当前系统还不能对 waimai 执行真实自动修复，原因是执行入口、仓库准备、Docker 镜像三个关键环节没有打通。

因此，本次“是否能自动修复”的答案是：

```text
不能。当前系统能完成文档入库和 RAG 上下文准备，但真实 Docker Claude Code 自动修复没有跑起来，也没有产出修复 patch。
```

## 16. 工作区说明

本次新增了 QA 工作目录：

```text
/Users/wish233/Documents/RD-Bot/qa-runs/waimai-e2e
```

该目录包含从原始 waimai 复制出的测试副本和 npm 安装结果，用于保留本次复现现场。RD-Bot 原工作树在任务开始前已经存在大量 P1/P2 未跟踪和修改文件，本次没有 stage、commit、push 或回滚任何文件。
