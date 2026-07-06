# RD 项目管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增“项目管理”，让管理台先配置项目仓库，创建“做需求”和“修 Bug”任务时选择项目并自动带出仓库信息。

**Architecture:** 项目是独立 PostgreSQL 资源，领域能力放在 `rag` 的 `RdProjectService` 和 `RdProjectStore` 端口，`bootstrap` 只提供 PostgreSQL 适配与 REST 控制器。任务创建保留旧 payload 兼容，但管理台新建任务必须选择项目；选择项目后后端把 `projectId/projectKey/projectName/repositoryUrl/repoOwner/repoName/baseBranch` 写入 `rd_tasks`，不新增任何项目 in-memory 存储实现。

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus, PostgreSQL, React/Vite/TypeScript, JUnit 5, MockMvc, Docker PostgreSQL SQL execution.

---

## 调研结论

- 管理台任务入口在 `frontend/src/pages/admin/rdtask/RdTaskListPage.tsx`；“做需求”当前手填 `repositoryUrl`，“修 Bug”当前只传工单字段。
- 后端任务 API 在 `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskController.java`；需求创建走 `/admin/rd-tasks/requirements`，Bug 创建走 `/admin/rd-tasks`。
- 任务领域入口在 `rag/src/main/java/com/wish/rd/rag/runtime/RagStreamTaskRegistry.java`，任务持久化由 `RdTaskStore` 端口承接，PostgreSQL 适配在 `bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRdTaskStore.java`。
- 当前 SQL 真值在 `bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql` 和 `p1_multi_agent_orchestration.sql`；本次表设计应追加到 P0 文件，便于本地一键建表。
- 管理后台路由由 `frontend/src/App.tsx` 和 `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/AdminFrontendController.java` 维护，需要新增 `/admin/projects`。

## 技术方案

### 后端

- 新增 `rag/src/main/java/com/wish/rd/rag/project/RdProject.java`：不可变项目快照，字段为 `projectId/projectKey/name/description/repositoryUrl/repoOwner/repoName/defaultBranch/enabled/deleted/createTimeEpochMillis/updateTimeEpochMillis`。
- 新增 `RdProjectCommand`、`RdProjectPage`、`RdProjectQuery`、`RdProjectStore`、`RdProjectService`。
- `RdProjectService` 负责校验、仓库 URL 解析、唯一 key 检查、分页、逻辑删除；禁止 fallback 到 in-memory。
- 新增 `PostgresRdProjectStore`、`RdProjectRow`、`RdProjectMapper`，只在 `rd.knowledge.store=postgres` 下装配。
- 新增 `RdProjectController`，提供：
  - `GET /admin/projects`
  - `GET /admin/projects/{projectId}`
  - `POST /admin/projects`
  - `PUT /admin/projects/{projectId}`
  - `DELETE /admin/projects/{projectId}`
- 扩展 `RdTaskController` 请求体支持 `projectId`。如果传入 `projectId`，通过 `RdProjectService` 查项目并写入任务仓库字段；如果未传，旧接口继续按原逻辑兼容。
- 扩展 `RdBugFixTask`、`RdRequirementTask`、`CreateRequirementTaskCommand`、`RdTaskView` 和 PostgreSQL `rd_tasks` 映射，保存项目快照字段。

### 数据库

- 新增表 `rd_projects`，唯一约束为未删除项目的 `project_key`。
- `rd_tasks` 新增 `project_id/project_key/project_name` 三列及索引。
- 所有 DDL 使用 `IF NOT EXISTS` 或条件 `ALTER TABLE`，可重复执行。

### 前端

- 新增 `frontend/src/services/projectService.ts`。
- 新增 `frontend/src/pages/admin/project/ProjectListPage.tsx` 管理项目 CRUD。
- 新增导航“项目管理”和 `/admin/projects` 路由。
- `RdTaskListPage` 新建任务对话框加载启用项目列表：
  - “做需求”：选择项目后不再显示仓库地址，基准分支默认项目默认分支。
  - “修 Bug”：新增项目选择。
  - 无启用项目时禁用保存并提示先配置项目。

## 测试与验收方案

### 自动化测试

1. `./mvnw -q -pl rag -Dtest=RdProjectServiceTest test`
   - 覆盖创建项目、重复 key 拒绝、GitHub URL 解析、逻辑删除后列表不可见。
2. `./mvnw -q -pl bootstrap -Dtest=RdProjectControllerTest,RdTaskControllerTest,RdProjectPostgresSchemaPolicyTest test`
   - 覆盖项目 CRUD API、任务按项目创建、未知项目拒绝、SQL 包含项目表和任务项目列。
3. `./mvnw -q -pl rag,bootstrap test`
   - 覆盖受影响模块回归。
4. `npm run build`（工作目录 `frontend`）
   - 覆盖管理台 TypeScript 和生产构建。

### 数据库验收

使用 Docker PostgreSQL 执行：

```bash
docker exec postgres psql -U postgres -d ragent -f /tmp/rd-bot-p0.sql
docker exec postgres psql -U postgres -d ragent -Atc "\\dt rd_projects"
docker exec postgres psql -U postgres -d ragent -Atc "select column_name from information_schema.columns where table_name='rd_tasks' and column_name in ('project_id','project_key','project_name') order by column_name;"
```

若本机容器名不是 `postgres`，先用 `docker ps --format '{{.Names}}'` 确认实际容器名。

### 真实 HTTP 验收

在服务运行后执行：

1. `POST /admin/projects` 创建项目，断言返回 `projectId/projectKey/repositoryUrl/defaultBranch`。
2. `GET /admin/projects?enabled=true` 反查项目，断言列表包含新项目。
3. `POST /admin/rd-tasks/requirements` 仅传 `projectId`、不传 `repositoryUrl`，断言任务返回项目字段与仓库字段。
4. `POST /admin/rd-tasks` 传 `projectId` 创建 Bug 任务，断言任务返回项目字段。
5. `GET /admin/rd-tasks/{taskId}` 反查，断言项目快照仍在数据库任务记录中。

### RULE.md 落地

在 `RULE.md` 增加项目管理约束：项目配置属于生产共享状态，禁止新增 `InMemoryRdProjectStore` 或本地集合兜底；必须经 `RdProjectStore` 端口落 PostgreSQL，并由数据库唯一约束保证跨实例一致性。
