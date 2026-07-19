# Role Prompt Evidence View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在任务详情中以角色和 Attempt 为维度显示真实 Prompt、RAG 上下文与证据，而不是将任务级基线 Prompt 误当为角色 Prompt。

**Architecture:** 新增独立只读读模型接口，由 `AgentStageRunStore`、`AgentStageArtifactStore` 与 `RoleContextPackageStore` 聚合真实产物。前端按需拉取该读模型，使用现有 Markdown 渲染器渲染 Prompt，并保持任务轮询负载不变。

**Tech Stack:** Spring Boot、Java records、PostgreSQL Store、React、TypeScript、Tailwind、React Markdown。

---

### Task 1: 后端读模型与控制器测试

**Files:**
- Create: `bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskRolePromptController.java`
- Create: `bootstrap/src/test/java/com/wish/rd/bootstrap/controller/admin/rdtask/RdTaskRolePromptControllerTest.java`

- [x] **Step 1: 写入失败测试**

新增一个 MockMvc 测试，构造需求任务、`REQUIREMENT_REVIEWER` Prompt 产物、上下文包和一条证据；断言 `GET /admin/rd-tasks/{taskId}/role-prompts` 返回角色、Attempt、Prompt hash、`retrievalRunId` 和证据。另断言 `PENDING` 的 `CODING_AGENT` 返回 `prompt.available=false`。

- [x] **Step 2: 运行失败测试**

运行：

```bash
./mvnw -q -pl bootstrap -Dtest=RdTaskRolePromptControllerTest test
```

预期：接口不存在或响应字段缺失，测试失败。

- [x] **Step 3: 实现最小只读控制器**

控制器从阶段运行记录的 `promptArtifactId` 精确解析 `PROMPT_SNAPSHOT`，按角色和 Attempt 排序，并从阶段绑定的 `contextPackageId` 获取上下文和证据。不得调用执行、检索、重试或写入方法。

- [x] **Step 4: 验证绿色**

再次运行同一测试，预期通过。

### Task 2: 前端角色选择与 Markdown 视图

**Files:**
- Modify: `frontend/src/services/rdTaskService.ts`
- Modify: `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- Create: `frontend/test/rdTaskRolePromptPresentation.test.ts`

- [x] **Step 1: 写入失败静态测试**

断言任务详情调用 `getRdTaskRolePrompts(taskId)`、使用 `MarkdownRenderer`，以角色与 Attempt 选择实际 Prompt；断言不把 `task.promptSnapshot` 用于角色 Prompt 面板。

- [x] **Step 2: 运行失败测试**

运行：

```bash
node --test frontend/test/rdTaskRolePromptPresentation.test.ts
```

预期：因接口、组件或选择器未实现而失败。

- [x] **Step 3: 实现类型、请求和页面**

新增前端响应类型和 API 方法；页面按需加载角色 Prompt 读模型。用角色 `select` 与 Attempt `select` 选择阶段，渲染安全 Markdown、Prompt 元数据、上下文预算、检索 Run 和证据列表。保留任务基线 Prompt，但改名并给出边界说明。

- [x] **Step 4: 验证绿色**

再次运行静态测试，预期通过。

### Task 3: 回归、真实链路与静态资源

**Files:**
- Modify: `bootstrap/src/main/resources/static/admin/*`（由前端构建生成）
- Modify: `docs/superpowers/specs/2026-07-14-role-prompt-evidence-view-spec.md`

- [x] **Step 1: 构建与回归**

```bash
./mvnw -q -pl bootstrap -Dtest=RdTaskRolePromptControllerTest,RdTaskExecutionOverviewControllerTest test
npm --prefix frontend run typecheck
npm --prefix frontend run build
node --test frontend/test/rdTaskRolePromptPresentation.test.ts
```

- [x] **Step 2: 真实 PostgreSQL/HTTP 验证**

在隔离端口启动 Bootstrap，用现有任务 `7482638768785199104` 请求新接口，断言需求评审 Prompt 与 `7482662634806972416` 一致，`CODING_AGENT` 明确没有实际 Prompt。记录 HTTP 响应与数据库反查。

- [ ] **Step 3: 浏览器验收与关闭测试服务**

在隔离开发服务加载任务详情，验证 Markdown 标题、列表、角色/Attempt 选择、证据显示和 PENDING 空状态。完成后关闭仅由本次验收启动的服务。

当前已验证 Vite 将该页面导航回退为 HTML，并把角色 Prompt API 正确代理到隔离后端；浏览器自动化桥接在本机初始化阶段失败，尚未获取视觉截图。本次启动的隔离前后端服务已关闭。
