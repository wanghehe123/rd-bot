# MEA Demo 验收记录（codex/mea-demo 组合候选）

> 记录日期：2026-09-08。范围合同：`openspec/changes/mea-demo-scope/`。
> 本文档只记录 Demo 范围结果；未执行项保持未执行，不把 SKIPPED 涂成 PASS。

> 后续两项收口及最终候选见 `2026-09-08-mea-demo-closeout-and-live-verification.md`。下文 `a93fd28b…` 是历史构建，未包含后续修复，不再作为待部署候选。

> 最终更新：D00–D05 已在精简 Demo 范围内收口。最终云端包 `d707cdaf…`，真实任务 `7502920392433078272` COMPLETED、业务 PR #40、两个 AC 完成、23 条 QA 证据；详见上述后续报告。以下保留 D00–D03 历史构建和当时待执行方案，不作为最新进度。

## 1. 候选与来源（D00）

| 项 | 值 |
| --- | --- |
| Demo 分支 | `codex/mea-demo`（工作区 `/Users/wish233/Documents/RD-Bot/.worktrees/mea-demo`） |
| 后端基点 | `9da44a5cacd4cf3eb38a67b390f65c94f064ef43`（`codex/mea-fresh-executor-episode`，含 Coding MEA / Manager 全文 / stage result 读取 API 与 B09–B11） |
| 前端来源 | `codex/mea-task-workbench` 工作区，HEAD `3fcd7db65cb7a3aabace2d04ffa5d30fdf69e714`，未提交 dirty 实现 |
| 迁入方式 | 7 个新 src 文件 + 8 个新测试文件逐文件复制；9 个 M 文件以 `git diff` patch 应用（`3fcd7db6..9da44a5c` 未触碰 frontend/static，patch 零冲突） |
| 不迁入 | `static/admin` 旧 bundle（组合候选统一重新 build）、`frontend/node_modules`、旧验收报告、`openspec/changes/mea-task-workbench/`、后端 P2 恢复实验的 8 个 tracked dirty 文件（command reopen / deadline 刷新 / 立即重调度 / Pi lifecycle 自动开新 Attempt，整体保留在来源工作区） |
| openspec | `openspec/changes/mea-demo-scope/` 已建，`openspec validate --all --strict` 19 passed / 0 failed |

提交链：`3fc6d9d7`（候选建立 + 迁入）→ `b74a0bad`（D01 前端修复）→ `a65bcaea`（D02 后端失败底线）→ D03 提交（bundle + verify 脚本）。

## 2. D01 前端接口修复（b74a0bad）

- `codingMeaService.ts`：query 统一 `codingStageRunId`；Manager 全文独立 wire type `ManagerDecisionDetail.boundedContract`；审计记录适配 `id/text/evidenceRefs`（EvidenceRef 保留 `{auditRunId, sourceKind, uri, sha256}` URI 结构）。
- `RdTaskDetailPage.tsx`：`loadCodingMea` 传 `{ codingStageRunId: stageRunId }`，generation guard / in-flight 去重保留。
- `codingMeaModel.ts`：移除 `role+attemptNo`、`roundNo===attemptNo`、`decisions[0]`、`codingStages[0]` 等全部无证据回退；decision 关联只用 links 的 EXECUTES 边 / `sourceCommandId`→remediation 身份两级解析；QA 只用 `remediation.targetQaStageRunId` 或「唯一 Coding 轮 + 唯一 QA stage」无歧义特例；关联不上显示 `当前记录暂无法关联到具体 Manager 决策`。
- `RoleDeliverablesPanel.tsx` / `roleDeliverableModel.ts`：QA 证据 URL 优先复用后端 `contentUrl`，否则生成 `/admin/rd-tasks/${taskId}/qa-evidence/${artifactId}/content`。
- 测试：`frontend` 240/240 通过（含新增：未知 stageRunId 不回落其它 Attempt、外来 stageRunId 的 command 不被借用、无证据关联显示不可用、QA URL taskId 断言）；`npm run typecheck` 通过。

## 3. D02 后端失败底线（a65bcaea）

- 真实链路核对结论：bridge 预算 stop 时以 `BRIDGE_SYNTHETIC` 来源发 `RESULT_SUBMITTED`（不置位 `resultSubmitted`）→ executor 走 missing-lifecycle 聚合分支，result.json 里的 `BUDGET_EXCEEDED` 从未被读取；`validateRoleProtocolResult` 的硬编码只影响非编码角色分支。两处聚合点均已修复。
- `PiBridgeResultPayloads.failureCategory()` 透传 bridge 声明类别；`DockerPiAgentExecutor.validateRoleProtocolResult` 不再硬编码 `PI_BRIDGE_PROTOCOL`。
- missing-lifecycle 分支先读取 bridge 已写入的 synthetic result.json（仅 exit 0 + 文件存在 + 声明为 synthetic failure），可证明时保留 `BUDGET_EXCEEDED` 原因；无 synthetic 产物时保持通用 `PI_RESULT_PROTOCOL` 聚合不变（RULE.md missing-lifecycle 语义不变）。
- Host 窄断言（engine 测试 `shouldEndBudgetExhaustedCodingAttemptAsFailedNeedsHumanWithoutAutoRepair`）：预算耗尽 → `FAILED_NEEDS_HUMAN`，不晋升 COMPLETED，Coding 恰好调用 1 次（无自动返工）。
- false-ASK：仅迁入 `RequirementReviewProtocol.disposition` 前置分支 + `isApproved` helper + 单测（`approvedWithAdvisoryMissingInformationStillProceeds`）；`NEED_INFO` 仍 `ASK_OPERATOR`，`REJECTED/UNSAFE/FAILED` 仍 fail-closed。P2 实验补丁未带入。
- 验证：`PiBridgeResultPayloadsTest` 3/3；`DockerPiAgentExecutorTest` 全类通过（含 3 个新归类测试）；bridge `npm test` 111/111；`EngineRequirementExecutionProfileResolverTest` 11/11。仅改 Java 归类，未改容器资源，无需重建 Pi 镜像。

## 4. D03 组合回归

- 后端聚焦测试（同一候选）：`RdTaskCodingMeaControllerTest` 2/2、`RdTaskStageResultControllerTest` 1/1、`RequirementDeliveryEngineTest` 45/45、`RequirementReviewProtocolTest` 4/4 — BUILD SUCCESS。
- 前端组合 build 一次：`npm run build` 产物已入 `bootstrap/src/main/resources/static/admin/`（bundle 内核对：`codingStageRunId`、taskId-scoped `qa-evidence/.../content`、`boundedContract`、`当前记录暂无法关联` 均在 `admin-RdTaskDetailPage.js`）。
- 组合候选 jar：`bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar`
  SHA-256 `a93fd28be5c8154a90e104a0824bec7b10abc3fed63a46c7de674aadb348aa1a`（2026-09-08 01:41 本地构建）。
- 读取核对脚本已建：`deploy/cloud-server/mea-live/verify_demo.py`（只读断言：列表 / shell / execution-overview / role-prompts / coding-mea 参数回显 / Manager 全文 / stage result + 两个负例）。
- 本地无 PostgreSQL/Docker，`@ConditionalOnProperty rd.knowledge.store=postgres` 的读取 Controller 与真实任务数据只在云端候选上可验 → 归入 D04 前置执行（见 §6）。

## 5. 本期明确移出（不演示、不宣称）

完整 P2 kill 矩阵（W1 主动 kill JVM / W2 强制构建失败 / W3 QA 协议隔离）、B12–B24（稳定契约表、追加材料重规划、契约反查、预算账本、无进展检测、回执恢复、记忆晋升、模型路由、消融）、12 case×3 轮与 P95 阈值、移动端专项、四边界故障注入与恢复率统计、全仓 Maven 回归。历史实验（07h/07i/07k 等）保持原结论，不因本轮降标改写。

## 6. 待执行（依赖云端部署授权）

以下为发布判定五项的剩余证据，需要获得授权后在云端执行（不接管/不终止他人实验）：

```bash
# 1) 部署同一构建物（a93fd28b…）到云端候选实例，确认版本与来源
# 2) 读取接口核对（一个已完成的真实 taskId）：
RD_BOT_BASE_URL=http://127.0.0.1:8080 python3 deploy/cloud-server/mea-live/verify_demo.py --task-id <taskId>
# 3) 桌面单视口浏览器核对：切两个角色/Attempt、开 Prompt、开一条证据、刷新不串；console 无页面异常
# 4) 一条普通真实需求（codex-run-test-waimai 项目）走到完成并打开真实 PR
# 5) 用一个已有失败/空状态样本确认错误显示
```

全部通过后在本文件补记：演示 taskId / PR 链接、verify_demo 输出、桌面截图与演示顺序，然后方可写「Demo 范围验收通过」。
