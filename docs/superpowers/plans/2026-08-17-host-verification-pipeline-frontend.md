# Host Verification Pipeline — Frontend Handoff

> 给**另一个前端 agent**的独立实施说明。不要改交付编排、不要跑 Maven 业务测试除非你动了 Vite proxy 合同。后端 API 以本文契约为准；后端尚未合并时先写类型、空态和合同测试，再对接真数据。

**Goal:** 在管理台让人看见「Coding 已完成 → 宿主 BUILD → 宿主 STATIC → 才进入 QA」，并能配置构建/静态命令、下载验证日志。

**Do not:**

- 不要把验证画成第五个角色（`CODING_AGENT` / `QA_AGENT` 中间不要插入假 `AgentRole`）。
- 不要把验证日志塞进现有 `QaEvidenceCard` 当 QA 截图。
- 不要把环境失败画成「将自动打回 Coding」。
- 不要手改 `bootstrap/src/main/resources/static/admin/**`；走 Vite 构建产物。
- 不要把 `buildCommands` 空数组在用户没碰过旧配置时 PUT 上去（`[]` 表示显式跳过该步，`省略字段` 才是未声明）。

**Backend change:** `openspec/changes/host-verification-pipeline/`  
**Backend implementation plan:** `docs/superpowers/plans/2026-08-17-host-verification-pipeline.md`（你不需要实施它）

---

## 1. 用户能看到什么

```
任务详情
  角色工作台（现有四角色）
  宿主验证（新卡片，独立于 QA 证据）
    轮次 1  SUCCEEDED / FAILED / 进行中
    BUILD   命令、退出码、耗时、日志下载
    STATIC  同上；BUILD 失败时显示「未执行」
    廉价返工 0/2
  QA 验证证据（现有卡片，不要混入编译日志）

项目列表 → 浏览器 QA 对话框
  现有 mode / baseUrl / startCommand / …
  新增：构建命令、静态检查命令（多行文本）
```

---

## 2. HTTP 契约（后端会提供）

基路径已由 `/admin/rd-tasks` 代理到 Spring Boot。新增子路径必须继续走后端，不能被 SPA `bypass` 吃掉。

### 2.1 列出某任务的验证轮次

`GET /admin/rd-tasks/{taskId}/host-verifications`

```ts
export type HostVerificationStatus =
  | "CREATED"
  | "PREPARING"
  | "BUILDING"
  | "STATIC_CHECKING"
  | "SUCCEEDED"
  | "FAILED_RETRYABLE"
  | "FAILED_NEEDS_HUMAN"
  | "SKIPPED_DOCS_ONLY"
  | "CANCELLED";

export type HostVerificationStepName = "BUILD" | "STATIC";

export type HostVerificationStepStatus =
  | "PENDING"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED";

export type HostVerificationFailureCategory =
  | ""
  | "NONE"
  | "PRODUCT_DEFECT"
  | "ENVIRONMENT"
  | "AUTHENTICATION"
  | "QA_INFRASTRUCTURE"
  | "REQUIREMENT_AMBIGUITY"
  | "FLAKY";

export interface HostVerificationArtifact {
  artifactId: string;
  type: string; // VERIFY_BUILD_LOG | VERIFY_STATIC_LOG | …
  name: string;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  previewable: boolean;
  contentUrl: string; // /admin/rd-tasks/{taskId}/host-verifications/{runId}/evidence/{artifactId}/content
}

export interface HostVerificationStep {
  step: HostVerificationStepName;
  status: HostVerificationStepStatus;
  commands: string[];
  exitCode: number | null;
  durationMillis: number;
  logArtifactId: string;
  errorMessage: string;
}

export interface HostVerificationRun {
  runId: string;
  codingStageRunId: string;
  parentRunId: string;
  attemptNo: number;
  status: HostVerificationStatus;
  docsOnly: boolean;
  failureCategory: HostVerificationFailureCategory;
  errorMessage: string;
  remediationCount: number; // 这一轮开始前已经用掉的廉价返工次数
  createdAtEpochMillis: number;
  startedAtEpochMillis: number;
  finishedAtEpochMillis: number;
  steps: HostVerificationStep[];
  artifacts: HostVerificationArtifact[];
}

export interface HostVerificationList {
  taskId: string;
  runs: HostVerificationRun[];
}
```

`GET /admin/rd-tasks/{taskId}/host-verifications/{runId}` 返回单个 `HostVerificationRun`。  
`GET .../evidence/{artifactId}/content` 返回日志原文（`text/plain`）。任务 id 与证据不匹配时 **404**。

空任务：`{ "taskId": "...", "runs": [] }`，HTTP 200。不要当错误。

### 2.2 QA profile 新字段

现有：

- `GET/PUT /admin/projects/{projectId}/qa-profile`
- `GET/PUT /admin/rd-tasks/{taskId}/qa-profile`

在 `ProjectQaProfile` / payload 上增加：

```ts
buildCommands?: string[] | null;
staticCommands?: string[] | null;
```

语义（必须写进 UI 帮助文案）：

| 值 | 含义 |
|---|---|
| 字段省略 / `null` | 本层未声明，继续用下层或自动探测 |
| `[]` | 显式跳过该步 |
| 非空数组 | 本层命令，覆盖下层 |

加载旧 profile 时若字段为 `null`/缺省，文本框显示空，保存时**不要**把空框序列化成 `[]`，应省略字段或显式发 `null`。只有用户勾选「跳过构建」时才发 `[]`。

`startCommand` / `regressionCommands` 语义不变：前者是 QA 启动应用，后者是 QA Agent 业务回归，**不是**宿主 BUILD/STATIC。

### 2.3 不要用错的现有接口

| 接口 | 用途 | 验证门 |
|---|---|---|
| `GET .../qa-evidence` | Playwright 截图/trace | 不要混用 |
| `GET .../execution-overview` | 四角色 stageRuns | 可以用来对齐 `codingStageRunId`，但验证状态以 host-verifications 为准 |
| `GET .../stage-runs/{id}/execution-trace` | Agent 事件流 | 验证命令不在这里 |

---

## 3. 要改的前端文件

- `frontend/src/services/rdTaskService.ts` — 类型 + `getRdTaskHostVerifications` / `getRdTaskHostVerification`
- `frontend/src/services/projectService.ts` — profile 类型与 PUT
- `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` — 新卡片；audit 视图与工作台都要能看到最新一轮
- `frontend/src/pages/admin/rdtask/roleWorkbenchModel.ts` — 不要把 VERIFY 编进 `AgentRole` 列表；最多按 `codingStageRunId` 挂一条旁路状态
- `frontend/src/pages/admin/project/ProjectListPage.tsx` — `ProjectQaProfileDialog` 增加构建/静态命令
- 若任务详情也有 QA profile 编辑，同样改
- `frontend/test/viteProxy.test.ts` — 增加：
  - `/admin/rd-tasks/7480495920010891264/host-verifications`
  - `/admin/rd-tasks/7480495920010891264/host-verifications/2/evidence/9/content`
  - 断言 HTML navigation 的 `bypass` 为 `undefined`
- `frontend/test/qaEvidencePresentation.test.ts` 或新建 `frontend/test/hostVerificationPresentation.test.ts` — 断言 service 路径与页面引用
- `frontend/src/styles.css` — 仅当现有卡片样式不够用

Vite：`/admin/rd-tasks` 已代理到后端。新 API 是该前缀的子路径，**不要**新建 proxy key，除非测试证明 SPA 吞了请求。

---

## 4. UI 规则

### 4.1 宿主验证卡片

标题建议：「宿主验证（编译 / 静态检查）」。副标题：「Coding 通过后、浏览器 QA 之前由宿主重跑。不是第五个 Agent。」

每轮展示：

- 状态徽章：`SUCCEEDED` 绿、`SKIPPED_DOCS_ONLY` 蓝（文案：文档变更，已跳过构建与静态检查）、进行中旋转、`FAILED_*` 红
- `attemptNo`、关联 `codingStageRunId`（可链到该 Coding attempt）
- 廉价返工：`已用 {n} / 2`（用**最新一轮**的 `remediationCount`，若后端后续在 list 根上提供 `cheapRemediationsUsed` 则优先用根字段）
- BUILD、STATIC 两行：命令列表、exit code、耗时、失败原因、日志按钮
- STATIC 在 BUILD 失败时：灰色「未执行」+ `errorMessage`

失败分类文案：

| category | 用户文案 | 下一步 |
|---|---|---|
| `PRODUCT_DEFECT` | 候选代码未通过构建或静态检查 | 自动打回 Coding（未达 2 次且 Coding attempt < 3） |
| `ENVIRONMENT` / `QA_INFRASTRUCTURE` | 环境或验证基础设施失败 | 需人工，不会自动打回 |
| `REQUIREMENT_AMBIGUITY` | 无法安全探测构建命令 | 需人工或补项目配置 |
| `FLAKY` | 不稳定失败 | 需人工 |
| docsOnly | 已跳过 | 进入无需浏览器的 QA |

### 4.2 四角色工作台

保持 `REQUIREMENT_REVIEWER → SOLUTION_ARCHITECT → CODING_AGENT → QA_AGENT`。  
QA 角色若仍是 PENDING，而最新验证是 FAILED，显示「等待宿主验证通过」而不是「QA 还没开始干活」。

### 4.3 项目 QA 对话框

在「回归命令」**上方**加两块：

1. 构建命令（多行）  
   说明：安装、编译、仓库测试。留空=自动探测。勾选「跳过构建」才保存为空列表。
2. 静态检查命令（多行）  
   说明：typecheck / lint。留空=自动探测。

禁止在构建框示例里写 `npm run dev`。

### 4.4 响应式

桌面：BUILD/STATIC 两列或上下堆叠都可。  
移动：单列，日志按钮可点，不要横向溢出。  
这是任务详情改动，桌面和移动都要看一眼。

---

## 5. 前端任务清单

- [ ] 5.1 在 `viteProxy.test.ts` 钉住两条 host-verifications 路径必须到达 Spring
- [ ] 5.2 增加 `rdTaskService` 类型与 GET 方法；合同测试断言路径字符串
- [ ] 5.3 扩展 `ProjectQaProfile`；保存时区分「省略」与「显式 []」
- [ ] 5.4 任务详情增加 `HostVerificationCard`：空态、进行中、成功、docs-only、BUILD 失败 STATIC 未执行、环境失败
- [ ] 5.5 工作台：QA PENDING + 验证失败时的说明，不新增角色
- [ ] 5.6 项目 QA 对话框：构建/静态命令 + 跳过开关 + 帮助文案
- [ ] 5.7 `cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build`
- [ ] 5.8 浏览器验收：任务详情（有验证数据或空态）、项目 QA 对话框；桌面 + 窄屏。不要把截图当断言。

---

## 6. 验证命令（前端 agent）

```bash
cd frontend
node --experimental-strip-types --test test/viteProxy.test.ts test/qaEvidencePresentation.test.ts
# 若新增 test/hostVerificationPresentation.test.ts 一并跑
npm run typecheck
npm run build
```

后端未就绪时：合同测试仍应通过（它们读的是源码字符串）。页面用 mock 或空 `runs: []` 先做空态。

---

## 7. 后端未就绪时的挡板

若 `GET host-verifications` 404：卡片显示「此环境尚未启用宿主验证」，不要把整个任务详情打成错误页。  
若 profile PUT 因未知字段 400：暂时不要发新字段，并在对话框注明后端版本不支持；后端合并后去掉挡板。
