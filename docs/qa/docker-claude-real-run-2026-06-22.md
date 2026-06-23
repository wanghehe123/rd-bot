# Docker Claude Code 真实运行记录（2026-06-22）

## 结论

本次真实运行验证了九件事：

1. `rd-bot/claude-code:local` 镜像已存在，容器内 `claude` CLI 可启动。
2. Docker entrypoint 的输入挂载、输出产物、失败兜底 artifact 协议可工作。
3. 已修复干净容器首次使用 `--dangerously-skip-permissions` 的交互确认问题，镜像构建时预置 `/home/rdbot/.claude.json`。
4. 已新增 `/test/repair/bugfix/run`，可以从 HTTP 侧真实触发 `RdBotFixEngine` 和 Docker executor。
5. 注入 DeepSeek 凭据后，Docker Claude Code 能真实调用 DeepSeek Anthropic-compatible endpoint。
6. 真实执行暴露并修复了结构化结果校验器对模型额外字段过严的问题。
7. 真实执行暴露并修复了 `patch.diff` 被 entrypoint 覆盖为空的问题。
8. 最终端到端执行成功：RAG 命中 3 个 chunk，DeepSeek 生成修复、结构化结果、patch 和测试日志，Java 侧返回 `COMMITTED`。
9. 已修复执行镜像缺少 Java/Maven 时只能跳过测试的问题：容器继续保持非 root 用户运行，但允许 `rdbot` 免密执行受限的 `apt-get/apt`；真实 DeepSeek 运行中，Claude Code 自行安装 Java 17 和 Maven，并完成 `mvn test`，测试结果 `PASSED`。

## 镜像信息

- Image: `rd-bot/claude-code:local`
- Image ID: `sha256:3deb10673bc37e5d6aff2a7b66205dd55a04e784532cf49b86aea514e7e39fbc`
- Created: `2026-06-22T10:40:27.264654333Z`
- Size: `174086119`
- CLI check: `claude --version` -> `1.0.0 (Claude Code)`
- User check: `id` -> `uid=999(rdbot) gid=999(rdbot) groups=999(rdbot)`
- Yolo acceptance check: `/home/rdbot/.claude.json` -> `{"bypassPermissionsModeAccepted":true}`

## 运行 1：干净容器直接跑 entrypoint

- Run dir: `qa-runs/docker-claude-real-20260622T085515Z`
- 输入：
  - `input/prompt.md`
  - `input/result.schema.json`
  - `repo/README.md`
- 输出文件：
  - `output/claude-events.jsonl`
  - `output/container.stdout.log`
  - `output/docker-meta.json`
  - `output/patch.diff`
  - `output/result.json`
  - `output/test.log`

关键输出：

```text
--dangerously-skip-permissions must be accepted in an interactive session first.
```

`docker-meta.json`：

```json
{
  "startedAt": "2026-06-22T08:56:28Z",
  "endedAt": "2026-06-22T08:56:32Z",
  "user": "rdbot",
  "workingDirectory": "/work/repo",
  "outputDirectory": "/work/output",
  "exitCode": 1
}
```

`result.json` 由 entrypoint 兜底生成：

```json
{
  "status": "FAILED",
  "summary": "Claude Code did not write result.json. See claude-events.jsonl and test.log for details.",
  "changedFiles": [],
  "testCommands": [],
  "testStatus": "SKIPPED",
  "riskLevel": "HIGH",
  "prBody": "",
  "needHumanAction": true
}
```

判断：Docker 镜像和 artifact 协议是通的，但干净容器不满足 Claude Code yolo 首次确认要求。

## 运行 2：挂载最小 Claude home 后跑 entrypoint

- Run dir: `qa-runs/docker-claude-real-accepted-20260622T085835Z`
- 额外挂载：`home/.claude.json`
- 最小配置内容只包含：

```json
{"bypassPermissionsModeAccepted":true}
```

关键输出：

```json
{"type":"system","subtype":"init","session_id":"b392ad34-b919-42f3-8c57-9fa75e66eddf","tools":["Task","Bash","Glob","Grep","LS","Read","Edit","MultiEdit","Write","NotebookRead","NotebookEdit","WebFetch","TodoRead","TodoWrite","WebSearch"],"mcp_servers":[]}
{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Invalid API key · Please run /login"}]},"session_id":"b392ad34-b919-42f3-8c57-9fa75e66eddf"}
{"type":"result","subtype":"success","cost_usd":0,"is_error":true,"duration_ms":13651,"duration_api_ms":0,"num_turns":1,"result":"Invalid API key · Please run /login","total_cost":0,"session_id":"b392ad34-b919-42f3-8c57-9fa75e66eddf"}
```

`docker-meta.json`：

```json
{
  "startedAt": "2026-06-22T08:59:04Z",
  "endedAt": "2026-06-22T08:59:18Z",
  "user": "rdbot",
  "workingDirectory": "/work/repo",
  "outputDirectory": "/work/output",
  "exitCode": 1
}
```

产物大小：

```text
0     output/patch.diff
952   output/claude-events.jsonl
952   output/container.stdout.log
```

判断：挂载最小确认配置后，Claude Code 非交互执行可进入 `stream-json` 流程；失败点推进到认证。当前 shell 环境缺少 `DEEPSEEK_API_KEY`，所以没有真实调用 DeepSeek 模型完成修复。

## 运行 3：Spring Boot 测试通道系统级运行

启动方式：

```bash
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar \
  --server.port=18080 \
  --rd.executor.docker.enabled=true \
  --rd.executor.docker.image=rd-bot/claude-code:local \
  --rd.executor.docker.workspace-root=/Users/wish233/Documents/RD-Bot/qa-runs/rd-bot-real-executor-workspaces \
  --rd.executor.docker.timeout-alert-millis=60000 \
  --rd.knowledge.store=memory \
  --rd.repair.queue.mode=memory \
  --rd.github.code-platform.mode=mock \
  --rd.feishu.helpdesk.enabled=false \
  --rd.rag.retrieval-log.path=/Users/wish233/Documents/RD-Bot/qa-runs/rd-bot-real-executor-rag.jsonl
```

触发方式：

```bash
curl -X POST http://127.0.0.1:18080/test/repair/tickets/FS-MOCK-1/run
```

HTTP 结果：

- Status: `200`
- Total time: `0.171547s`
- Response file: `qa-runs/rd-bot-real-executor-http/run-response.json`
- Repair record: `7474748754533421056`
- Repair status: `CONTEXT_READY`
- Artifacts:
  - `FEISHU_EVENT`
  - `FEISHU_TICKET_SNAPSHOT`
  - `FEISHU_MESSAGES`
  - `RAG_CONTEXT`

RAG 检索日志已落盘：

- File: `qa-runs/rd-bot-real-executor-rag.jsonl`
- Ticket: `FS-MOCK-1`
- Retrieved chunks: 3
  - `payment-api.md#0`
  - `payment-service#OrderService.java#42`
  - `log-center#payment-system#0`

判断：系统级测试通道跑通了 Mock 飞书工单、RAG 检索、repair record 和 RAG JSONL 记录。但该入口按当前设计只推进到 `CONTEXT_READY`，没有触发 `RdBotFixEngine` / Docker executor，因此不会生成 Docker workspace。

源码边界：

- `TicketRepairEngine` 注释明确当前流程是 `QUEUED -> CONTEXT_COLLECTING -> CONTEXT_READY`。
- `TicketRepairEngine.prepareRagContext(...)` 只调用 `RagBugFixEngine.findBugFixMessgaesForAgent(...)` 并写入 `RAG_CONTEXT` artifact。
- `RdBotFixEngine` 才会调用 `BugFixExecutor.execute(...)`，但当前没有对应 REST 测试入口。

## 运行 4：修复后镜像构建和干净容器复测

修复内容：

- 文件：`bootstrap/src/main/resources/executor/claude/Dockerfile`
- 变更：镜像构建阶段写入最小 Claude Code yolo 接受配置。

```dockerfile
printf '%s\n' '{"bypassPermissionsModeAccepted":true}' > /home/rdbot/.claude.json
```

重新构建：

```bash
docker build -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg NODE_BASE_IMAGE=node:22-bookworm-slim \
  --build-arg CLAUDE_CODE_VERSION=1.0.0 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

容器内检查：

```text
{"bypassPermissionsModeAccepted":true}

uid=999(rdbot) gid=999(rdbot) groups=999(rdbot)
1.0.0 (Claude Code)
```

干净容器复测：

- Run dir: `qa-runs/docker-claude-fixed-20260622T091341Z`
- 输出文件：
  - `output/claude-events.jsonl`
  - `output/container.stdout.log`
  - `output/docker-meta.json`
  - `output/patch.diff`
  - `output/result.json`
  - `output/test.log`

关键输出：

```json
{"type":"system","subtype":"init","session_id":"c4937313-aeac-4d7c-bd5e-93b6d5e0022a"}
{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Invalid API key · Please run /login"}]},"session_id":"c4937313-aeac-4d7c-bd5e-93b6d5e0022a"}
{"type":"result","is_error":true,"duration_ms":11339,"result":"Invalid API key · Please run /login","session_id":"c4937313-aeac-4d7c-bd5e-93b6d5e0022a"}
```

判断：修复后已经不再出现 `--dangerously-skip-permissions must be accepted in an interactive session first.`。干净容器能进入 Claude Code `stream-json` 执行流程，剩余失败点推进到模型认证。

## 运行 5：修复后 HTTP 触发真实 Docker executor

新增测试入口：

- 文件：`bootstrap/src/main/java/com/wish/rd/bootstrap/controller/testchannel/BugFixExecutionTestChannelController.java`
- Endpoint：`POST /test/repair/bugfix/run`
- 职责：直接调用 `RdBotFixEngine.runBugFix(...)`，用于本地端到端验证 RAG、Prompt 和执行器端口；不改变正式工单入口职责。

启动方式：

```bash
java -jar bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar \
  --server.port=18081 \
  --rd.executor.docker.enabled=true \
  --rd.executor.docker.image=rd-bot/claude-code:local \
  --rd.executor.docker.workspace-root=/Users/wish233/Documents/RD-Bot/qa-runs/rd-bot-real-executor-workspaces-fixed \
  --rd.executor.docker.timeout-alert-millis=60000 \
  --rd.knowledge.store=memory \
  --rd.repair.queue.mode=memory \
  --rd.github.code-platform.mode=mock \
  --rd.feishu.helpdesk.enabled=false \
  --rd.rag.retrieval-log.path=/Users/wish233/Documents/RD-Bot/qa-runs/rd-bot-real-executor-fixed-rag.jsonl
```

触发方式：

```bash
curl -sS -X POST http://127.0.0.1:18081/test/repair/bugfix/run \
  -H 'Content-Type: application/json' \
  -d '{"ticketId":"FS-EXEC-FIXED-1","title":"支付系统下单接口 500","description":"金额为空时 OrderService.create 写入订单失败","labels":["payment","orders.amount"],"logs":["ERROR orders.amount is null at OrderService.create"],"priority":"P1"}'
```

HTTP 结果：

- Status: `200`
- Total time: `10.023722s`
- Response file: `qa-runs/rd-bot-real-executor-fixed-http/run-response.json`
- Task ID: `7474751987528110080`
- Status: `COMMITTED`
- RAG retrieved chunks: `3`
- Execution summary: `Claude Code did not write result.json. See claude-events.jsonl and test.log for details.`
- Provider attempts:
  - `deepseek` -> `FAILED`, `container exited with code 1`
  - `anthropic` -> `FAILED`, `container exited with code 1`

RAG 检索日志：

- File: `qa-runs/rd-bot-real-executor-fixed-rag.jsonl`
- Ticket: `FS-EXEC-FIXED-1`
- Retrieved chunks:
  - `payment-api.md#0`
  - `payment-service#OrderService.java#42`
  - `log-center#payment-system#0`

Docker workspace：

- Root: `qa-runs/rd-bot-real-executor-workspaces-fixed/7474751987528110080`
- Input:
  - `input/context.json`
  - `input/prompt.md`
  - `input/result.schema.json`
- Output:
  - `output/claude-events.jsonl`
  - `output/docker-meta.json`
  - `output/patch.diff`
  - `output/result.json`
  - `output/test.log`

`docker-meta.json` 关键字段：

```json
{
  "containerName": "rd-bot-repair-7474751987528110080-anthropic-2",
  "image": "rd-bot/claude-code:local",
  "networkMode": "bridge",
  "removeAfterExit": true,
  "exitCode": 1,
  "durationMillis": 4909
}
```

`claude-events.jsonl` 关键输出：

```json
{"type":"system","subtype":"init","session_id":"5cc7f6b5-17c7-45ea-a21f-3ccfe0ca4d9b"}
{"type":"assistant","message":{"model":"<synthetic>","content":[{"type":"text","text":"Invalid API key · Please run /login"}]},"session_id":"5cc7f6b5-17c7-45ea-a21f-3ccfe0ca4d9b"}
{"type":"result","is_error":true,"duration_ms":4263,"result":"Invalid API key · Please run /login","session_id":"5cc7f6b5-17c7-45ea-a21f-3ccfe0ca4d9b"}
```

判断：HTTP 侧已经真实进入 `RdBotFixEngine -> BugFixExecutor -> DockerClaudeCodeExecutor -> Docker container`。RAG 检索内容已按要求落盘到 JSONL，可用于后续 RAG 测评。执行失败不是镜像不存在或 yolo 交互确认，而是当前执行环境没有可用模型凭据。

## 运行 6：注入 DeepSeek 凭据后首次真实执行

启动环境：

- 端口：`18082`
- 凭据：通过进程环境变量注入，未写入配置文件或报告明文。
- Workspace root: `qa-runs/rd-bot-real-executor-workspaces-deepseek`
- RAG log: `qa-runs/rd-bot-real-executor-deepseek-rag.jsonl`

HTTP 结果：

- Status: `200`
- Total time: `162.568501s`
- Task ID: `7474757310175383552`
- RAG retrieved chunks: `3`
- DeepSeek provider: 已真实调用，但结果被 Java 结构化校验拦截。

Provider attempts:

```json
[
  {
    "attempt": "1",
    "status": "FAILED_VALIDATION",
    "provider": "deepseek",
    "errorMessage": "parse error: Unrecognized field \"taskId\" (class com.wish.rd.exec.repair.result.StructuredRepairResult), not marked as ignorable"
  },
  {
    "attempt": "2",
    "status": "FAILED",
    "provider": "anthropic",
    "errorMessage": "container exited with code 1"
  }
]
```

判断：DeepSeek 凭据和网络链路已经可用，失败根因从认证推进到本地结构化协议适配。模型输出多带了 `taskId` 字段，而 `StructuredResultValidator` 通过 Jackson record 映射时默认拒绝未知字段，导致本来可读的结果被判定为 `FAILED_VALIDATION`。

修复：

- 文件：`exec/src/main/java/com/wish/rd/exec/repair/result/StructuredResultValidator.java`
- 变更：内部 `ObjectMapper` copy 后关闭 `FAIL_ON_UNKNOWN_PROPERTIES`。
- 回归测试：`StructuredResultValidatorTest.extraModelFieldsDoNotInvalidateKnownProtocolFields`。

红测证据：

```text
StructuredResultValidatorTest.extraModelFieldsDoNotInvalidateKnownProtocolFields
parse error: Unrecognized field "taskId"
```

绿测证据：

```text
StructuredResultValidatorTest: 20 tests, 0 failures, 0 errors
DockerClaudeCodeExecutorTest: 15 tests, 0 failures, 0 errors
```

## 运行 7：修复校验器后真实执行

启动环境：

- 端口：`18083`
- Workspace root: `qa-runs/rd-bot-real-executor-workspaces-deepseek-fixed`
- RAG log: `qa-runs/rd-bot-real-executor-deepseek-fixed-rag.jsonl`

HTTP 结果：

- Status: `200`
- Total time: `240.594583s`
- Task ID: `7474767083549626368`
- Java 侧 status: `COMMITTED`
- Provider attempts: `deepseek` 第 1 次即 `SUCCESS`
- Container exit code: `0`
- Docker duration: `240319ms`
- Mock PR URL: `https://github.com/local/repository/pull/repair-7474767083549626368`

DeepSeek 产出：

- `result.json`: `SUCCESS`
- `changedFiles`: `src/services/OrderService.js`
- `testCommands`: `npm test`
- `testStatus`: `PASSED`
- 容器内测试：`7` tests passed

发现的新问题：

- Java 侧 artifact 中 `patch.diff` 为 `0` 字节。
- 原因：entrypoint 在 Claude Code 结束后无条件执行 `git diff --binary > "$PATCH_FILE"`。DeepSeek 已经在容器内 commit 了修复，工作区无未提交 diff，所以它把模型写入的 patch 覆盖为空。

修复：

- 文件：`bootstrap/src/main/resources/executor/claude/rd-claude-entrypoint.sh`
- 变更：先把未提交 diff 写入临时文件；若非空才覆盖 `patch.diff`；若为空且模型已写非空 `patch.diff` 则保留；若二者都没有，则尝试 `git diff --binary HEAD~1 HEAD` 记录最后一次 commit diff。
- 回归测试：`DockerAssetPolicyTest.entrypointShouldUseWorkOutputOnlyForGeneratedArtifacts` 增加 patch 保留策略断言。

## 运行 8：修复 patch artifact 后最终真实执行

启动环境：

- 端口：`18084`
- Workspace root: `qa-runs/rd-bot-real-executor-workspaces-deepseek-patch-fixed`
- RAG log: `qa-runs/rd-bot-real-executor-deepseek-patch-fixed-rag.jsonl`
- Response file: `qa-runs/rd-bot-real-executor-deepseek-patch-fixed-http/run-response.json`

HTTP 结果：

- Status: `200`
- Total time: `188.959282s`
- Task ID: `7474768767151640576`
- Java 侧 status: `COMMITTED`
- `rejected`: `false`
- RAG retrieved chunks: `3`
- Provider attempts: `deepseek` 第 1 次即 `SUCCESS`
- Container exit code: `0`
- Docker duration: `188675ms`
- Mock PR URL: `https://github.com/local/repository/pull/repair-7474768767151640576`

RAG 检索命中：

- `payment-api.md#0`
- `payment-service#OrderService.java#42`
- `log-center#payment-system#0`

输出产物：

```text
112076 output/claude-events.jsonl
  1382 output/docker-meta.json
  2018 output/patch.diff
  2435 output/result.json
   797 output/test.log
```

最终 `result.json`：

```json
{
  "status": "SUCCESS",
  "summary": "在 OrderService.create 中添加 orders.amount null 校验，缺失时抛出 IllegalArgumentException 避免数据库层异常导致 HTTP 500。",
  "changedFiles": [
    "src/main/java/com/payment/OrderService.java",
    "src/test/java/com/payment/OrderServiceTest.java"
  ],
  "testCommands": [
    "mvn test -Dtest=OrderServiceTest"
  ],
  "testStatus": "SKIPPED",
  "riskLevel": "LOW",
  "needHumanAction": false
}
```

最终 `patch.diff` 关键内容：

```diff
diff --git a/src/main/java/com/payment/OrderService.java b/src/main/java/com/payment/OrderService.java
@@ -12,6 +12,9 @@ public class OrderService {
     public Order create(Order order) {
+        if (order.getAmount() == null) {
+            throw new IllegalArgumentException("orders.amount must not be null");
+        }
         order.setStatus("PENDING");
         return orderRepository.save(order);
     }
diff --git a/src/test/java/com/payment/OrderServiceTest.java b/src/test/java/com/payment/OrderServiceTest.java
new file mode 100644
```

测试日志判断：

- DeepSeek 生成了 `OrderServiceTest`，覆盖正常金额与 `amount=null` 两个场景。
- 最终 Java demo workspace 的容器运行环境缺少 Java 17/Maven，所以 `testStatus=SKIPPED`，`test.log` 标记 `PENDING_EXECUTION`。
- 这不影响自动修复链路结论：RAG、DeepSeek 调用、代码修改、patch artifact、result artifact、mock PR 创建全部跑通；但最终补丁仍需要在 CI 或具备 Java/Maven 的执行镜像中跑真实测试。

## 运行 9：修复工具链按需安装后真实执行

目标：验证执行镜像缺少 Java 17/Maven 时，不再让修复任务跳过测试，而是让容器内的 Claude Code 按需自行安装工具链并继续完成验证。

### 问题复现

修复前直接进入容器检查权限：

```bash
docker run --rm --entrypoint sh rd-bot/claude-code:local -lc 'id; command -v sudo || true; command -v apt-get || true; command -v java || true; command -v mvn || true; apt-get update >/tmp/apt.log 2>&1; status=$?; echo apt_get_update_exit=$status; tail -20 /tmp/apt.log'
```

关键输出：

```text
uid=999(rdbot) gid=999(rdbot) groups=999(rdbot)
/usr/bin/apt-get
apt_get_update_exit=100
E: List directory /var/lib/apt/lists/partial is missing. - Acquire (13: Permission denied)
```

判断：镜像保持 `USER rdbot` 是正确的，但没有 `sudo` 和受限安装权限。Claude Code 即使知道需要 Java/Maven，也无法在容器内安装。

### 修复内容

Dockerfile 保持非 root 运行，同时只给 `rdbot` 开放受限的免密 apt 安装能力：

```dockerfile
sudo
printf '%s\n' 'rdbot ALL=(root) NOPASSWD: /usr/bin/apt-get, /usr/bin/apt' > /etc/sudoers.d/rdbot-toolchain
chmod 0440 /etc/sudoers.d/rdbot-toolchain
```

Prompt 增加执行边界：

```text
如果验证命令依赖的工具链缺失，必须先按需安装缺失工具链再执行测试。当前隔离容器允许使用 `sudo apt-get update` 和 `sudo apt-get install -y <packages>` 安装开源构建工具，例如 Java 17、Maven、Node.js 或 npm。不要因为缺少工具链直接跳过测试；只有安装失败、包不可用或外部服务缺失时，才在 `testSummary` 和测试日志中说明原因。
```

覆盖测试：

- `DockerAssetPolicyTest` 约束 Dockerfile 必须安装 `sudo`、写入 `/etc/sudoers.d/rdbot-toolchain`，且只允许 `apt-get/apt`。
- `RdBotFixEngineTest` 约束生成给 agent 的 prompt 必须包含“按需安装缺失工具链”、`sudo apt-get update`、`sudo apt-get install`、`Java 17`、`Maven`。

聚焦测试命令：

```bash
./mvnw -pl engine,bootstrap -am -Dtest=RdBotFixEngineTest,DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：

```text
RdBotFixEngineTest: 2 tests, 0 failures, 0 errors
DockerAssetPolicyTest: 4 tests, 0 failures, 0 errors
BUILD SUCCESS
```

### 镜像重建和直接容器验证

重建命令：

```bash
docker build -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg NODE_BASE_IMAGE=node:22-bookworm-slim \
  --build-arg CLAUDE_CODE_VERSION=1.0.0 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

新镜像：

- Image ID: `sha256:3deb10673bc37e5d6aff2a7b66205dd55a04e784532cf49b86aea514e7e39fbc`
- Created: `2026-06-22T10:40:27.264654333Z`
- Size: `174086119`

直接验证受限 sudo 安装：

```bash
docker run --rm --entrypoint sh rd-bot/claude-code:local -lc 'set -e; id; sudo -n apt-get update >/tmp/apt-update.log; echo sudo_apt_update=ok; sudo -n apt-get install -y --no-install-recommends openjdk-17-jdk-headless maven >/tmp/apt-install.log; echo sudo_apt_install=ok; java -version 2>&1 | head -3; mvn -version | head -5'
```

关键输出：

```text
uid=999(rdbot) gid=999(rdbot) groups=999(rdbot)
sudo_apt_update=ok
sudo_apt_install=ok
openjdk version "17.0.19" 2026-04-21
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 17.0.19
```

### 真实 HTTP + DeepSeek 执行

启动环境：

- 端口：`18085`
- Workspace root: `qa-runs/rd-bot-real-executor-workspaces-toolchain`
- RAG log: `qa-runs/rd-bot-real-executor-toolchain-rag.jsonl`
- Response file: `qa-runs/rd-bot-real-executor-toolchain-http/run-response.json`
- 凭据：通过进程环境变量注入，未写入配置文件或报告明文。

触发请求：

```bash
curl -sS -X POST http://127.0.0.1:18085/test/repair/bugfix/run \
  -H 'Content-Type: application/json' \
  -d '{"ticketId":"FS-DEEPSEEK-TOOLCHAIN-1","title":"支付系统下单接口 500","description":"金额为空时 OrderService.create 写入订单失败","labels":["payment","orders.amount"],"logs":["ERROR orders.amount is null at OrderService.create"],"priority":"P1"}'
```

HTTP 结果：

- Status: `200`
- Total time: `279.872115s`
- Task ID: `7474773743164854272`
- Java 侧 status: `COMMITTED`
- `rejected`: `false`
- RAG retrieved chunks: `3`
- Provider attempts: `deepseek` 第 1 次即 `SUCCESS`
- Container exit code: `0`
- Docker duration: `279650ms`
- Mock PR URL: `https://github.com/local/repository/pull/repair-7474773743164854272`

RAG 检索命中：

- `payment-api.md#0`
- `payment-service#OrderService.java#42`
- `log-center#payment-system#0`

### Claude Code 自行安装证据

`claude-events.jsonl` 显示模型明确决定先安装工具链：

```text
Let me install Java and Maven first, then create the project.
```

随后 Claude Code 在容器内执行：

```bash
sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk-headless maven 2>&1 | tail -20
```

工具链验证输出：

```text
openjdk version "17.0.19" 2026-04-21
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 17.0.19
```

最终测试日志：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
EXIT_CODE=0
```

### 输出产物

Docker workspace：

- Root: `qa-runs/rd-bot-real-executor-workspaces-toolchain/7474773743164854272`
- Input:
  - `input/context.json`
  - `input/prompt.md`
  - `input/result.schema.json`
- Output:
  - `output/claude-events.jsonl`
  - `output/docker-meta.json`
  - `output/patch.diff`
  - `output/result.json`
  - `output/test.log`

产物大小：

```text
120320 output/claude-events.jsonl
  1349 output/docker-meta.json
 27469 output/patch.diff
  1884 output/result.json
  6185 output/test.log
```

最终 `result.json`：

```json
{
  "status": "SUCCESS",
  "changedFiles": [
    "src/main/java/com/payment/service/OrderService.java",
    "src/main/java/com/payment/controller/OrderController.java",
    "src/test/java/com/payment/service/OrderServiceTest.java"
  ],
  "testCommands": [
    "cd /work/repo && mvn test"
  ],
  "testStatus": "PASSED",
  "riskLevel": "LOW",
  "needHumanAction": false
}
```

最终 `patch.diff` 关键内容：

```diff
diff --git a/src/main/java/com/payment/service/OrderService.java b/src/main/java/com/payment/service/OrderService.java
@@ -16,6 +16,9 @@ public class OrderService {
     public Order create(String productId, BigDecimal amount) {
+        if (amount == null) {
+            throw new IllegalArgumentException("orders.amount must not be null");
+        }
         Order order = new Order(productId, amount);
         return orderRepository.save(order);
     }
diff --git a/src/main/java/com/payment/controller/OrderController.java b/src/main/java/com/payment/controller/OrderController.java
+    @ExceptionHandler(IllegalArgumentException.class)
+    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
+        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
+                .body(Map.of("error", ex.getMessage()));
+    }
```

补充观察：Claude Code 后续尝试用 `python3` 校验 JSON 时遇到 `/bin/bash: line 1: python3: command not found`，但它没有因此中断，最终仍写出 `result.json`、`patch.diff` 和 `test.log`，容器退出码为 `0`。当前目标是让 Java/Maven 测试链路可按需安装，已验证完成；如后续希望 agent 常用 Python 脚本，也可以把 Python 纳入基础镜像或提示词允许安装列表。

判断：运行 8 暴露的 `testStatus=SKIPPED` 已被修复。当前真实链路已经覆盖 RAG 检索、DeepSeek 调用、Claude Code 按需安装 Java/Maven、真实 Maven 测试、结构化结果、patch artifact、mock PR 创建和 Java 侧 `COMMITTED`。

## 代码级验证

新增和更新的覆盖：

- `BugFixExecutionTestChannelControllerTest`：证明 `/test/repair/bugfix/run` 会调用 `RdBotFixEngine` 并返回 RAG 与执行器结果。
- `DockerAssetPolicyTest`：证明 Dockerfile 预置 `/home/rdbot/.claude.json` 和 `bypassPermissionsModeAccepted`，同时继续约束非 root 用户、固定 Claude Code 版本和不复制凭据。
- `DockerAssetPolicyTest`：证明 Dockerfile 安装 `sudo`，并只向 `rdbot` 开放 `/usr/bin/apt-get` 和 `/usr/bin/apt` 的免密 sudo 权限。
- `DockerAssetPolicyTest`：证明 entrypoint 不会把模型已经生成的 `patch.diff` 无条件覆盖为空。
- `RdBotFixEngineTest`：证明传给 agent 的修复 prompt 包含工具链按需安装指令。
- `StructuredResultValidatorTest`：证明模型输出额外字段时，不会再触发 Jackson unknown field parse error。

验证命令：

```bash
./mvnw -pl bootstrap -am -Dtest=DockerAssetPolicyTest,BugFixExecutionTestChannelControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl exec -Dtest=StructuredResultValidatorTest,DockerClaudeCodeExecutorTest test
./mvnw -pl exec,bootstrap -am -Dtest=StructuredResultValidatorTest,DockerClaudeCodeExecutorTest,DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl engine,bootstrap -am -Dtest=RdBotFixEngineTest,DockerAssetPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw test
git diff --check
awk '/[ \t]$/ {print FILENAME ":" FNR ": trailing whitespace"; bad=1} END {exit bad}' <新增文件和报告路径>
```

结果：

- 聚焦测试：`5` tests, `0` failures, `0` errors, `0` skipped。
- 结构化校验器 + Docker executor 聚焦测试：`35` tests, `0` failures, `0` errors, `0` skipped。
- 结构化校验器 + Docker executor + Docker asset 策略测试：`39` tests, `0` failures, `0` errors, `0` skipped。
- 工具链按需安装 prompt + Docker asset 策略测试：`6` tests, `0` failures, `0` errors, `0` skipped。
- 全量测试：`251` tests, `0` failures, `0` errors, `5` skipped。
- `git diff --check`：无输出。
- 新增文件尾随空白检查：无输出。

## 剩余问题和后续动作

### 已处理：模型凭据注入

DeepSeek 凭据已通过进程环境变量注入运行，不写入仓库配置、报告明文或 Docker artifact。`docker-meta.json` 仅记录 `-e DEEPSEEK_API_KEY` 变量名。

### 已处理：执行镜像缺少 Java/Maven 时可按需安装

运行 8 暴露的 Java/Maven 缺失问题已处理。当前镜像继续使用 `rdbot` 非 root 用户运行 Claude Code，但允许受限免密执行 `sudo apt-get update` 和 `sudo apt-get install -y <packages>`。运行 9 中，Claude Code 自行安装 Java 17/Maven 并完成 `mvn test`，`testStatus=PASSED`。

### 已处理：镜像预置 yolo 接受状态

修复前干净容器会失败：

```text
--dangerously-skip-permissions must be accepted in an interactive session first.
```

当前已在 Dockerfile 中为 `rdbot` 预置最小 `/home/rdbot/.claude.json`：

```json
{"bypassPermissionsModeAccepted":true}
```

修复后干净容器可进入 Claude Code `stream-json` 流程。

### 已处理：真实执行测试入口

当前已新增：

- `POST /test/repair/bugfix/run`：直接调用 `RdBotFixEngine.runBugFix(...)`。

该入口保持 `/test/...` 隔离，不改变生产工单入口只准备 RAG 上下文的职责边界。
