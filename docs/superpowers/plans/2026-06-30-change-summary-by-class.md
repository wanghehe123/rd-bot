# RD-Bot 需求交付与分布式锁改动说明

日期：2026-06-30

## 提交分组

| 提交 | 类型 | 范围 |
| --- | --- | --- |
| `e8ed846b feat(runtime): add requirement task foundation` | 运行时基础 | RD 任务类型泛化、需求任务模型、任务材料模型、PostgreSQL 映射、任务状态机扩展、RAG 侧锁端口 |
| `a70149e6 feat(requirement): wire delivery workflow` | 需求交付链路 | 需求上下文、计划、策略门、执行端口、Docker 执行适配、Feishu IM 入口、后台 API、React 任务管理 UI |
| `61ef5eb2 feat(lock): add redisson distributed locking` | 分布式锁与规则 | Redisson 锁适配、默认配置、AGENTS/RULE 并发规范 |
| `docs: document classified changes` | 文档 | 本文档与研发计划文档 |

## 运行时与持久化基础

### `rag.runtime`

| 类 | 改动 |
| --- | --- |
| `CreateRequirementTaskCommand` | 新增需求任务创建命令，封装标题、优先级、来源、仓库、分支、预期结果、验收标准、材料输入和自动执行标记；构造器统一做 null 归一。 |
| `CreateRequirementTaskCommand.RequirementMaterialInput` | 新增需求材料输入值对象，支持手填文本、本地上传、Feishu 文档 URL 等材料来源。 |
| `RdRequirementTask` | 新增需求交付任务快照，包含 source、repository、branch、expectedResult、acceptanceCriteriaJson、promptSnapshot、executionResultJson、PR URL、错误信息、暂停标记等字段；提供 `created`、`withState`、`withPaused`、`deleted`。 |
| `RdTaskType` | 新增任务类型枚举，为 `BUG_FIX`、`REQUIREMENT` 以及后续智能问答/评审等任务类型预留统一类型入口。 |
| `RdTask` | 扩展通用任务接口，使列表、详情、暂停/恢复/删除可同时处理 BugFix 与 Requirement。 |
| `RdTaskStatus` | 扩展需求交付状态：`MATERIAL_COLLECTING`、`MATERIAL_READY`、`CONTEXT_BUILDING`、`CONTEXT_READY`、`PLAN_GENERATING`、`PLAN_GENERATED`、`WAITING_POLICY`、`WAITING_APPROVAL`、`VALIDATING`、`PR_CREATING`、`REPORTING`、`COMPLETED`、`FAILED_RETRYABLE`、`FAILED_NEEDS_HUMAN`、`CANCELLED`、`DEAD_LETTERED`、`RECOVERING`。 |
| `RdTaskQuery` | 增加任务类型过滤能力，并让管理台查询可覆盖 BugFix 与 Requirement。 |
| `RdTaskPage` | 泛化分页结果为 `List<RdTask>`，避免列表接口只返回 BugFix。 |
| `RdTaskStore` | 增加 `saveRequirementTask`、`findRequirementTask`、`listRequirementTasks`、`findTask`、`listTasks` 默认方法，形成多任务类型存储端口。 |
| `RagStreamTaskRegistry` | 增加 `createRequirementTask`、`getRequirementTask`、`markRequirement*` 状态推进、`queryTasks`、`pauseTask`、`resumeTask`；状态转换覆盖需求任务全链路；所有公开状态/查询入口统一通过 `DistributedLockExecutor` 保护。 |
| `TaskMaterial` | 新增任务材料快照，记录材料类型、来源类型、标题、URI、MIME、hash、内容预览、artifact URI、知识文档 ID、revision、metadata 与时间戳。 |
| `TaskMaterialType` | 新增材料类型枚举，例如需求文档、验收标准、附件等。 |
| `TaskMaterialSourceType` | 新增来源类型枚举，区分手填文本、本地上传、Feishu 文档。 |
| `TaskMaterialStore` | 新增任务材料存储端口，支持保存、按 ID 查询、按 taskId 查询、按 taskId 删除。 |
| `InMemoryTaskMaterialStore` | 新增内存材料 store，使用锁端口保护内部 `LinkedHashMap` 快照。 |
| `InMemoryRdTaskStore` | 扩展保存/查询需求任务，`listTasks` 同时返回 BugFix 与 Requirement；改为通过锁端口保护内部状态。 |
| `InMemoryRdTaskStatusEventStore` | 由 JVM 级 `synchronized` 改为锁端口保护事件快照。 |

### `rag.lock`

| 类 | 改动 |
| --- | --- |
| `DistributedLockExecutor` | 新增领域层分布式锁端口，RAG/engine 只依赖端口，不依赖 Redisson SDK。 |
| `LocalDistributedLockExecutor` | 新增单进程本地锁实现，供单测和 `RD_DISTRIBUTED_LOCK_MODE=local` 场景使用。 |

### `bootstrap.persistence`

| 类/文件 | 改动 |
| --- | --- |
| `PostgresRdTaskStore` | 支持 `RdRequirementTask` 的保存、查询、列表转换；BugFix 查询会过滤 `BUG_FIX` 类型，避免跨类型误读。 |
| `RdTaskRow` | 增加需求任务字段：source、repository、repoOwner、repoName、baseBranch、workBranch、expectedResult、acceptanceCriteriaJson。 |
| `RdTaskMapper` | `upsertTask` SQL 增加需求字段写入和冲突更新。 |
| `PostgresTaskMaterialStore` | 新增 PostgreSQL 任务材料 store，实现 `TaskMaterialStore`。 |
| `RdTaskMaterialRow` | 新增 `rd_task_materials` 表行实体。 |
| `RdTaskMaterialMapper` | 新增任务材料 MyBatis mapper。 |
| `p0_knowledge_productionization.sql` | 为 `rd_tasks` 增加需求任务字段；新增 `rd_task_materials` 表和 task/source 索引。 |

### 运行时测试

| 测试类 | 覆盖 |
| --- | --- |
| `RagStreamTaskRegistryRequirementTest` | 需求任务创建、状态推进和查询。 |
| `TaskMaterialStoreTest` | 内存材料 store 保存和按任务查询。 |
| `RagStreamTaskRegistryDistributedLockTest` | 任务注册表和任务 store 的锁名调用。 |
| `InMemoryTaskStoreDistributedLockTest` | 材料 store、状态事件 store 的锁端口接入。 |
| `RdTaskPersistencePolicyTest` | PostgreSQL 任务表与材料表字段/SQL 策略。 |

## 需求交付链路

### `engine.requirement`

| 类 | 改动 |
| --- | --- |
| `RequirementContextBuilder` | 根据 `RdRequirementTask` 和 `TaskMaterial` 构建需求上下文，汇总材料、仓库、分支、预期结果和验收标准。 |
| `RequirementContextPackage` | 新增需求上下文包值对象，并提供 JSON 快照，供任务审计和执行 prompt 使用。 |
| `RequirementPlanGenerator` | 根据需求任务和上下文生成实现计划。 |
| `RequirementPlan` | 表达目标、实现步骤、验证命令、风险提示等计划输出。 |
| `RuleBasedRequirementPolicyGate` | 新增需求策略门，基于材料、仓库、分支、验收标准等做最小治理判断。 |
| `RequirementPolicyDecision` | 表达允许执行、等待人工审批或拒绝，以及原因和 JSON 快照。 |
| `RequirementDeliveryEngine` | 串联 `MATERIAL_READY -> CONTEXT_BUILDING -> CONTEXT_READY -> PLAN_GENERATING -> PLAN_GENERATED -> WAITING_POLICY/WAITING_APPROVAL -> EXECUTING -> COMMITTED/FAILED_NEEDS_HUMAN/REJECTED`。 |
| `RequirementDeliveryResult` | 需求交付引擎返回值，携带 taskId、status、PR URL、错误信息。 |
| `RequirementExecutorPort` | 新增需求执行端口，让 engine 不依赖 Docker/Claude 具体实现。 |
| `RequirementExecutionRequest` | 需求执行请求，携带任务、上下文、计划和策略决策。 |
| `RequirementExecutionResult` | 需求执行结果，支持成功/失败工厂方法和结构化 result JSON。 |

### `bootstrap.executor`

| 类/文件 | 改动 |
| --- | --- |
| `EngineRequirementExecutorAdapter` | 将 engine 的需求执行请求适配成 exec 层 `RepairJobCommand`，复用 Docker Claude Code、GitHub PR 和执行证据协议。 |
| `EngineRequirementExecutorConfiguration` | 条件装配需求执行端口，桥接 `RepairExecutorPort`。 |
| `MockRepairExecutorConfiguration` | 新增 mock executor 配置，支持本地无需 Docker/Claude 也能跑通需求交付链路。 |
| `rd-local-repair-worker.mjs` | 增加 README-only 需求交付 smoke patch，用于验证需求执行链路能生成可审计改动。 |
| `DockerExecutorConfigurationTest` | 将 `requirement/*` 纳入 Docker 执行工作分支 allowlist 测试。 |
| `ExecutionAllowlistPolicyTest` | 增加 `requirement/*` 分支允许与非法 feature 分支拒绝断言。 |

### Feishu IM 入口

| 类 | 改动 |
| --- | --- |
| `FeishuImMessageController` | 增加需求意图解析和结构化字段抽取；可从飞书对话创建 `RdRequirementTask`、保存 `TaskMaterial`、触发 `RequirementDeliveryEngine`，并返回缺字段提示、任务状态或 PR 信息。 |
| `FeishuImMessageController.RequirementDraft` | 新增内部草稿模型，承载解析后的标题、优先级、仓库、分支、预期、验收标准、材料来源和内容。 |
| `FeishuImMessageControllerTest` | 覆盖需求消息创建、缺字段提示、Feishu 文档材料识别、自动执行结果等。 |

### 管理台 API

| 类 | 改动 |
| --- | --- |
| `RdTaskController` | 列表改用 `queryTasks`；新增需求任务创建、执行提交、材料列表、手填材料、本地上传、Feishu 材料、材料预览接口；详情视图增加需求字段和执行证据字段。 |
| `RdTaskController.CreateRequirementTaskRequest` | 新增后台创建需求任务请求。 |
| `RdTaskController.RequirementMaterialInput` | 新增后台材料输入请求。 |
| `RdTaskController.TaskMaterialView` | 新增材料列表视图。 |
| `RdTaskController.TaskMaterialPreviewView` | 新增材料预览视图。 |
| `RdTaskControllerTest` | 覆盖后台创建需求任务、材料接口、执行入口和视图字段。 |

### 前端与静态资源

| 文件/组件 | 改动 |
| --- | --- |
| `frontend/src/services/rdTaskService.ts` | 增加需求任务 payload、材料 payload、材料列表/新增/上传/预览 API、执行提交 API，以及需求任务字段类型。 |
| `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx` | 新建任务弹窗支持 BugFix/Requirement 类型；需求模式支持手填需求、本地文件、Feishu 文档 URL、仓库、分支、预期结果、验收标准和自动执行。 |
| `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` | 详情页展示需求交付信息、验收标准、执行证据、PR 链接、材料列表，并提供需求任务执行按钮。 |
| `frontend/src/components/ui/card.tsx` | 为卡片增加 `min-w-0`，避免需求详情中的长文本撑破布局。 |
| `bootstrap/src/main/resources/static/admin/admin-knowledge.js` | 同步 React/Vite 构建后的静态后台资源。 |
| `bootstrap/src/main/resources/static/admin/admin-knowledge.css` | 同步静态后台样式产物。 |

### 需求链路测试

| 测试类 | 覆盖 |
| --- | --- |
| `RequirementDeliveryEngineTest` | 需求交付主流程成功/策略拦截。 |
| `EngineRequirementExecutorAdapterTest` | 需求执行请求到 exec repair job 的适配。 |
| `EngineRequirementExecutorConfigurationTest` | 需求执行端口条件装配。 |
| `MockRepairExecutorConfigurationTest` | mock repair executor 输出协议。 |
| `LocalRepairWorkerAssetTest` | 本地 worker 的 README smoke patch 资产校验。 |

## 分布式锁与并发规范

### `bootstrap.lock`

| 类 | 改动 |
| --- | --- |
| `RedissonDistributedLockExecutor` | 新增 Redisson `RLock` 实现，执行前加锁，`finally` 中确认当前线程持有后解锁。 |
| `DistributedLockConfiguration` | 根据 `rd.distributed-lock.mode` 装配 Redisson 或本地锁；Redisson 使用 `spring.data.redis.*` 地址、端口、密码。 |
| `RedissonDistributedLockExecutorTest` | 使用 JDK 动态代理验证 lock/action/unlock 顺序和异常时解锁。 |
| `DistributedLockConfigurationTest` | 验证 `rd.distributed-lock.mode=local` 不创建 RedissonClient。 |

### 配置与规则

| 文件 | 改动 |
| --- | --- |
| `bootstrap/src/main/resources/application.yaml` | 新增 `rd.distributed-lock.mode` 默认 `redisson`；新增需求执行 mock 开关；允许 `requirement/*` 工作分支。 |
| `AGENTS.md` | 增加 `DistributedLockExecutor` 词汇和全仓库并发规则：生产共享状态不能依赖 JVM 级 `synchronized`，Redisson 只能在 bootstrap 适配层。 |
| `RULE.md` | 更新 Registry/并发规范：多实例共享状态走分布式锁、数据库原子约束或并发原语，`synchronized` 只允许单进程资源保护。 |

## 文档

| 文件 | 改动 |
| --- | --- |
| `docs/superpowers/plans/2026-06-29-requirement-delivery-bot.md` | 需求交付智能机器人研发计划，记录本地代码调研、方案比较、推荐架构、迭代阶段和验收标准。 |
| `docs/superpowers/plans/2026-06-30-change-summary-by-class.md` | 本文档，按提交和类说明本次改动。 |

## 验证记录

已执行：

```bash
git diff --check
./mvnw -pl rag,bootstrap -am -Dtest=RagStreamTaskRegistryDistributedLockTest,InMemoryTaskStoreDistributedLockTest,RedissonDistributedLockExecutorTest,DistributedLockConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw test
```

结果：

- `git diff --check` 无输出。
- 聚焦 Maven 测试通过。
- 全量 `./mvnw test` 通过：188 tests，0 failures，0 errors，7 skipped。

## 备注

- `docs/` 默认被 `.gitignore` 忽略，本文档放在已放行的 `docs/superpowers/plans/*.md` 路径下，便于进入提交。
- 本地还存在 `docs/reports/2026-06-29-redisson-concurrency-cr.md`，该路径被 `.gitignore` 忽略；其并发 CR 内容已整合到本文档的“分布式锁与并发规范”章节。
