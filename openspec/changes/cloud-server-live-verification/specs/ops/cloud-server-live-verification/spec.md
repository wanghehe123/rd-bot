## Purpose

定义 RD-Bot 后端在指定云服务器上的标准连接方式，以及任何涉及需求交付/Pi/发布/项目记忆的验收必须在该环境用真实项目跑真实需求任务的强制合同。

## ADDED Requirements

### Requirement: 云服务器是后端 live 连接与验收锚点

系统运维与验收 SHALL 将主机 `106.55.13.166` 上的 `ubuntu` 账户与仓库目录 `~/RD-Bot` 作为后端 live 连接锚点。操作者 MUST 通过 SSH 登录该主机，或经 SSH 本地端口转发访问仅绑定在该主机 loopback 上的管理 API。密钥、PAT、CPA API key MUST 只存在于服务器环境变量或未入库的本地机密文件（例如 `~/RD-Bot/.env.opencode.local`），MUST NOT 写入 OpenSpec、Git 跟踪配置或证据报告正文。

#### Scenario: SSH 登录云服务器工作目录

- **WHEN** 操作者需要在 live 环境检查或启动后端
- **THEN** 使用 `ssh ubuntu@106.55.13.166`（可附带本机 IdentityFile）进入主机，并在 `~/RD-Bot` 下操作仓库与启动脚本

#### Scenario: 本机经隧道访问管理 API

- **WHEN** 操作者需要从笔记本电脑调用仅监听 `127.0.0.1:8080` 的云端管理 API
- **THEN** 建立 `ssh -L 8080:127.0.0.1:8080 ubuntu@106.55.13.166`，再通过 `http://127.0.0.1:8080` 访问；MUST NOT 假定公网直接暴露 8080

#### Scenario: 机密不入库

- **WHEN** 文档或 OpenSpec 描述云服务器连接
- **THEN** 只记录主机、用户、路径、端口与脚本名；MUST NOT 粘贴 `GH_TOKEN`、`GITHUB_PAT`、`CPA_API_KEY` 或其它密钥字面量

### Requirement: 后端启动必须使用云服务器约定入口

在云服务器上启动后端时，操作者 MUST 使用仓库内 `deploy/cloud-server/start-backend.sh`（或其当前等价入口），并以 `--spring.profiles.active=local` 与端口 `8080` 运行。运行时 overlay MUST 落在 JVM 工作目录的 `~/RD-Bot/application-local.yaml`（由 `deploy/cloud-server/application-local.server.yaml` 复制），MUST NOT 只更新 `bootstrap/src/main/resources/application-local.yaml` 却期望已构建 jar 读取该路径。需要 CPA 模型时，MUST 先保持 `deploy/cloud-server/start-cpa-tunnel.sh` 建立的本机 `127.0.0.1:8317` 隧道可用。GitHub 访问 MUST 使用令牌化 HTTPS rewrite，MUST NOT 将同一仓库同时 rewrite 到仅本地 bare mirror 再当作远端 push 成功。

#### Scenario: 标准启动

- **WHEN** 操作者在 `~/RD-Bot` 执行 `deploy/cloud-server/start-backend.sh`
- **THEN** 后端在 `127.0.0.1:8080` 可响应，日志写入 `/tmp/rd-bot-backend.log`，且运行时使用工作目录 overlay `application-local.yaml`

#### Scenario: CPA 隧道前置

- **WHEN** 项目策略选择 provider `cpa` 且上游仅经 SSH 隧道可达
- **THEN** 启动验收任务前 `127.0.0.1:8317` 必须可达；隧道不可用时不得声称模型路径已验证

#### Scenario: 禁止假阳性 mirror rewrite

- **WHEN** 配置 Git `insteadOf` 以便加速克隆
- **THEN** MUST NOT 让 `git push` 只写入本地 mirror 却让 publication 认为远端分支已存在；BRANCH_CONFIRMED 必须对应真实 GitHub 远端可见分支

### Requirement: Live 验收必须在云服务器上执行

凡宣告需求交付链路、Pi Agent 执行、credential-relay、GitHub publication 或 project-scoped agent memory capture「已在 live 验证」的结论，MUST 以云服务器 `106.55.13.166` 上运行的后端与其 PostgreSQL/Docker/Pi 环境为执行面。本机 IDE、mock executor、skipped integration test、或仅对本机进程的 curl/health 检查 MUST NOT 单独构成 live 通过证据。

#### Scenario: 本机单测不能替代 live

- **WHEN** 某 change 仅在开发者笔记本电脑上跑通聚焦 Maven/Node 测试
- **THEN** 证据可记为「单元/合同前置通过」，但 MUST NOT 标记为云服务器 live 验收通过

#### Scenario: 必须在云主机观察任务推进

- **WHEN** 进行 live 验收
- **THEN** 任务创建、角色阶段推进与终态 MUST 发生在云服务器后端进程所连接的数据库与执行器上，并可通过该主机日志或经隧道访问的管理 API 观测

### Requirement: Live 验收必须使用真实已登记项目

Live 验收 MUST 使用云服务器 PostgreSQL 中已存在、未删除、且已配置可执行 Agent 策略与真实 `repositoryUrl` 的 RD 项目。MUST NOT 使用空项目、占位 `example-owner/example-repo`、或仅内存/fixture 项目身份。知识库与仓库 MUST 对应该项目真实绑定关系。

#### Scenario: 使用真实项目身份

- **WHEN** 操作者提交 live 验收需求任务
- **THEN** `projectId` 指向云库中真实 `RdProject`，其 `repositoryUrl` 可被 allowlist 接受，且策略可解析到可用的 Pi + provider/model

#### Scenario: 拒绝占位项目冒充 live

- **WHEN** 任务仅绑定示例仓库 URL 或未登记项目
- **THEN** 该运行 MUST NOT 记为满足本能力的 live 验收

### Requirement: Live 验收必须提交真实需求并完成可观察交付

Live 验收 MUST 通过管理入口创建 `REQUIREMENT` 任务（`POST /admin/rd-tasks/requirements` 或其等价管理台路径），提交真实需求材料与可验证预期结果，并实际启动交付流水线。验收证据 MUST 包含：任务 ID、项目 ID、各角色 attempt/stage 终态、以及至少一种外部可观察结果（成功时的 GitHub PR URL，或失败时的精确阶段错误与日志锚点）。MUST NOT 用手工改库把 stage 标为 SUCCEEDED、用 mock 执行器返回假结果、或只断言 HTTP 202 即宣称需求已验证。

#### Scenario: 真实需求跑完可观察路径

- **WHEN** 操作者在云服务器后端对真实项目提交一条新的需求任务并启动交付
- **THEN** 系统实际调度 Pi 角色阶段；证据记录 taskId、各角色结果或失败原因，以及成功时的远端 PR/变更，或失败时不可伪造的错误类别

#### Scenario: 禁止 mock 与改库假阳性

- **WHEN** 验收过程依赖 mock executor、跳过 Docker/Pi、或直接 UPDATE 阶段状态为成功
- **THEN** 该过程 MUST 判定为无效 live 验收，不得勾选本能力相关完成门禁

#### Scenario: 证据最小集合

- **WHEN** 报告 live 验收结果
- **THEN** 证据至少包含：云主机标识、项目 ID/名称、任务 ID、所用 provider/model（若适用）、终态（COMPLETED 或精确失败阶段），以及 PR URL 或等价远端可查产物/失败日志路径
