## Context

See proposal.md — Why. Inventory for B09–B11 on this branch:

| 入口 | 状态 |
| --- | --- |
| `previousFailureFeedbackSection` | 已实现 → AuditedGapSection |
| `hostVerifyFailureFeedbackSection` / HOST_VERIFY_FIX | 已实现；marker 测试加强 |
| QA_PROTOCOL_RETRY 受控文案 | 已实现 |
| `downstreamFailureFeedbackSection` | **本 change 已修** |
| Compact UNTRUSTED/VERIFIED | 已有 |
| RoleContextEvidence.trust HINT\|VERIFIED | **本 change 新增** |
| QA provider-attempt 隔离 | 已有测试/归档证据 |
| Episode 预算 P95 defaults | **NOT_FROZEN**（缺 12×3 样本） |
| Episode 预算 env 冻结 | **本 change**：正数 env → snapshot/request |
| P2 真机 | **未真机** |

## Goals / Non-Goals

**Goals:** B09 堵 raw error；B10 信任贯通；B11 预算冻结语义与隔离核验。  
**Non-Goals:** 伪造 P95；12×3；擅自 commit/archive；B12 契约表（另 change）。

## Decisions

1. Downstream 反馈只用 audited gap / 不可用短句。
2. 检索命中 HINT 不过角色门；Host/ROLE_HANDOFF VERIFIED。
3. 无 B07 样本时 `role-budget-defaults.json` 保持 NOT_FROZEN；仅正数 env 可 `FROZEN_FROM_ENV`，永不写 0。

## Risks / Trade-offs

- 无 env 预算时 bridge 仍可能未强制 episode 上限 → 需云端设 `RD_PI_MAX_*` 或补基线后冻结 defaults。
- P2 真机未跑 → Phase2 退出条件中的真机项保持未勾选。

## Migration Plan

部署含本 change 的 jar；设正数 `RD_PI_MAX_AGENT_TURNS`/`RD_PI_MAX_TOTAL_TOKENS`；P2 真机另授权。

## Open Questions

是否授权云端部署本分支 + 跑 P2-W1/W2/W3；是否恢复 12×3 以冻结 P95 预算。
