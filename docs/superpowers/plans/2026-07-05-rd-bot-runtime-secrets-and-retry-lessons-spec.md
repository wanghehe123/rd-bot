# RD-Bot 运行密钥与重试经验 Spec

日期：2026-07-05

## 1. 目的

本 spec 固化本轮真实联调中的运行参数、排障路径和禁止重复犯错项。后续 Agent 接手 RD-Bot 多 Agent/RAG 编排验收时，必须先读本文件，再启动服务或判断任务失败原因。

本文件不得保存任何明文密钥。用户在会话中提供过真实 LongCat、MiniMax、Feishu 等凭据，但仓库 spec 只能记录变量名、协议、模型、注入方式和脱敏校验要求。

## 2. 运行密钥记录规范

### 2.1 禁止项

- 禁止把 `LONGCAT_API_KEY`、`MINIMAX_API_KEY`、`FEISHU_APP_SECRET`、`GITHUB_PAT`、`GH_TOKEN`、数据库密码或 secret scan needle 原值写入仓库。
- 禁止在最终报告、PR body、Feishu 消息、Docker metadata、阶段 prompt/result artifact、经验条目中输出明文密钥。
- 禁止为了“方便下次运行”新增 `.env`、`application-local.yaml` 或文档内明文密钥。

### 2.2 必须记录的变量名

| 系统 | 必需变量 | 当前约定 |
| --- | --- | --- |
| LongCat / Docker Claude Code | `LONGCAT_API_KEY`, `RD_CLAUDE_AUTH_TOKEN_ENV`, `LONGCAT_PROTOCOL`, `LONGCAT_ANTHROPIC_BASE_URL` | token env 为 `LONGCAT_API_KEY`，协议为 `anthropic-compatible` |
| MiniMax | `MINIMAX_API_KEY`, `MINIMAX_MODEL`, `MINIMAX_BASE_URL`, `MINIMAX_PROTOCOL` | 模型必须为 `MiniMax-M3`，本轮真实接入使用 `https://api.minimaxi.com/anthropic` + `anthropic-compatible` |
| OpenAI chat adapter | `RD_EXECUTOR_OPENAI_CHAT_ENABLED`, `RD_EXECUTOR_OPENAI_CHAT_PROTOCOL` | 本轮本机验收开启为 `true`，协议随 MiniMax 使用 `anthropic-compatible` |
| GitHub | `GH_TOKEN` / `GITHUB_PAT` / gh CLI 登录态 | 优先使用 gh CLI 已登录态；不要把 token 写进配置 |
| Feishu | `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, `FEISHU_IM_ALERT_CHAT_ID` | app secret 只从环境变量或 lark-cli 配置读取 |
| Secret scan | `RD_BOT_SECRET_SCAN_NEEDLES`, `RD_BOT_SECRET_SCAN_MASK` | needles 可临时编造或来自秘密存储，但报告只记录数量，不记录原值 |

### 2.3 本机注入方式

本机联调可以使用 `launchctl` 注入和读取密钥。命令模板只能保留占位符：

```bash
launchctl setenv LONGCAT_API_KEY '<redacted-longcat-key>'
launchctl setenv MINIMAX_API_KEY '<redacted-minimax-key>'
launchctl setenv MINIMAX_MODEL 'MiniMax-M3'
launchctl setenv MINIMAX_BASE_URL 'https://api.minimaxi.com/anthropic'
launchctl setenv MINIMAX_PROTOCOL 'anthropic-compatible'
launchctl setenv RD_EXECUTOR_OPENAI_CHAT_ENABLED 'true'
launchctl setenv RD_EXECUTOR_OPENAI_CHAT_PROTOCOL 'anthropic-compatible'
```

启动后端前必须把 `launchctl` 中的变量导回当前 shell：

```bash
export LONGCAT_API_KEY="$(launchctl getenv LONGCAT_API_KEY)"
export MINIMAX_API_KEY="$(launchctl getenv MINIMAX_API_KEY)"
export MINIMAX_MODEL="$(launchctl getenv MINIMAX_MODEL)"
export MINIMAX_BASE_URL="$(launchctl getenv MINIMAX_BASE_URL)"
export MINIMAX_PROTOCOL="$(launchctl getenv MINIMAX_PROTOCOL)"
export RD_EXECUTOR_OPENAI_CHAT_ENABLED="$(launchctl getenv RD_EXECUTOR_OPENAI_CHAT_ENABLED)"
export RD_EXECUTOR_OPENAI_CHAT_PROTOCOL="$(launchctl getenv RD_EXECUTOR_OPENAI_CHAT_PROTOCOL)"
```

验证密钥是否存在时只能输出 `SET/EMPTY`，不能打印原值。

## 3. 启动与重新加载代码的正确顺序

如果修改了 `engine` 模块，再直接运行 `./mvnw -q -pl bootstrap spring-boot:run`，bootstrap 可能继续加载本地 Maven 仓库里的旧 engine 包，导致修复看似没生效。

正确顺序：

```bash
./mvnw -q -pl engine install -DskipTests
./mvnw -q -pl bootstrap spring-boot:run
```

本轮踩坑记录：

- `./mvnw -q -pl bootstrap spring-boot:run` 能启动，但在未安装 engine 新包时会继续复现旧行为。
- `./mvnw -q -pl bootstrap -am spring-boot:run` 曾触发 Spring Boot plugin 跑到 root aggregator，出现找不到 main class 的问题；不要把它当作默认启动方式。
- 重启服务前必须停掉旧 session 或旧 PID，否则 UI 仍会命中旧代码。

## 4. 任务重试的关键经验

### 4.1 本轮真实故障

原始现象：

```text
agent stage is terminal before execution: REQUIREMENT_REVIEWER FAILED_NEEDS_HUMAN
```

根因不是密钥本身，而是第一次缺少 `LONGCAT_API_KEY` 时，`REQUIREMENT_REVIEWER attempt=1` 已落为 `FAILED_NEEDS_HUMAN`。后续点击重试时，编排代码仍然取该角色的第一条 stage run，而不是最新 attempt，于是每次都在执行前命中旧终态。

### 4.2 正确语义

- 主任务 `REJECTED`、`FAILED_NEEDS_HUMAN`、`FAILED_RETRYABLE` 必须允许重新提交。
- `COMPLETED`、`CANCELLED`、`DEAD_LETTERED`、`DELETED` 才属于不可重试终态。
- `AgentStageRun` 的终态失败不能直接 transition 回运行态，必须创建新的 `attemptNo`。
- 选择 stage run 时必须按 `attemptNo`、`createTimeEpochMillis`、`stageRunId` 取最新记录。
- 已 `SUCCEEDED` 的阶段允许复用；失败终态阶段重试必须创建下一次 attempt；尚未运行的下游阶段保留原记录。

### 4.3 代码位置

- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementDeliveryEngine.java`
  - `submit(...)`：不可把 `REJECTED` / `FAILED_NEEDS_HUMAN` 当成直接返回终态。
  - `ensureRequirementStages(...)`：对失败终态阶段创建新 attempt。
  - `stageRun(...)`：必须返回最新 attempt，而不是 `.findFirst()`。
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`
  - 必须保留“第一次 reviewer 失败、第二次提交创建新 attempt 并跑通”的回归测试。

### 4.4 必跑验证

```bash
./mvnw -q -pl engine -Dtest=RequirementDeliveryEngineTest#shouldCreateNewAgentStageAttemptWhenRejectedRequirementIsSubmittedAgain test
./mvnw -q -pl engine test
./mvnw -q test
```

## 5. 排障命令

任务仍显示旧错误时，不要猜。先查任务、timeline 和 stage 表：

```bash
curl -fsS 'http://127.0.0.1:18080/admin/rd-tasks/<taskId>' | jq '{status,errorMessage,pullRequestUrl,executionResultJson}'
curl -fsS 'http://127.0.0.1:18080/admin/rd-tasks/<taskId>/timeline' | jq '[.[] | {status,message}][-10:]'
docker exec postgres psql -U postgres -d ragent -Atc "select role,status,attempt_no,provider_name,error_category,left(error_message,160),updated_at from rd_agent_stage_runs where task_id=<taskId> order by role,attempt_no;"
```

判断方式：

- 如果 `REQUIREMENT_REVIEWER attempt=2 SUCCEEDED` 已出现，说明旧 terminal stage 问题已解除。
- 如果卡在 `CODING_AGENT RUNNING` 且 Docker 容器日志仍在输出 Claude Code 工具调用，当前是 coding agent 长耗时，不是重试 bug。
- 如果改完代码仍复现旧行为，先确认是否执行了 `./mvnw -q -pl engine install -DskipTests` 并重启了 `bootstrap`。

Docker 执行器日志：

```bash
docker ps --filter name=rd-bot-repair-<taskId>
docker logs --tail 120 rd-bot-repair-<taskId>-long-cat-1
```

## 6. 本轮验收事实

本轮针对任务 `7479367966937714688` 的复测已经证明：

- 第一次失败原因是 RD-Bot 进程缺少 provider secret 环境变量。
- 注入环境变量并重启后，重试仍报旧错，说明还有 stage attempt 选择问题。
- 修复并安装 engine 新包后，原任务不再报 `agent stage is terminal before execution`。
- 该任务进入真实链路后，`REQUIREMENT_REVIEWER attempt=2` 和 `SOLUTION_ARCHITECT attempt=1` 均已成功，随后进入 `CODING_AGENT RUNNING`。

这条经验以后不能被简化成“密钥没配”。密钥缺失只是第一次失败原因；重试失败的根因是 stage attempt 选择和终态重试语义。

## 7. 交接要求

后续 Agent 接手时必须：

1. 先确认密钥变量是否存在，但只输出 `SET/EMPTY`。
2. 先读 `bootstrap/src/main/resources/application.yaml`，不要凭记忆写 provider 参数。
3. MiniMax 模型保持 `MiniMax-M3`，不要退回 `MiniMax-M2.7`。
4. 修改 `engine` 后先安装 engine 再重启 bootstrap。
5. 对已失败任务重试时先查 `rd_agent_stage_runs`，确认是否创建了新 attempt。
6. 报告中只写变量名、协议、模型、base URL 和证据路径，不写明文 secret。
