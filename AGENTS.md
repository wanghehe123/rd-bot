# RD-Bot 中文开发手册

本文件适用于仓库内全部代码。改动前请先完整阅读。该说明基于当前代码库的真实结构，不再假设不存在的模块。

---

## 一、项目定位

RD-Bot 是研发交付编排系统，而非聊天机器人。

平台价值在于：

- 用可替换模型能力完成交付任务。
- RAG 证据检索与上下文打包。
- 多角色流水线编排（需求评审、方案、编码、QA）。
- 执行隔离与可回放产物。
- 政策门控、告警、恢复和审计闭环。

目标链路是：

`工单/需求 -> 上下文构建 -> 方案 -> 策略 -> 执行 -> 验证 -> PR -> 报告 -> 审计`

---

## 二、仓库结构（当前）

这是 `pom.xml` 里的实际模块：

- `rag`：知识、检索、上下文、trace、任务运行时基础域。
- `engine`：编排层，承接单任务和多角色工作流。
- `exec`：执行/验证能力抽象与接入（Docker executor、模型执行结果、健康治理）。
- `skill`：可复用能力切片与技能策略。
- `bootstrap`：Spring Boot 启动、HTTP API、配置、持久化适配、管理端能力。
- `frontend/`：前端工程目录，当前不在 Maven 依赖构图中，静态页面由 `bootstrap` 托管。

依赖方向规则（代码实际）：

- `bootstrap -> engine`
- `bootstrap -> exec`
- `bootstrap -> skill`
- `bootstrap -> rag`
- `engine -> rag`
- `exec -> rag`
- `skill -> rag`

请以 `pom.xml`/子模块 `pom.xml` 为最终准绳。

---

## 三、核心概念与角色（按当前实现）

- `RdTask`：任务主对象（`rd_tasks`），对应需求/工单交付需求。
- `RdTaskStatus`：任务状态机，当前枚举为
  `CREATED, MATERIAL_COLLECTING, MATERIAL_READY, CONTEXT_BUILDING, CONTEXT_READY, PLAN_GENERATING, PLAN_GENERATED, WAITING_POLICY, WAITING_APPROVAL, SEARCHING, EXECUTING, VALIDATING, PR_CREATING, COMMITTED, MERGED, REPORTING, COMPLETED, REJECTED, FAILED_RETRYABLE, FAILED_NEEDS_HUMAN, CANCELLED, DEAD_LETTERED, RECOVERING, DELETED`
- `RepairRecord`：主执行记录（含 `repair_records` 等持久化链路）。
- `RequirementDeliveryEngine`：需求交付主编排入口（多角色）。
- `AgentRole`：`REQUIREMENT_REVIEWER -> SOLUTION_ARCHITECT -> CODING_AGENT -> QA_AGENT`。
- `AgentStageRun`：每个角色一次执行尝试。
- `AgentStageStatus`：阶段状态机，`PENDING -> CONTEXT_READY -> DISPATCHING -> RUNNING -> RESULT_COLLECTING -> VERIFYING -> SUCCEEDED`，以及失败/恢复分支。
- `RoleContextPackage`：按角色裁剪的上下文包。
- `WorkflowExperience`：经验沉淀类型（需求评审/方案/代码/QA/交付复核）。
- `PolicyGate`：高风险动作前的显式门控。

---

## 四、当前进度（P0~P3）

### P0：基础底座

- PostgreSQL 持久化方向已落地。
- SQL 与历史状态落库脚本在
  `bootstrap/src/main/resources/sql/postgres`，包含 `rd_tasks`、`repair_records`、`repair_record_artifacts`、`repair_audit_events` 等。
- `RdTask` 运行时状态和事件轨迹可观测。

### P1：需求入口与调度

- Feishu/工单/队列体系采用接口化抽象；RocketMQ 接入方向已在配置和适配里。
- 管理端任务创建与手工触发通道已打通。

### P2：多角色交付与执行

- `RequirementDeliveryEngine` 已按 4 个角色分阶段执行。
- `Docker Claude Code`、provider 列表、fallback/retry/超时告警链路已纳入执行面。
- PR 发布与产物归档路径已可用，并通过管理 API 查询。

### P3：治理与生产运营

- 告警、告警分类、重试与恢复、指标、技能策略、经验复用在持续补齐。
- 当前建议把“治理增强”与“旧行为兼容性”绑定：任何增强都需带状态与产物断言。

---

## 五、架构与开发边界

1. `engine`、`exec` 只做业务控制和领域编排，不直接接触基础设施 SDK 细节；SDK 与外部客户端封装在 `bootstrap`。
2. 任何外部系统调用都必须通过端口/适配器（ticket、queue、storage、model、code platform、alert）。
3. 共享状态流转只能通过状态机 + 持久化事件，不能靠局部内存“顺便推进”。
4. 跨实例共享状态禁止直接依赖 JVM 内的 `synchronized` 作为兜底锁。
5. 禁止在核心代码中硬编码敏感凭证、API 秘钥与测试环境密钥。

---

## 六、状态机与阶段编排（必须遵循）

### 6.1 主任务状态

任务主状态实际流转包含并行入口与兜底分支，推荐按 `RdTaskStatus` 枚举实现，核心链路可用：

`CREATED -> MATERIAL_COLLECTING -> MATERIAL_READY -> CONTEXT_BUILDING -> CONTEXT_READY -> PLAN_GENERATING -> PLAN_GENERATED -> WAITING_POLICY -> WAITING_APPROVAL -> SEARCHING/EXECUTING -> VALIDATING -> PR_CREATING -> COMMITTED -> MERGED/REPORTING -> COMPLETED`

失败与恢复要落到：

- `FAILED_RETRYABLE`
- `FAILED_NEEDS_HUMAN`
- `REJECTED`
- `RECOVERING`
- `CANCELLED`
- `DEAD_LETTERED`
- `DELETED`（只做删除语义标记，不是正常流转）

### 6.2 阶段状态

每个 `AgentStageRun` 使用 `AgentStageStatus` 进行完整追踪。

- 每个阶段必须有：上下文包 ID、provider 尝试记录、产物 ID（prompt/result/log）、失败分类、阶段时间线。
- 进入失败分支时必须保留原因并可恢复，不得无日志短路终止。
- `REQUIREMENT_REVIEWER` 结果为 `NEED_INFO / NEEDS_HUMAN / UNSAFE / REJECTED / FAILED` 时，必须阻断后续阶段并写入人工干预事件。
- 重试失败终态阶段时，禁止直接 transition 旧 `AgentStageRun`；必须创建新的 `attemptNo`。
- `RequirementDeliveryEngine` 选择阶段运行记录时必须取同角色最新 attempt，不能取第一条历史记录。否则会反复触发
  `agent stage is terminal before execution: REQUIREMENT_REVIEWER FAILED_NEEDS_HUMAN`。

---

## 七、配置与凭证（AGENT 必须执行）

1. 凭证只允许通过环境变量/秘密存储读取，不得写入仓库文本。
2. 配置真值优先 `bootstrap/src/main/resources/application.yaml`，缺失值通过环境变量回退。
3. 仅记录变量名，不记录明文：

- 长猫模型：`RD_CLAUDE_*` / `LONGCAT_*`
- MiniMax 模型：`MINIMAX_*`
- GitHub：`GITHUB_PAT` / `GH_TOKEN` / GitHub App 变量
- Feishu：`FEISHU_APP_ID`, `FEISHU_APP_SECRET`, `FEISHU_IM_ALERT_CHAT_ID`
- 告警/扫描：`RD_BOT_SECRET_SCAN_NEEDLES`, `RD_BOT_SECRET_SCAN_MASK`
- 执行/仓库：`RD_EXECUTOR_*`, `RD_GITHUB_*`, `FEISHU_*`, `ROCKETMQ_*`, `POSTGRES_*`, `REDIS_*`

4. 2026-07-05 真实联调经验已固化在
   `docs/superpowers/plans/2026-07-05-rd-bot-runtime-secrets-and-retry-lessons-spec.md`。
   后续 Agent 处理 provider、重试、生产验收或本机启动问题前必须先读该 spec。
5. MiniMax 当前运行模型为 `MiniMax-M3`；不得退回旧的 `MiniMax-M2.7`。本机 Docker Claude Code provider 链如需走 MiniMax，使用
   `MINIMAX_PROTOCOL=anthropic-compatible` 和 `MINIMAX_BASE_URL=https://api.minimaxi.com/anthropic`，密钥仍只通过
   `MINIMAX_API_KEY` 环境变量读取。
6. 修改 `engine` 后启动 `bootstrap` 前，必须先让 bootstrap 能加载到新 engine 包；本机最小顺序为
   `./mvnw -q -pl engine install -DskipTests` 后再 `./mvnw -q -pl bootstrap spring-boot:run`。
   不要把 stale engine jar 导致的旧行为误判为修复无效。
7. 遇到
   `provider long-cat is missing required auth environment variable(s): LONGCAT_API_KEY`
   时，根因是 **RD-Bot 后端进程自身** 没有继承 `LONGCAT_API_KEY`，不是 Docker 容器内临时缺变量，也不是需求内容被打回。
   处理必须遵守：
   - 不得把 LongCat key 写入 `AGENTS.md`、spec、`.env`、`application*.yaml`、命令日志或 PR 文本。
   - 先用脱敏方式确认当前 shell 与 `launchctl` 是否为 `SET/EMPTY`，禁止打印明文：
     `if [ -n "${LONGCAT_API_KEY:-}" ]; then echo SET; else echo EMPTY; fi`。
   - 若本地环境允许 `launchctl setenv`，只记录变量名并重启后端；启动前再把 `launchctl` 中变量导回当前 shell。
   - 当前代码兼容 IDEA/GUI 启动：`DockerClaudeCodeExecutor` 先读 RD-Bot 进程环境，再读 macOS `launchctl getenv <ENV_NAME>`；只要
     `launchctl getenv LONGCAT_API_KEY` 为 `SET` 且当前构建包含最新 `exec` 代码，IDEA 后端即使自身未继承 shell `export` 也应通过预检。
   - `ProcessContainerRunner` 需要把解析出的敏感变量写入 Docker CLI 子进程环境，让 `docker run -e LONGCAT_API_KEY` 能继承值；不得把密钥拼进 argv、metadata、日志或 `docker-meta.json`。
   - 若 Codex 沙箱或当前终端对 `launchctl setenv` 返回 `Not privileged to set domain environment`，必须改用进程级注入：在同一个 shell 中用静默输入读取 key，`export LONGCAT_API_KEY` 后立即启动
     `./mvnw -q -pl bootstrap spring-boot:run`；不要把 key 放进命令行参数。
   - 启动命令至少带上：
     `RD_CLAUDE_AUTH_TOKEN_ENV=LONGCAT_API_KEY`、
     `LONGCAT_PROTOCOL=anthropic-compatible`、
     `LONGCAT_ANTHROPIC_BASE_URL=https://api.longcat.chat/anthropic`。
8. 处理 provider 密钥或 allowlist 后，必须重启 `bootstrap`，然后用真实任务反查验证：
   - `curl -fsS 'http://127.0.0.1:18080/admin/rd-tasks/<taskId>' | jq '{status,errorMessage}'`
   - `docker exec postgres psql -U postgres -d ragent -Atc "select role,status,attempt_no,provider_name,error_category,left(error_message,160),updated_at from rd_agent_stage_runs where task_id=<taskId> order by role,attempt_no;"`
   - `docker ps --filter name=rd-bot-repair-<taskId>`
   若出现新的 `REQUIREMENT_REVIEWER attempt=N RUNNING/SUCCEEDED` 或 Docker 容器已启动，说明 LongCat key 已被进程继承；此时不应再把问题归因到缺 key。
9. 如果 `./mvnw -q -pl bootstrap spring-boot:run` 启动时报 `NoClassDefFoundError` 且类名来自 `rag`/`engine`/`exec`/`skill` 的已迁移包，先执行
   `./mvnw -q install -DskipTests`，再启动 `bootstrap`。这是本地 Maven 仓库中的 stale module jar，不是 provider 密钥问题。
10. 修改 `exec` 的 Docker/provider 鉴权链路后，至少运行
    `./mvnw -q -pl exec -Dtest=DockerClaudeCodeExecutorTest test` 和
    `./mvnw -q -pl bootstrap -Dtest=ProcessContainerRunnerTest test`；如从 IDEA 或单模块 Maven 启动仍读到旧行为，先执行
    `./mvnw -q -pl exec install -DskipTests`，避免 stale `exec` jar 误导排查。

---

## 八、告警、技能与经验沉淀

### 告警

- 告警类型至少覆盖：provider 降级、阶段失败重试、QA 阻断、交付复核失败、PR 发布失败、知识库刷新失败、票据写回失败。
- 告警需能回溯：`messageId`、`taskId`、`stage`、`nextAction`、`reason`。

### 技能治理

- 技能/工具必须先注册、再策略判定、再安装执行；高风险能力必须有角色/作用域限制。
- 非授权或高风险技能安装流程必须拒绝并留档。

### 经验沉淀

- 交付成功后至少产出：
  - `REQUIREMENT_REVIEW`
  - `TECHNICAL_DESIGN`
  - `CODE_CHANGE`
  - `QA_REPORT`
  - `DELIVERY_REPORT`
- 每条经验需可追溯：`taskId`、`stageRunId`、`sourceArtifactId`、`contentHash`、`redacted=true`。

---

## 九、测试与验收（与 RULE 对齐）

1. 先补领域单测与失败路径；再做模块/集成验证。
2. 重点链路要有真实 HTTP 请求链回归：至少一条成功链 + 一条状态变更链 + 一条反查链。
3. `SKIPPED` 不等于通过，生产验收必须记录实际调用证据。
4. 本地缺外部服务时可跳过真实外部调用，但跳过必须显式写在验收记录里并给出补测命令模板。

---

## 十、开发约束（强制）

- 修改前先读相关代码与 `docs`；
- 不做无关重构和一次性大改，优先最小闭环；
- 遵循 `RULE.md`，尤其是分层、命名、异常、测试、可观测性约束；
- 不随意回滚他人未授权改动；
- 不提交、不推送、不建 PR，除非用户明确要求；
- 交付报告必须列明：通过测试、失败项/缺失环境、实链验证命令与证据路径。

---

## 十一、硬约束（生产环境）

1. 任何阶段化交付不得回到“单角色全链路”模式。
2. 角色上下文必须独立，禁止只依赖前一角色未证据化输出。
3. 需求评审不过关不得进入编码/交付。
4. provider 重试、阶段失败、人工恢复、经验复用必须都写到可审计字段。
5. 涉及生产密钥与外部副作用的行为必须经过脱敏、告警和 PR/记录链路。

任何“看起来已实现”但未留产物的优化，不算交付。
