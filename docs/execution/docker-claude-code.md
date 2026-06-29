# Docker Claude Code 执行器操作说明

## 架构边界

P2 修复执行链路保持分层边界：

- `engine` 负责编排 `RdBotFixEngine`，把 RAG 上下文打包成 prompt，并通过 `BugFixExecutor` 端口触发执行。
- `exec` 定义执行模型、Docker 执行器、结构化 `result.json` 校验、告警策略、代码平台端口和工单回写端口。
- `bootstrap` 提供外部适配器：Docker CLI runner、GitHub adapter、mock code-platform、mock ticket write-back、PostgreSQL repository。
- `rag` 不直接调用 Docker、GitHub、Feishu、PostgreSQL、Claude SDK 或 RocketMQ。

Claude Code 通过 Docker 内的 CLI 与 Java 通信。Java 写入工作区输入文件，容器写回标准输出文件；当前没有 Java Claude SDK 依赖。

## 本地 Mock Smoke

不依赖 Docker、GitHub、Claude、Feishu、RocketMQ 或 PostgreSQL：

```bash
./mvnw -pl bootstrap -am -Dtest=P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试串起 `RdBotFixEngine + EngineBugFixExecutorAdapter + DockerClaudeCodeExecutor`，只替换容器 runner 和 code-platform 端口。成功路径应创建 1 个 mock PR；validation failure 路径应创建 0 个 PR，且 PR URL 为空。

更完整的 focused mock 验证：

```bash
./mvnw -pl exec test
./mvnw -pl bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,GitHubCodePlatformAdapterTest,P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 真实 Docker Smoke 前置条件

真实 Docker runner 默认关闭。启用前需要：

1. 已安装 Docker CLI，当前用户可执行 `docker run`。
2. 已构建 Claude Code 镜像，版本必须是精确 semver，不能使用 `latest` 或版本范围：

```bash
docker build \
  -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg NODE_BASE_IMAGE=node:22-bookworm-slim \
  --build-arg CLAUDE_CODE_VERSION=1.0.0 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

`NODE_BASE_IMAGE` 默认为 `node:22-bookworm-slim`。如果本机 Docker 镜像源无法访问
Docker Hub，可以先把可用的 Node 基础镜像拉到本机或推到内部镜像仓库，然后覆盖该参数：

```bash
docker build \
  -f bootstrap/src/main/resources/executor/claude/Dockerfile \
  --build-arg NODE_BASE_IMAGE=<internal-registry>/node:22-bookworm-slim \
  --build-arg CLAUDE_CODE_VERSION=1.0.0 \
  -t rd-bot/claude-code:local \
  bootstrap/src/main/resources/executor/claude
```

### 镜像不存在排查

如果执行器报 `rd-bot/claude-code:local` 不存在，先确认本地镜像：

```bash
docker image inspect rd-bot/claude-code:local
```

如果镜像不存在，按上一节构建。若构建过程中出现 `docker/dockerfile` frontend 拉取失败，
当前 Dockerfile 已避免使用外部 frontend 语法镜像；重新构建即可验证是否还命中该问题。

如果构建仍失败在 `node:22-bookworm-slim` 拉取阶段，说明是基础镜像源不可用，需要任选一种方式配合：

- 修复 Docker Desktop 的 registry mirror，使 `docker pull node:22-bookworm-slim` 可成功。
- 提供离线基础镜像包并执行 `docker load -i <node-image.tar>`。
- 提供内部仓库中的 Node 镜像地址，并通过 `--build-arg NODE_BASE_IMAGE=...` 指定。

3. 运行环境以环境变量或宿主机 secret 方式提供 Claude Code 所需凭据。runner 只传递环境变量名，不把原始 secret 写入 argv 或 `docker-meta.json`。
4. 目标仓库已被挂载或复制到每个 repair workspace 的 `repo/` 目录。

## 配置键

Docker 执行器配置前缀：`rd.executor.docker`。

- `rd.executor.docker.enabled`: 默认 `false`。只有 `true` 才注册 `ProcessContainerRunner`。
- `rd.executor.docker.image`: 默认 `rd-bot/claude-code:local`。
- `rd.executor.docker.workspace-root`: 默认 `/tmp/rd-bot/repair-workspaces`。
- `rd.executor.docker.command`: 默认 `claude`。
- `rd.executor.docker.yolo-flag`: 默认 `--dangerously-skip-permissions`。
- `rd.executor.docker.output-format`: 默认 `stream-json`。
- `rd.executor.docker.network-mode`: 默认 `bridge`。
- `rd.executor.docker.remove-after-exit`: 默认 `true`。
- `rd.executor.docker.timeout-alert-millis`: 默认 `1800000`。
- `rd.executor.docker.budget-alert-usd`: 默认 `5.00`。
- `rd.executor.docker.providers`: Claude Code 模型供应商降级链。每次执行按顺序尝试 provider。

默认配置集中在 `bootstrap/src/main/resources/application.yaml`。本地无需再散落查找 PG/Redis/RocketMQ/Docker
参数；默认仍保持内存队列、内存知识库、Docker 关闭，避免没有外部依赖时启动失败。

### Claude Code 第三方模型 Provider

`providers` 由 Java 外层控制降级链，而不是在容器入口脚本里切换。每次 Docker 运行只注入当前
provider 的环境变量，执行结果的 `dockerMetadataJson` 会记录：

- `provider`: 最终返回结果的 provider 名称。
- `providerAttemptsJson`: 每次尝试的 provider、attempt、status 和 errorMessage。

DeepSeek 配置示例：

```yaml
rd:
  executor:
    docker:
      providers:
        - name: deepseek
          base-url: https://api.deepseek.com/anthropic
          auth-token-env: DEEPSEEK_API_KEY
          model: "deepseek-v4-pro[1m]"
          default-opus-model: "deepseek-v4-pro[1m]"
          default-sonnet-model: "deepseek-v4-pro[1m]"
          default-haiku-model: deepseek-v4-flash
          subagent-model: deepseek-v4-flash
          effort-level: max
        - name: anthropic
          api-key-env: ANTHROPIC_API_KEY
```

密钥只写宿主机环境变量名，不写入配置值：

```bash
export DEEPSEEK_API_KEY=...
export ANTHROPIC_API_KEY=...
```

`auth-token-env=DEEPSEEK_API_KEY` 会让 Java 只传递 `DEEPSEEK_API_KEY` 变量名和
`RD_CLAUDE_AUTH_TOKEN_ENV=DEEPSEEK_API_KEY`。容器入口脚本在容器内把它映射成
`ANTHROPIC_AUTH_TOKEN`，不会把真实 key 写入 Docker argv 或 `docker-meta.json`。

降级只针对基础设施/协议失败：

- Docker runner 返回 null。
- Docker 进程非 0 退出。
- 缺失或非法 `result.json`。
- `SUCCESS` 缺少标准 artifact 导致 `FAILED_VALIDATION`。

以下业务结果不会触发降级：`NEED_INFO`、`UNSAFE`、测试失败、模型按协议返回 `FAILED`。

仓库配置由 `EngineBugFixExecutorAdapter` 使用：

- `rd.executor.repository.owner`
- `rd.executor.repository.name`
- `rd.executor.repository.url`
- `rd.executor.repository.base-branch`
- `rd.executor.repository.work-branch-prefix`: 默认 `repair/`

## Artifact 协议

每个修复任务的 workspace：

```text
<workspace-root>/<taskId>/
  input/
    context.json
    prompt.md
    result.schema.json
  repo/
  output/
    result.json
    patch.diff
    test.log
    claude-events.jsonl
    docker-meta.json
```

成功结果必须包含 5 个标准 output artifacts：

- `result.json`
- `patch.diff`
- `test.log`
- `claude-events.jsonl`
- `docker-meta.json`

`result.json` 必须满足：

- `status`: `SUCCESS`, `FAILED`, `NEED_INFO`, `UNSAFE`
- `summary`: 非空
- `changedFiles`: 数组；仅 `NEED_INFO` 或 `FAILED` 可为空
- `testCommands`: 数组；仅 `testStatus=SKIPPED` 可为空
- `testStatus`: `PASSED`, `FAILED`, `SKIPPED`
- `riskLevel`: `LOW`, `MEDIUM`, `HIGH`
- `prBody`: `SUCCESS` 时必须非空
- `needHumanAction`: `NEED_INFO`、`UNSAFE` 或 `testStatus=FAILED` 时必须为 `true`

缺失 `result.json`、非法 JSON、业务校验失败，或 `SUCCESS` 缺少 `patch.diff`/`test.log`/`claude-events.jsonl`，都会返回 `FAILED_VALIDATION`，并且不会调用 code-platform PR 端口。

## Claude Code Yolo 模式

默认命令包含 `--dangerously-skip-permissions`。该 yolo 模式只允许在 Docker 沙箱内使用，不能直接在宿主机工作区运行。沙箱边界由容器镜像、挂载目录、网络模式和 secret 传递策略共同控制。

## 告警行为

`RepairExecutionWatchdog` 会根据 timeout 和 budget 阈值产生告警，例如超时预警或预算预警。当前策略只告警，不默认 kill Docker 容器；是否停止任务由 RD 后续人工或治理流程决定。

## GitHub Auth

代码平台通过 `CodePlatformPort` 抽象。默认 `rd.github.code-platform.mode=MOCK`，零配置启动。

真实模式使用 GitHub App 作为生产推荐认证方式：

- `rd.github.code-platform.mode=REAL`
- `rd.github.code-platform.allowed-repositories`: 推荐设置 allowlist，例如 `acme/order`
- `rd.github.code-platform.auth-mode=GITHUB_APP`: 生产推荐
- `rd.github.code-platform.app-id`
- `rd.github.code-platform.installation-id`
- `rd.github.code-platform.private-key-ref`: 只接受 `env:VAR`、`file:URI` 或绝对路径

本地 smoke fallback：

- `rd.github.code-platform.auth-mode=GH_CLI_LOCAL_SMOKE`: 默认本地真实 PR 路径，复用宿主机 `gh auth login` 的 keyring 登录态，不要求把 PAT 写入 RD-Bot 配置。
- `rd.github.code-platform.gh-cli-command`: 默认 `gh`
- `rd.github.code-platform.auth-mode=PAT_LOCAL_SMOKE`
- `rd.github.code-platform.pat-token`

PAT 只用于显式本地 smoke 或临时 fallback，不作为生产默认，也不作为本地默认。

## Feishu 写回限制

P2 当前只提供通用 `TicketWriteBackPort` 和默认 mock adapter。真实 Feishu 写回仍然出于范围外，必须等用户提供精确 API 字段、认证方式、状态枚举和写回格式后再实现。当前代码不猜 Feishu 工单字段名或状态枚举。

## 最终验证

首选 focused verification：

```bash
./mvnw -pl exec test
./mvnw -pl bootstrap -am -Dtest=DockerClaudeCodeExecutorTest,DockerExecutorConfigurationTest,GitHubCodePlatformAdapterTest,P2RepairExecutionFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

环境允许时再跑完整验证：

```bash
./mvnw test
```

如果本地缺少 PostgreSQL、Docker、GitHub、Claude 或 Feishu 凭据，报告缺失项，并保留 focused mock 验证结果作为 P2 验收证据。
