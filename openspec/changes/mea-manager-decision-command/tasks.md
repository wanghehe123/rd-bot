## 1. 状态、SQL 与决策模型

- [x] 1.1 `RdTaskStatus.WAITING_USER_INPUT`；`RdTaskTransitionPolicy` 仅需求图按 K6 加边；BugFix 图不变。测试：`RdTaskTransitionPolicyTest`
- [x] 1.2 `p22_task_manager_decisions.sql`：`rd_task_manager_decisions`；放宽 rounds kind/number/targets 与 `ck_rd_requirement_stage_command_generation_v2` 含 `MANAGER_GAP_FIX`；coding-targeting 部分唯一索引；claim/recoverable join `rd_tasks` 排除 paused 与（非 `USER_ANSWER_RESUME` 的）`WAITING_USER_INPUT`。README 第 22 行。测试：`PiAgentRemediationSqlPolicyTest` 扩 p22
- [x] 1.3 `ManagerDecision` / `ManagerPolicy` / `ManagerDecideStages` / `ManagerDecisionStore`（含内存实现）。`ManagerPolicyTest`：QA+PENDING AC → EXECUTE CODING；全 COMPLETED → DONE；HOST_VERIFY 成功 → EXECUTE QA；paused → BLOCKED；缺材料 → ASK；intent 不可铸 → BLOCKED 不 DONE
- [x] 1.4 `AgentRemediationKind.MANAGER_GAP_FIX(2)`；`PiQaRemediationIntent` / `AgentRemediationRound` 按 HOST_VERIFY_FIX 形状且不要求 v2 capability

## 2. Engine 续跑

- [x] 2.1 `planStage` 增加 `MANAGER_DECIDE:*`；裸 `MANAGER_DECIDE` 仍失败关闭
- [x] 2.2 `planHostVerifyStage` 成功 continuation 改为 Manager；`planRoleExecutionStage` QA 成功改为 Manager；Coding 仍 HOST_VERIFY
- [x] 2.3 铸造 `MANAGER_GAP_FIX` intent（有界合同点名 AC）；失败则 BLOCKED。`RequirementDeliveryStageExecutionTest` 覆盖 P3-W1 形状
- [x] 2.4 `RequirementStageExecutionPlan` 可选 `managerDecision`；codec 往返
- [x] 2.5 `ManagerDecisionPurityPolicyTest` 扫描 Manager 类型不得引用 `RepairExecutorPort` / workspace / Docker / Pi executor
- [x] 2.6 协议完整但产品 FAILED 的 QA（无 bounce）续 `MANAGER_DECIDE`；`protocolCompleteAcceptanceReport` 排除 ENVIRONMENT/AUTHENTICATION/QA_INFRASTRUCTURE/FLAKY/REQUIREMENT_AMBIGUITY。阶段记 `SUCCEEDED` 以便复核恢复。测试：`PiQaRemediationPlannerTest#protocolCompleteAcceptanceReportDetectsFailedCurrentGap`、`RequirementAgentStageOrchestratorTest#protocolCompleteQaProductFailureMarksStageSucceededForReviewRecovery`、`RequirementDeliveryEngineTest#shouldNotReturnEnvironmentQaFailureToCoding`
- [x] 2.7 `MANAGER_GAP_FIX` 再入 QA 时，若上一 QA attempt 已终态或 SUCCEEDED 且未达 attempt 上限，新开 PENDING attempt。测试：`RequirementDeliveryStageExecutionTest#managerGapFixQaReentryOpensFreshAttemptWhenPreviousQaStageIsTerminal`、`#managerGapFixQaReentryOpensFreshAttemptWhenPreviousQaSucceeded`

## 3. Dispatcher、finalize、HTTP

- [x] 3.1 `continuationCommand`：QA 成功后续的 `MANAGER_DECIDE:*` 退出 remediation 世代；缺口 Coding 之后的 HOST_VERIFY / Manager / QA 必须复制同一 `remediation_round_id`
- [x] 3.2 `nextStageFor(WAITING_USER_INPUT)` 抛错；`nextRoleTarget` 读决策/Manager command，禁止无条件 `DETERMINISTIC_REVIEW`。`RequirementDeliveryDispatchServiceTest`
- [x] 3.3 `persistDeliveryOutcome` / checkpoint 把 `WAITING_USER_INPUT` 当成功暂停；Java + SQL claim 过滤 paused
- [x] 3.4 finalize 同事务写入 `rd_task_manager_decisions` 与 continuation；`PiRemediationFinalizationWriter` 支持 `MANAGER_GAP_FIX`
- [x] 3.5 `RequirementUserAnswerTransactionPort` + `POST /admin/rd-tasks/{id}/answer` + `USER_ANSWER_RESUME:<sourceCommandId>`。`RdTaskControllerTest`
- [x] 3.6 `failurePhaseForCommand` / retry planner 识别 `MANAGER_DECIDE:*` / `USER_ANSWER_RESUME:*`，避免 `RETRY_POINT_AMBIGUOUS`
- [x] 3.7 Host 附件白名单允许 `attachments/manager-gap-fix/request.json`（compact ctor / `EngineRequirementExecutorAdapter` / `RepairWorkspaceFactory`），不要求 Pi state v2。测试：`RequirementAgentStageOrchestratorTest#durableManagerGapFixInjectsAttachmentWithoutQaV2Capability`、`RepairWorkspaceFactoryTest#shouldPreserveManagerGapFixNestedAttachmentPath`

## 4. 前端与 RULE

- [x] 4.1 徽标/文案/列表筛选与 `WAITING_APPROVAL` 区分；`answerRdTask` 发送 version/fence/decisionHash 绑定 body；`viteProxy.test.ts` 列出 `/answer`
- [x] 4.2 dashboard `WAITING_HUMAN` 含 `WAITING_USER_INPUT`；观测 TERMINAL/SUCCESS 不含它
- [x] 4.3 `RULE.md` 3.5.3 追加 Manager 双权威、有界缺口、暂停领取、WAITING_USER_INPUT 图约束与验证命令（5.1 已跑）

## 5. 验证

- [x] 5.1 重跑（2026-09-06，含 2.6/2.7/3.7 硬化测试）。命令与结果见交接文档 `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md` §11：
  - `./mvnw -pl rag -Dtest=RdTaskTransitionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - `./mvnw -pl engine -am -Dtest=ManagerPolicyTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementStageExecutionPlanCodecTest,RequirementAgentStageOrchestratorTest,PiQaRemediationPlannerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - `./mvnw -pl exec -am -Dtest=RepairWorkspaceFactoryTest#shouldPreserveManagerGapFixNestedAttachmentPath -Dsurefire.failIfNoSpecifiedTests=false test`
  - `./mvnw -pl bootstrap -am -Dtest=RequirementDeliveryDispatchServiceTest,RdTaskControllerTest,PiAgentRemediationSqlPolicyTest,ManagerDecisionPurityPolicyTest,RequirementCompletionWriterPolicyTest,PiRemediationFinalizationWriterTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - `cd frontend && node --experimental-strip-types --test test/viteProxy.test.ts test/rdTaskRolePromptPresentation.test.ts test/roleWorkbenchModel.test.ts && npm run typecheck`
  - `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`（2026-09-06 再跑，15 passed）
- [x] 5.2 真机记录（不要求 COMPLETED+PR，不归档）：P3-W1 三角色/有界缺口由 W1e `7502196308401328128` 证明（AC-003 PENDING 期间无 `DETERMINISTIC_REVIEW`；round 2 合同点名 AC-003；随后 HV+QA+auditQa）。P3-W2/W3 仅单测，未真机。证据与对照见 `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md`。
