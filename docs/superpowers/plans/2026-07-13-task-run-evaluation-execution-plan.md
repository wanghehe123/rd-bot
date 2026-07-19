# RD-Bot 单次任务执行评测执行文档

日期：2026-07-13
执行方式：TDD、小步闭环、真实 PostgreSQL/HTTP/浏览器验收

## 1. 执行顺序

### Step 1：评分契约测试

先在 `scripts/evaluation/test_rd_eval_lib.py` 写失败测试，覆盖：

- 四角色完整成功时执行指标通过。
- 缺少 RetrievalRun 时仅检索覆盖指标失败并进入 failures。
- 任务末状态与 timeline 末节点不一致时失败。
- record 中出现 secret 模式时 `secret_leak_rate` 失败。

再在 `rd_eval_lib.py` 增加阈值与 `score_task_run`，运行 Python 单测至绿。

### Step 2：领域配置测试

先扩展 `EvaluationRunConfig`/`EvaluationRunEngineTest`：

- `TASK_RUN + taskId` 合法。
- `TASK_RUN` 缺 taskId、taskId 非数字非法。
- 旧 `FIXTURE/RAG_HTTP` 行为不变。
- 重试保留 taskId 并递增 attempt。

实现 `EvaluationSource.TASK_RUN` 和配置字段。

### Step 3：任务快照测试

新增 `TaskRunEvaluationSnapshotCollectorTest`，使用内存 Store 构造一个四角色任务：

- 断言只读取指定 taskId。
- 断言 latest attempt 投影正确且历史 attempt 保留。
- 断言上下文、RetrievalRun、产物、timeline、experience 被结构化写入。
- 断言 artifact URI 不被跟随读取，preview 被长度限制和脱敏。
- 断言数据集与记录递归脱敏、悬空结果产物不计覆盖、测试证据必须具备角色/hash/URI。
- 断言集合截断元数据存在且最终快照不超过 500K。

在 `engine` 实现领域 collector，在 `bootstrap` 实现生成 JSONL 的 writer，避免基础设施层承担业务投影规则。

### Step 4：执行器与 API 测试

扩展 `LocalPythonEvaluationExecutorTest`：

- `TASK_RUN` 跳过 `rd_eval_run.py`，直接生成 dataset/record。
- score 命令使用 run 私有数据集。
- catalog 返回 `TASK_RUN`。

扩展 `EvaluationControllerTest`：

- 创建任务执行评测返回 202。
- 未知任务 404、非法 taskId 400、无执行证据 409。

实现 `POST /admin/rd-tasks/{taskId}/evaluations`。

### Step 5：前端测试与实现

先扩展 `frontend/test/evaluationConsole.test.ts`，断言：

- service 类型和 API 含 `TASK_RUN/taskId`。
- 执行追踪页有“评测本次执行”并调用任务评测 API。
- 评测页读取 `runId` 查询参数。
- `TASK_RUN` 模式展示任务 ID、隐藏静态数据集配置。

实现 service、页面交互、图标、loading 和响应式样式。因 API 路径已由 `/admin/rd-tasks` 和 `/admin/evaluations` 代理，不新增代理前缀；仍运行 Vite proxy 测试防止 SPA 200 假响应。

### Step 6：回归构建

按以下顺序执行：

```bash
python3 -m unittest scripts.evaluation.tests.test_rd_eval_lib
./mvnw -q -pl engine test
./mvnw -q -pl bootstrap -am test
npm --prefix frontend run typecheck
node --test frontend/test/*.test.ts
npm --prefix frontend run build
```

记录既有失败，不隐藏、不回滚。

### Step 7：真实联调

1. 执行 `./mvnw -q -pl engine install -DskipTests`，避免 stale engine jar。
2. 使用独立测试端口启动 bootstrap，不占用用户的 `18080`。
3. 使用真实 PostgreSQL 和目标任务创建 `TASK_RUN` 评测。
4. 轮询至终态，保存 HTTP、数据库、records、scores、failures、report。
5. 使用独立前端端口或静态 bundle 做桌面/移动端浏览器验收。
6. 执行 secret scan。
7. 关闭本次启动服务并确认端口释放。
8. 将实际结果写入 `docs/qa/2026-07-13-task-run-evaluation-acceptance-report.md`。

## 2. 回滚边界

不修改目标外卖仓库、不重新执行任务、不改变原任务/阶段/RetrievalRun 状态。若评测失败，只产生新的 EvaluationRun 和本地评测产物；取消/重试遵循现有评测状态机。

## 3. 交付清单

- 计划、验收、执行三份实施前文档。
- 后端 `TASK_RUN` 来源、快照、API、评分。
- 执行追踪页入口和评测控制台支持。
- 单元/模块/前端构建证据。
- 同一 taskId/runId 的真实 HTTP + PostgreSQL + 浏览器证据。
- 服务关闭和端口释放证据。
