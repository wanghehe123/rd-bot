# Tasks: mea-fresh-executor-episode (B09–B11)

## B09 恢复入口已审计缺口

- [x] 1.1 创建 change / 分支 `codex/mea-fresh-executor-episode`
- [x] 1.2 已实现/剩余/未真机三清单（`design.md`）
- [x] 1.3 `downstreamFailureFeedbackSection` 改走 audited gap（Orchestrator + Engine）
- [x] 1.4 marker 测试：checkpoint 打回 / 无 head / HOST_VERIFY_FIX
- [x] 1.5 `openspec validate mea-fresh-executor-episode --strict`（持续维护）
- [x] 1.6 聚焦测试：`AuditedGapSectionTest,RequirementAgentStageOrchestratorTest`

## B10 compact handoff 与检索信任标记

- [x] 2.1 compact UNTRUSTED/VERIFIED 已存在；不重写第二套包装
- [x] 2.2 `RoleContextEvidence.trust` = HINT|VERIFIED（+ optional auditRunId）
- [x] 2.3 Host 材料/根证据/ROLE_HANDOFF → VERIFIED；检索命中默认 HINT
- [x] 2.4 角色证据门只认 VERIFIED 非根证据；HINT 不能单独过门
- [x] 2.5 manifest `RoleExecutionEvidenceEntry` 贯通 trust；UNTRUSTED_PROJECT_MEMORY 输出 trust=HINT
- [x] 2.6 测试：HINT「QA已通过」不过门；同任务 VERIFIED Host 过门；RoleContextBuilder Host 材料 VERIFIED

## B11 角色 episode 预算、隔离与 P2 真机

- [x] 3.1 QA provider-attempt 隔离：既有 `DockerPiAgentExecutorTest`（`/provider-attempts/`）+ 归档 P2-W3 证据；不重套 :ro
- [x] 3.2 snapshot 可冻结 `maxAgentTurns/maxTotalTokens`（正数 env → `FROZEN_FROM_ENV`）；`writeRequest` 贯通；禁止写入 0
- [x] 3.3 `role-budget-defaults.json` = `NOT_FROZEN`（缺 B07 每角色≥3 样本，不填假 0）
- [ ] 3.4 BUDGET_EXCEEDED → Host blocker 专测（bridge 已分类；Host 晋升路径待补断言）
- [x] 3.5 旧 snapshot 无预算字段仍可解码；有字段则重试复用冻结值
- [x] 3.6 Slim P2 真机预算探针 PASS（task `7502657531727187968`，`FROZEN_FROM_ENV`）；ISO/GAP SKIPPED；**未**跑完整 P2-W1/W2/W3 / 12×3（见 `docs/superpowers/qa/2026-09-07-mea-p2-slim-b11.md`）
