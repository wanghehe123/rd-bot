# RD-Bot

**AI 驱动的研发交付编排平台**

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

RD-Bot 将工单或研发需求转化为**可治理的自动化交付流水线**。通过任务级 RAG 构建工程上下文，在 Docker 隔离沙箱中运行编码 Agent，自动验证结果并创建可审查 PR，同时记录完整的审计证据链。

> 不是"一个会修 Bug 的聊天机器人"，而是 **AI-native Software Delivery Harness（研发流程自动化控制面）**。

---

## ✨ 核心特性

### 🤖 多角色 Agent 流水线

```
需求评审 → 方案设计 → 编码执行 → QA 验证 → PR 交付
```

- **需求评审 Agent**：判断需求完整性、安全性和可执行性，不通过则阻断后续阶段
- **方案架构 Agent**：基于 RAG 证据生成可执行开发方案
- **编码 Agent**：在 Docker 沙箱中执行代码修改
- **QA Agent**：验证修改正确性，失败则阻断交付

### 📚 任务级 RAG 上下文构建

- 多通道并行检索：向量语义、BM25 关键词、日志中心、代码仓库
- 意图树分类锁定目标系统和知识范围
- 结构化 `ContextPackage`：不只是文本片段，而是包含相关文件、根因假设、验证命令的工程上下文
- 经验沉淀与复用：历史交付经验自动检索注入后续任务

### 🔒 安全隔离执行

- Docker 容器级隔离，Agent 不直接操作宿主机
- 仓库/分支白名单控制
- Secret 扫描：产物和 PR 内容自动检测敏感信息
- Provider 密钥仅通过环境变量注入，零硬编码

### 📋 PR-based 交付（非直接合并）

- Agent 只提交可审查变更，**永远不直接合并主干**
- PR body 自动包含：上下文摘要、风险评估、测试证据、产物链接
- 交付复核通过后才允许创建 PR

### 🛡️ 策略门控与治理

- 高风险动作必须经过 `PolicyGate` 和人工审批
- 完整状态机：25+ 状态覆盖正常流转、失败重试、人工恢复、死信队列
- 告警分类：Provider 降级、阶段失败、QA 阻断、PR 发布失败等
- 全链路审计：每个阶段都有 prompt/result/log 产物和结构化 trace

### 🔄 失败恢复与运营闭环

- 阶段级重试：自动创建新 attempt，保留完整失败历史
- 租约派发：持久化 Job + 租约领取 + 恢复调度，任务不丢失
- 人工接管：`FAILED_NEEDS_HUMAN` 状态支持运营干预后继续
- 经验自动沉淀：成功交付自动提取可复用经验

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      bootstrap (API/适配层)                   │
│  REST API · 管理前端 · Feishu/RocketMQ/GitHub 适配器 · 持久化  │
├─────────────────────────────────────────────────────────────┤
│                      engine (编排控制面)                      │
│  多角色工作流 · 状态机 · 策略门控 · 经验复用 · PR 发布          │
├────────────────────┬────────────────────┬───────────────────┤
│   rag (知识上下文)   │   exec (执行面)     │   skill (技能层)   │
│ 检索·切块·意图树     │ Docker沙箱·Provider │ 可复用能力切片     │
│ 上下文包·Trace      │ 健康治理·熔断       │ 策略判定·安装      │
└────────────────────┴────────────────────┴───────────────────┘
```

**依赖方向**：`bootstrap → engine → rag`，`bootstrap → exec → rag`，`bootstrap → skill → rag`

---

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| PostgreSQL | 14+ |
| Redis | 6+（分布式锁） |
| Docker | 20+（执行沙箱） |
| Node.js | 18+（前端开发，可选） |

### 本地启动

```bash
# 1. 初始化数据库
docker exec -i postgres psql -U postgres -d rdbot < bootstrap/src/main/resources/sql/postgres/init.sql

# 2. 构建项目
./mvnw install -DskipTests

# 3. 启动后端（默认端口 18080）
./mvnw -pl bootstrap spring-boot:run

# 4. 访问管理前端
open http://127.0.0.1:18080
```

### 前端开发（可选）

```bash
cd frontend
npm install
npm run dev   # Vite 开发服务器，自动代理 /admin/* 到后端
```

### 环境变量配置

所有敏感配置通过环境变量注入，**零硬编码**：

```bash
# 数据库
export POSTGRES_URL="jdbc:postgresql://127.0.0.1:5432/rdbot"
export POSTGRES_USERNAME="postgres"
export POSTGRES_PASSWORD="your-password"

# AI Provider（支持任意 Anthropic/OpenAI 兼容接口）
export LONGCAT_API_KEY="your-api-key"
export MINIMAX_API_KEY="your-api-key"

# GitHub（PR 交付）
export GITHUB_PAT="your-github-token"

# 飞书（工单接入，可选）
export FEISHU_APP_ID="your-app-id"
export FEISHU_APP_SECRET="your-app-secret"
```

---

## 📦 模块说明

| 模块 | 职责 | 关键能力 |
|------|------|----------|
| `rag` | 知识与上下文层 | 文档解析/切块/索引、意图树分类、多通道检索、查询重写、上下文打包、Trace |
| `engine` | 编排控制层 | 多角色工作流、状态机流转、策略门控、经验沉淀、PR 发布、告警 |
| `exec` | 执行层 | Docker 沙箱执行、多 Provider 适配（LongCat/MiniMax/自定义）、熔断降级 |
| `skill` | 技能层 | 可复用能力注册、策略判定、角色/作用域权限控制 |
| `bootstrap` | 启动与适配层 | Spring Boot 入口、REST API、外部系统适配器、PostgreSQL 持久化 |
| `frontend` | 管理前端 | React + Vite + TailwindCSS，任务工作台、知识库管理、评估控制台 |

---

## 🔌 Provider 可替换设计

RD-Bot 不绑定任何特定 AI 模型。Provider 通过配置热插拔：

```yaml
rd:
  executor:
    docker:
      providers:
        - name: long-cat
          protocol: anthropic-compatible
          base-url: https://api.longcat.chat/anthropic
          model: LongCat-2.0
        - name: minimax
          protocol: openai-chat-completions
          base-url: https://api.minimaxi.com/v1
          model: MiniMax-M3
        # 添加任意 OpenAI/Anthropic 兼容 Provider
        - name: your-provider
          protocol: openai-chat-completions
          base-url: https://your-api.com/v1
          model: your-model
```

支持自动 fallback：首选 Provider 失败时自动切换备选。

---

## 📡 核心 API

### 任务管理

```bash
# 创建研发任务
curl -X POST http://127.0.0.1:18080/admin/rd-tasks \
  -H 'Content-Type: application/json' \
  -d '{"title": "修复支付回调状态未更新", "description": "..."}'

# 查询任务状态
curl http://127.0.0.1:18080/admin/rd-tasks/{taskId}

# 查询阶段执行详情
curl http://127.0.0.1:18080/admin/rd-tasks/{taskId}/stages
```

### 知识库管理

```bash
# 创建知识库
curl -X POST http://127.0.0.1:18080/knowledge-base \
  -H 'Content-Type: application/json' \
  -d '{"name": "支付系统", "description": "支付 API 文档"}'

# 写入文档（自动解析、切块、索引）
curl -X POST http://127.0.0.1:18080/knowledge-base/{kbId}/docs/write \
  -H 'Content-Type: application/json' \
  -d '{"sourceName": "api.md", "content": "# 支付 API\n...", "knowledgeType": "api"}'

# RAG 检索
curl -X POST http://127.0.0.1:18080/rag/bugfix/messages \
  -H 'Content-Type: application/json' \
  -d '{"ticketId": "FI-001", "title": "支付回调异常", "description": "..."}'
```

### 管理前端

| 路径 | 功能 |
|------|------|
| `/admin/knowledge` | 知识库列表、文档管理、Chunk 管理 |
| `/admin/tasks` | 任务列表、状态追踪、阶段详情 |
| `/admin/evaluation` | 评估数据集、质量评分、对比分析 |

---

## 🧪 测试

```bash
# 运行全部单元测试
./mvnw test

# 运行特定模块测试
./mvnw -pl engine test
./mvnw -pl exec test

# 集成冒烟测试（需要 PostgreSQL）
./mvnw -pl bootstrap -Dtest=PostgresRdTaskStateAtomicRealSmokeTest test
```

---

## 🗺️ 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Java 21（Virtual Threads） |
| 框架 | Spring Boot 3.5 |
| 持久化 | PostgreSQL + Redis（Redisson 分布式锁） |
| 对象存储 | S3 兼容（RustFS/MinIO） |
| 消息队列 | RocketMQ |
| 执行沙箱 | Docker |
| 前端 | React 18 + Vite + TailwindCSS + Radix UI |
| 构建 | Maven（多模块） |

---

## 📐 设计原则

1. **模型可替换**：AI Provider 是 worker，不是系统核心。任何兼容接口都可接入。
2. **PR-based 交付**：Agent 永远不直接合并代码，所有变更必须经过人类 Review。
3. **证据驱动**：每个阶段产出结构化产物（prompt/result/log/diff），支持完整回放。
4. **端口-适配器架构**：Feishu、GitHub、RocketMQ、模型 SDK 全部通过端口接入，可 Mock 测试。
5. **状态机 + 持久化事件**：跨实例共享状态不依赖 JVM 内存，支持故障恢复。
6. **安全纵深**：Secret 扫描、仓库白名单、分支限制、Docker 网络隔离。

---

## 📄 License

[Apache License 2.0](LICENSE)
