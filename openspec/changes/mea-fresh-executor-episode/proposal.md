## Why

Phase2 已有 `AuditedGapSection` 与两处 `previousFailureFeedbackSection`，但 `downstreamFailureFeedbackSection` 仍曾把下游 `errorMessage` 注入恢复 Prompt；同时角色证据缺少显式 `HINT|VERIFIED` 信任字段，检索命中可单凭类型名过角色证据门。本 change 在同一 `mea-fresh-executor-episode` 批次内补齐 B09–B11 差异，不重做已归档的 QA 隔离/compact 基础能力。

## What Changes

- B09：`downstreamFailureFeedbackSection`（Engine + Orchestrator）改为 Host 已审计缺口 / 「尚无已审计状态」短句；marker 测试覆盖 checkpoint 打回与 HOST_VERIFY_FIX。
- B10：`RoleContextEvidence.trust`（HINT|VERIFIED + auditRunId）；检索命中默认 HINT 不能单独过门；Host 材料/根/ROLE_HANDOFF 为 VERIFIED；manifest 贯通 trust。
- B11：核对 QA provider-attempt 隔离已有测试；snapshot 冻结 `maxAgentTurns/maxTotalTokens`（来自正数 env，禁止写 0）；`role-budget-defaults.json` 在缺 B07 样本时标 `NOT_FROZEN`。P2 真机未授权则保持未勾选。

## Capabilities

### New Capabilities

- （无）

### Modified Capabilities

- `requirement/fresh-executor-episode`：恢复 Prompt 覆盖 checkpoint 下游反馈；角色证据信任字段与证据门；episode 预算冻结语义。

## Impact

- engine / rag / bootstrap / exec：如上
- 非目标：12×3 R0、评测 UI、BugFix 恢复、擅自 archive/commit
