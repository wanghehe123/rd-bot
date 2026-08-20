# RD-Bot

**AI 驱动的研发交付编排平台**（Spring Boot 模块化单体）

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

RD-Bot 把飞书 IM / 管理台需求转化为**可治理的多角色自动化交付流水线**：任务级 RAG 构建工程上下文，在 Docker 隔离沙箱中运行 Pi Agent，自动验证并创建可审查 PR，同时沉淀完整审计证据。

> 不是「会改代码的聊天机器人」，而是 **AI-native Software Delivery Harness（研发流程自动化控制面）**。

---

## 核心特性

### 多角色 Agent 流水线

```
需求评审 → 方案架构 → 编码执行 → QA 验证 → PR 交付
```

| 角色 | 职责边界 |
|------|----------|
| `REQUIREMENT_REVIEWER` | 完整性 / 安全性 / 可执行性评审；不改代码、不创建 PR |
| `SOLUTION_ARCHITECT` | 基于 RAG 证据输出可执行方案与 handoff |
| `CODING_AGENT` | Docker 沙箱改代码、跑测试，准备候选 PR body |
| `QA_AGENT` | 真实验收与回归；证据不全则阻断交付 |

### 任务级 RAG 与安全沙箱

- 多通道检索 → 结构化 `ContextPackage` / 角色上下文包
- Docker 隔离：`/work/repo` · `/work/input` · `/work/output` · `/work/cache`
- 仓库 / 分支白名单；Secret 扫描；Provider 密钥仅环境变量注入

### Skill Hub 与治理

- Skill 目录、角色绑定、风险门禁（LOW / MEDIUM / HIGH → `WAITING_APPROVAL`）
- Pi 原生 Skill 显式加载：系统提示渐进披露 `description`
- 可选「强制引导」：`forceGuide` 时在 Prompt 末尾追加用户 `guidePrompt`
- 修复队列：Redis Stream（替代 RocketMQ）

---

## 架构概览

```
 Clients / Events                Delivery
 ┌──────────────────┐           ┌─────────────────┐
 │ React+Vite 管理台 │           │ GitHub / Git    │
 │ Feishu Webhook   │           │ Branches + PRs  │
 └────────┬─────────┘           └────────▲────────┘
          │                              │
          ▼                              │
 ┌────────────────────────────────────────────────────────────┐
 │                     bootstrap（宿主）                        │
 │  REST / 管理控制器 · Postgres+MyBatis · Redis/Redisson     │
 │  RustFS/S3 · Docker · 外部适配器                            │
 └───────────────────────────┬────────────────────────────────┘
                             │
     ┌───────────────────────┼───────────────────────┐
     ▼                       ▼                       ▼
 ┌─────────┐           ┌──────────┐           ┌──────────┐
 │ engine  │           │   rag    │           │   exec   │
 │ 编排    │──────────▶│ 知识上下文│◀──────────│ 执行面   │
 └────┬────┘           └──────────┘           └────┬─────┘
      │                                            │
      │  RequirementExecutorPort                   │ AgentRuntimeRouter
      │  （需求执行桥）                              │
      └────────────────────┬───────────────────────┘
                           ▼
              DockerPiAgentExecutor（唯一容器执行路径）
                           ▲
 ┌─────────────────────────┴─────────────────────────┐
 │ skill：目录 · 角色绑定 · Manifest 校验 · 物化到     │
 │        /work/input/skills + skill-manifest.json    │
 └───────────────────────────────────────────────────┘
```

**依赖方向（强制）**：`bootstrap → engine|exec|skill|rag`；`engine|exec|skill → rag`。禁止反向依赖。

### 需求执行桥

`RequirementDeliveryEngine` / `RequirementAgentStageOrchestrator`
→ `RequirementExecutorPort`（bootstrap 适配）
→ `AgentRuntimeRouter`
→ `DockerPiAgentExecutor`

`AgentRuntimeType` 仍保留 `CLAUDE_CODE` / `MODEL_ONLY` 枚举值以便读取历史快照，但已无对应执行器：真被请求时 router 抛 `UnsupportedAgentRuntimeException`。

每次 attempt：物化 resource / skill manifest → 写 `request.json` → Pi bridge 加载原生 Skill → 宿主 Validator 验收。

### 存储分工

| 层 | 职责 |
|----|------|
| **PostgreSQL** | 事实源：任务、状态事件、阶段 run、检索 run、配置 |
| **Redis** | 协调：分布式锁、模型健康状态 |
| **RustFS / S3** | 内容：大产物、QA 证据、可选 Pi 原始事件 / session |
| **Docker** | 隔离：每 attempt 独立 workspace |

---

## 显式状态机

权威定义：`RdTaskTransitionPolicy` · `AgentStageTransitions` · `RetrievalRunTransitionPolicy`。

### 任务级 `RdTaskStatus`（需求交付主链）

```
CREATED → MATERIAL_COLLECTING → MATERIAL_READY → CONTEXT_BUILDING → CONTEXT_READY
  → PLAN_GENERATING → PLAN_GENERATED → WAITING_POLICY
  → [WAITING_APPROVAL?] → EXECUTING → VERIFYING → CREATING_PR
  → SUBMITTED → REPORTING → COMPLETED → MERGED
```

- 策略可在 `PLAN_GENERATED` 后进入 `WAITING_APPROVAL`
- 失败态（`REJECTED` / `FAILED_RETRYABLE` / `FAILED_NEEDS_HUMAN` / `CANCELLED` / `DEAD_LETTERED`）可转入 `RECOVERING`（分支，非并行链）

### Agent 阶段级 `AgentStageStatus`（含 `attemptNo`）

```
PENDING → CONTEXT_READY → DISPATCHING → RUNNING
  → COLLECTING_RESULT → VERIFYING → SUCCEEDED
```

- `FAILED_RETRYABLE` 是**本次 attempt 终态**；重试新建 `attemptNo`
- 另有 `FAILED_NEEDS_HUMAN` / `CANCELLED` / `SKIPPED` 等出口

### 落库对照

| 概念 | 主要表 / 存储 |
|------|----------------|
| 任务快照 | `rd_tasks` |
| 任务时间线 | `rd_task_status_events`（append-only） |
| Agent attempt | `rd_agent_stage_runs` · `rd_agent_stage_events` · `rd_agent_private_artifacts` |
| 角色上下文 / 检索 | `rd_role_context_packages` · `rd_rag_retrieval_runs` |
| 重试 / 调度 | `rd_task_retry_checkpoints` · `rd_requirement_delivery_jobs` |
| QA / 大文件 | RustFS URI · `rd_qa_evidence_objects` |

---

## 模块说明

| 模块 | 职责 |
|------|------|
| `bootstrap` | Spring Boot 宿主、REST、管理前端静态资源、Postgres/Redis/S3/Docker/飞书适配 |
| `engine` | `RequirementDeliveryEngine`、阶段编排、状态机与策略 |
| `rag` | 知识库、检索、角色上下文包、任务运行时端口与 Trace |
| `exec` | `AgentRuntimeRouter`、Pi Docker 执行器、结果 / QA Validator |
| `skill` | Skill 目录端口、安装编排、角色白名单与风险门禁 |
| `frontend` | React + Vite 管理台（任务 / 项目 / RAG / Skill Hub） |

---

## 快速开始

### 环境要求

| 依赖 | 版本 / 说明 |
|------|-------------|
| JDK | 21+ |
| Docker + Docker Compose | 20+ / Compose v2；`docker compose up` 拉起 Postgres（含 **pgvector**）、Redis、MinIO |
| Node.js | 18+（前端开发，可选） |

> **安全**：`/admin` 当前**无鉴权**。仅在本机或可信网络使用；详见 [SECURITY.md](SECURITY.md)。

### 本地启动

```bash
docker compose up -d
./scripts/bootstrap-db.sh
cp bootstrap/src/main/resources/application-local.example.yaml \
  bootstrap/src/main/resources/application-local.yaml
# optional: cp .env.example .env.local   # then export $(grep -v '^#' .env.local | xargs)
./mvnw install -DskipTests
# application-local.yaml is gitignored and excluded from the boot jar — load it explicitly:
SPRING_PROFILES_ACTIVE=local \
SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:./bootstrap/src/main/resources/application-local.yaml \
  ./mvnw -pl bootstrap spring-boot:run
open http://127.0.0.1:18080/admin/
```

Compose 默认：数据库 **`rdbot`**（`postgres` / `postgres`）、Redis `6379`、MinIO `9000`（`rustfsadmin` / `rustfsadmin`）。迁移细节见 `bootstrap/src/main/resources/sql/postgres/README.md`。本地 profile 默认关闭 Agent Docker 运行时与飞书监听，适合先冒烟管理台。

**完整 Agent 交付**前再构建 Pi 镜像，并打开运行时 / 白名单 / Provider Key（见下方环境变量）：

```bash
docker build -f bootstrap/src/main/resources/executor/pi/Dockerfile \
  -t rd-bot/pi-agent:local bootstrap/src/main/resources/executor/pi
docker build -f bootstrap/src/main/resources/executor/pi/Dockerfile.qa \
  -t rd-bot/pi-agent-qa:local bootstrap/src/main/resources/executor/pi
```

### 前端开发

```bash
cd frontend && npm install && npm run dev
# Vite :5173，代理 /admin/* → 后端 18080
```

### 常用环境变量

本地 profile 的连接串以 `application-local.yaml` 为准；也可覆盖：

```bash
export POSTGRES_URL="jdbc:postgresql://127.0.0.1:5432/rdbot"
export POSTGRES_USERNAME="postgres"
export POSTGRES_PASSWORD="postgres"
export RD_EXECUTOR_AGENT_RUNTIME_ENABLED=true
export RD_EXECUTOR_PI_IMAGE=rd-bot/pi-agent:local
export RD_EXECUTOR_PI_QA_IMAGE=rd-bot/pi-agent-qa:local
```

**完整 Agent 交付另需密钥与仓库白名单**（否则仅管理台冒烟）：

```bash
export GITHUB_PAT="..."                    # 或 GH_TOKEN；创建 PR / clone
# Provider API keys（按 execution profile 实际引用，常见）：
export OPENCODE_API_KEY="..."
export DEEPSEEK_API_KEY="..."
export LONGCAT_API_KEY="..."
export MINIMAX_API_KEY="..."
# Docker 执行面仓库 / 分支白名单（application.yaml → rd.executor.docker.security）
export RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY_URL="https://github.com/your-org/your-repo.git"
export RD_EXECUTOR_DOCKER_ALLOWED_REPOSITORY="your-org/your-repo"
export RD_EXECUTOR_DOCKER_ALLOWED_BASE_BRANCH="main"
export RD_EXECUTOR_DOCKER_ALLOWED_WORK_BRANCH="repair/*"
export RD_EXECUTOR_DOCKER_ALLOWED_REQUIREMENT_BRANCH="requirement/*"
```

可选：`.env.example` 可复制为 `.env` 供 Compose / shell 引用。维护者私有启动脚本见 `scripts/owner/`（非贡献者必需）。

改动 Pi bridge（`bootstrap/src/main/resources/executor/pi`）后需重建镜像，否则容器仍用旧规则。

---

## Skill Hub

| 能力 | 说明 |
|------|------|
| 管理页 | `/admin/skills`：目录、上传、角色绑定、强制引导、HIGH 审批 |
| API | `/admin/skills` · `/admin/skills/upload` · `/admin/skills/role-bindings/{role}` |
| 运行时 | 按角色物化到 `/work/input/skills/{id}`；Pi `additionalSkillPaths` 显式加载（保持 `noSkills: true` 阻断仓库自动扫描） |
| 两层启用 | ① 系统提示披露 desc；② `forceGuide` 追加 `guidePrompt` |

---

## 核心 API（节选）

```bash
# 任务
curl -X POST http://127.0.0.1:18080/admin/rd-tasks/requirements -H 'Content-Type: application/json' -d '{...}'
curl http://127.0.0.1:18080/admin/rd-tasks/{taskId}

# Skill Hub
curl http://127.0.0.1:18080/admin/skills
curl http://127.0.0.1:18080/admin/skills/role-bindings
```

| 管理台路径 | 功能 |
|------------|------|
| `/admin/dashboard` | 总览 |
| `/admin/knowledge` | 知识库 |
| `/admin/projects` | 项目与执行配置 |
| `/admin/rd-tasks` | 任务工作台 |
| `/admin/skills` | Skill Hub |
| `/admin/traces` | 执行追踪 |
| `/admin/observability` | 交付观测 |

---

## 测试

```bash
./mvnw test
./mvnw -pl engine,exec,skill,bootstrap -am test

# Pi bridge
cd bootstrap/src/main/resources/executor/pi && npm test
```

---

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 / 框架 | Java 21 · Spring Boot 3.5 |
| 事实库 | PostgreSQL + MyBatis-Plus |
| 协调 | Redis · Redisson（锁 + Redis Stream） |
| 对象存储 | S3 兼容（RustFS / MinIO） |
| 执行 | Docker · Pi Agent（主） |
| 前端 | React 18 · Vite · Tailwind · Radix |
| 构建 | Maven 多模块 |

---

## 设计原则

1. **模型可替换**：Provider 是 worker，不是系统核心。
2. **PR-based 交付**：Agent 永不直接合并主干。
3. **证据驱动**：prompt / result / log / diff / QA 证据可回放。
4. **端口-适配器**：飞书、GitHub、队列、模型 SDK 可 Mock。
5. **显式状态机 + 持久化事件**：跨实例状态不依赖 JVM 内存。
6. **安全纵深**：白名单、Secret 扫描、Docker 隔离、Skill 显式路径加载。

---

## License

[Apache License 2.0](LICENSE)
