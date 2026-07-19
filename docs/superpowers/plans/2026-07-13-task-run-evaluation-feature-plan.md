# RD-Bot 单次任务执行评测改造计划

日期：2026-07-13
状态：实施前已确认
目标任务样本：`7480495920010891264`（开发管理后台商品管理功能）

## 1. 背景与问题

当前评测控制台只支持 `FIXTURE` 与 `RAG_HTTP` 两种录制来源。执行追踪页虽然已经能够展示任务状态、四角色阶段、Provider 尝试、上下文 ID、交付材料和 PR，但操作者无法直接回答以下问题：

- 这一次真实任务是否完整走完任务状态机与四角色流水线？
- 每个角色是否都有上下文、Provider 尝试和结果产物？
- QA 是否留下真实测试证据，交付是否留下 PR 和五类经验？
- 本次执行是否真正使用了 Deep RAG RetrievalRun，还是只有旧式上下文？
- 哪些门禁通过、哪些能力缺失，证据文件在哪里？

本次改造新增第三种评测来源 `TASK_RUN`。它不重新执行目标仓库，也不调用外部 Provider，而是从 RD-Bot 的持久化事实源生成一次不可变、脱敏的任务执行快照，再复用现有 `record -> score -> report -> diff` 评测状态机。

## 2. 用户流程

1. 操作者进入 `/admin/traces/{taskId}`。
2. 点击顶栏“评测本次执行”。
3. 前端调用 `POST /admin/rd-tasks/{taskId}/evaluations`。
4. 后端校验任务存在且已经产生可评测执行证据，创建 `source=TASK_RUN` 的 EvaluationRun。
5. 后台从任务、状态事件、阶段、上下文、阶段产物、RetrievalRun、经验等真实 Store 生成单样本数据集与单条评测记录。
6. 现有 Python scorer 计算执行完整性指标，生成 `_scores.json`、`failures.jsonl`、`report.md` 和 `per_sample.csv`。
7. 前端跳转 `/admin/evaluations?runId={runId}`，实时展示阶段、日志、指标和失败原因。

## 3. 架构方案

### 3.1 EvaluationRun 配置

`EvaluationSource` 新增 `TASK_RUN`，`EvaluationRunConfig` 新增 `taskId`。

- `FIXTURE/RAG_HTTP`：继续要求 `datasetId` 为服务端白名单 JSONL 文件。
- `TASK_RUN`：要求 `taskId` 为 1~64 位数字；禁止客户端提供文件路径；`datasetId` 固定为服务端生成标识 `task-run.generated.jsonl`。
- 重试沿用原 Run 配置并创建新 attempt，旧 Run 不原地修改。
- Diff 继续允许，但仅允许与成功 EvaluationRun 比较。

### 3.2 真实任务快照

在 `engine` 新增 `TaskRunEvaluationSnapshotCollector` 领域投影器，只从受信任的任务作用域 Store 读取；`bootstrap` 仅负责将投影写为 JSONL 并调用本地评分进程：

- `RagStreamTaskRegistry`：任务快照与主状态时间线。
- `AgentStageRunStore`：全部角色、全部 attempt。
- `AgentStageArtifactStore`：Prompt、结果、测试日志、Patch 和 Docker 元数据的受控预览/hash/URI。
- `RoleContextPackageStore`：各角色上下文与预算。
- `RetrievalRunStore`：Deep RAG Run 和脱敏 artifacts。
- `WorkflowExperienceStore`：五类经验及 `redacted` 标记。

输出由服务端写入：

- `qa-runs/evaluation/web-runs/{runId}/task-run-dataset.jsonl`
- `qa-runs/evaluation/runs/{runId}.jsonl`

生成记录必须包含 `task_id`、`task_run`、`stages`、`delivery`、`retrieved_contexts`、`final_status`。结果内容只使用数据库中已有的 `contentPreview`，数据集和记录统一执行递归脱敏、集合上限与 500K 总预算；URL user-info/敏感查询参数也必须脱敏。结果产物必须能反查实际 artifact，测试证据必须来自编码或 QA 角色且同时具备 hash 与 URI。不读取任意 `file://` 产物正文，不跟随客户端路径。

### 3.3 评分指标

新增确定性指标，全部以 `1.0` 为通过门槛，`SKIPPED` 不算通过：

| 指标 | 含义 |
| --- | --- |
| `task_terminal_success_rate` | 主任务处于 `COMMITTED/MERGED/COMPLETED` 成功终态 |
| `role_stage_coverage_rate` | 需求任务四个角色均存在阶段记录 |
| `stage_success_rate` | 每个角色最新 attempt 均为 `SUCCEEDED` |
| `context_package_coverage_rate` | 每个角色最新 attempt 都绑定上下文包 |
| `provider_attempt_coverage_rate` | 每个角色最新 attempt 都保留 Provider 尝试 |
| `result_artifact_coverage_rate` | 每个角色最新 attempt 都绑定结果产物 |
| `task_timeline_terminal_rate` | 状态时间线存在且末节点与任务当前状态一致 |
| `retrieval_run_coverage_rate` | 需求基础上下文与四角色均存在成功 RetrievalRun |
| `test_evidence_coverage_rate` | 至少存在真实测试日志或 QA 测试产物 |
| `pull_request_present_rate` | 需求交付存在 PR URL |
| `required_experience_complete_rate` | 五类经验齐全 |
| `experience_redacted_rate` | 经验均标记为已脱敏 |
| `secret_leak_rate` | 评测记录中无凭证模式 |

本次样本是 Deep RAG 改造前的历史任务，若没有 `rd_rag_retrieval_runs`，`retrieval_run_coverage_rate=0` 应如实失败；其余执行证据仍独立计分。评测 Run 的技术执行成功与门禁结果分离：Run 可为 `SUCCEEDED`，但 `overallPassed=false`。

### 3.4 管理 API

新增：

```http
POST /admin/rd-tasks/{taskId}/evaluations
Content-Type: application/json

{
  "judgeProvider": "NONE",
  "timeoutSeconds": 90,
  "baselineRunId": ""
}
```

返回 `202 Accepted` 与 EvaluationRun。

- 未知任务：`404`。
- 非数字/越界 taskId：`400`。
- 任务尚无任何阶段与时间线证据：`409`。
- 不允许网页提交 Python、脚本、输出目录或原始数据库查询。

现有 `POST /admin/evaluations/runs` 同步接受结构化 `taskId`，但只有 `source=TASK_RUN` 时使用。

### 3.5 前端

- 执行详情顶栏新增带 `FlaskConical` 图标的“评测本次执行”按钮。
- 点击后进入 loading，成功后跳到 `/admin/evaluations?runId={runId}`，失败以 toast 展示。
- 评测控制台支持 `TASK_RUN` 标签；选择该来源时显示任务 ID，隐藏静态数据集、样本上限和 RAG HTTP 输入。
- 评测控制台读取 `runId` 查询参数并自动选中目标 Run。
- 运行历史中展示任务 ID，避免单样本执行评测与数据集回归混淆。

## 4. 代码改造范围

后端：

- `engine/.../evaluation/model/EvaluationSource.java`
- `engine/.../evaluation/model/EvaluationRunConfig.java`
- `engine/.../evaluation/taskrun/TaskRunEvaluationSnapshotCollector.java`
- `bootstrap/.../evaluation/LocalPythonEvaluationExecutor.java`
- `bootstrap/.../controller/admin/evaluation/EvaluationController.java`
- 对应 engine/bootstrap 单元测试

评分：

- `scripts/evaluation/rd_eval_lib.py`
- `scripts/evaluation/test_rd_eval_lib.py`

前端：

- `frontend/src/services/evaluationService.ts`
- `frontend/src/pages/admin/evaluation/EvaluationPage.tsx`
- `frontend/src/pages/admin/evaluation/evaluationPresentation.ts`
- `frontend/src/pages/admin/trace/ExecutionTracePage.tsx`
- `frontend/src/styles.css`
- `frontend/test/evaluationConsole.test.ts`

不新增数据库表。`taskId` 随 EvaluationRun 的 `config_json` 持久化，兼容历史配置时默认为空字符串。

## 5. 风险与控制

- **历史 JSON 兼容**：`taskId` 为新增 record 字段，反序列化旧 `config_json` 时为空；旧来源验证逻辑不变。
- **产物泄密**：仅使用数据库受控 preview，二次脱敏并限制总字符；不读取产物 URI 指向的文件。
- **历史任务无 RAG**：不伪造通过，输出明确失败指标，作为 Deep RAG 接入差距。
- **任务执行被修改**：评测开始时生成不可变快照；Run 重试重新抓取并通过新 attempt 保留差异。
- **脏工作区**：只改上述文件，不回滚现有未提交改动。
- **测试服务遗留**：真实验收使用独立端口，结束后关闭并验证端口释放。

## 6. 完成定义

满足预先编写的验收文档 A01~A14；至少完成目标任务一次真实 HTTP 评测，使用 PostgreSQL 反查同一 `taskId/runId`，生成桌面与移动端截图，并将真实结果写入验收报告。任何 `SKIPPED`、拼接不同任务证据或仅靠单测的结果不算完成。
