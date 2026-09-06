## Context

阶段 1（`requirement/audit-only-writeback`）已归档。生产路径仍是 `RequirementDeliveryDispatchService.submit` → `RequirementDeliveryEngine.planStage`；legacy `RequirementAgentStageOrchestrator.run(AgentWorkflowPlan.production())` 仍被单测与 inner-loop 使用，两套 Prompt 组装必须锁步。

当前泄漏点（2026-09-05 读码）：

- `RequirementAgentStageOrchestrator#previousFailureFeedbackSection` 与 `RequirementDeliveryEngine` 同名方法把上一轮 `errorMessage` 截到 4000 字写入 Prompt。
- Orchestrator `#hostVerifyFailureFeedbackSection` 与 `HostVerifyRemediationPackageBuilder` Prompt 把宿主 `errorMessage`（javac 墙）拼进 `HOST_VERIFY_FIX`。
- Compact 交接标题为「环境备忘（上游角色已实测验证，直接沿用，不要重复探测）」；architect/coding 指令写「上游 facts 视为已验证事实直接沿用」；compact JSON 仍复制 `errorMessage`。
- `DockerPiAgentExecutor` 对所有角色 `workspaceFactory.create(command)`，QA 与 Coding 共用任务 `repo/`；`createProviderAttempt` 已存在且被 Claude fallback / Host verifier 使用。

## Goals / Non-Goals

**Goals**

- 单一纯函数生成缺口段，Orchestrator 与 Engine 共用，避免再分叉。
- Prompt 合同与 `PROMPT_SNAPSHOT` 一致：测试断言快照/captured prompt，不依赖模型自觉。
- QA 隔离复用现成 `createProviderAttempt(command, stageRunId)`，attempt 目录名即 `stageRunId`（已满足 `[A-Za-z0-9._-]+`）。

**Non-Goals**

- 不改 `AuditedTaskState` schema、finalize 事务或 `gate-mode`。
- 不把 FACT 记录在本阶段自动晋升为 COMPLETED（仍由阶段 1 auditor 决定；本 change 只标注已晋升者）。
- 不改 QA 产品补救 `QaRemediationPackageBuilder` 的 hash-bound JSON 来源。

## Decisions

### D1. 缺口段是纯函数 `AuditedGapSection.render(head, lastRun)`

**决定**：在 `engine/.../audit/AuditedGapSection.java` 实现有界渲染；Orchestrator/Engine 的失败反馈槽、HOST_VERIFY_FIX Prompt 前缀都调用它。ID 顺序：`blockers` → `missing`（含 PENDING 阻断门如 `GATE-BUILD`）→ `untrusted`，去重后截 16 个。证据 URI 取 last `AuditRun.sourceRefs`，否则取 head 中 COMPLETED 记录的 `evidenceRefs.uri`，截到仍满足 2000 字。

**放弃**：继续在两个 4000 字方法里改文案；按角色过滤缺口（HOST_VERIFY_FIX 也需要看见 `GATE-BUILD`，同角色重试需要看见 UNTRUSTED claims）。

### D2. 禁止 errorMessage 回退

**决定**：head 缺失或缺口为空时返回 `""`，绝不回退 `errorMessage`。`HostVerifyRemediationPackageBuilder` Prompt 改为引用缺口段 + 附件路径/hash/category；`errorMessage` 若仍写入附件 JSON，必须保持既有 sanitizer 上限，且 **不得** 复制进 Prompt 段。

**放弃**：空缺口时保留截断 javac「以免 Coding 盲目」（违反 P2-W1/W2）。

### D3. Compact 标注而不改 RoleContextPackage schema

**决定**：渲染层给 notes/facts 加 `UNTRUSTED` / `VERIFIED(auditRunId=…)` 前缀或 `trust` 键；`RoleContextEvidence` JSON 的 `trust` 字段留给阶段 6。Compact JSON 删除 `errorMessage` 键。

### D4. QA 用 `stageRunId` 作为 provider-attempt id

**决定**：`QA_AGENT` 在持有 workspace lock 后调用 `createProviderAttempt(command, snapshot.stageRunId())`；git prepare、候选补丁、npm provision、指纹仍针对该隔离 `repo/`。任务级 `cache/` 经现有 `createWorkspace(..., taskRoot.resolve("cache"))` 共享。QA agent 容器的 `/work/repo` 保持可写：隔离靠独立 clone，不靠 `:ro`（K3：`:ro` 会破坏 `npm run build && npm run start`）。

**放弃**：为 QA 新造一套 workspace API；把 QA 继续挂任务根 `repo/`；或把 QA 加入 `READ_ONLY_REPO_ROLES`。

### D5. HOST_VERIFY_FIX 只要求 PI runtime，不要求 `PI_QA_REMEDIATION_V2`

**决定**：`HOST_VERIFY_FIX` 是宿主驱动的 Coding 重试，不是 QA v2 bounce。`buildHostVerifyFixIntent` 用 `prepareSnapshot(task, CODING, id, attempt)`（`requiredCapability=null`）；`PiQaRemediationIntent` 对 `HOST_VERIFY_FIX` 只 `requirePiRuntime()`。`recordOutcome` 锁 profile 时跳过 null 的 `qaProfile()`。`PiRemediationFinalizationWriter` 按 `rd.knowledge.store=postgres` 注册（不得用 `@ConditionalOnBean(AgentRemediationRoundMapper)`）。`p21_host_verify_fix_command_generation.sql` 把 `ck_rd_requirement_stage_command_generation_v2` 放宽到 `HOST_VERIFY_FIX`（p20 只放宽了 rounds.kind）。Durable `runRemediationRole` 在请求 JSON 含 `rd-host-verify-remediation-request/v1` 时注入 `AuditedGapSection` 并 `fromFrozen` 附件，**不**要求项目打开 `piQaRemediationV2Enabled`；`RequirementExecutionRequest` 允许无 initial-state 的 `attachments/host-verify-remediation/request.json`；workspace 保留该嵌套路径。FIX Coding 成功后的下一跳 `HOST_VERIFY` 带同一 `remediationRoundId`，finalize 必须用 `findByRemediationIdentity`，不得用会命中第一轮 HOST_VERIFY 的 `findByIdentity`。`QA_PRODUCT_FIX` / `QA_PROTOCOL_RETRY` 仍必须带 `PI_QA_REMEDIATION_V2`。

**放弃**：为了让 HOST_VERIFY_FIX 在 waimai 上跑而打开项目 `piQaRemediationV2Enabled`（那会改变 QA bounce 合同）。

## Risks / Trade-offs

- [Risk] HOST_VERIFY_FIX 没有 javac 墙后 Coding 更难定位编译错误 → Mitigation：缺口点名 `GATE-BUILD`；完整日志仍在 host-verification artifacts，附件 JSON 可保留有界 sanitized `errorMessage` 供容器内打开，但不进 `PROMPT_SNAPSHOT`。
- [Risk] QA 隔离 clone 变慢 → Mitigation：共享 `/work/cache`；git 仍走任务镜像；P2-W3 真机验证。
- [Risk] Orchestrator 与 Engine 只改一处 → Mitigation：两边都改为调用同一 `AuditedGapSection`；双测断言同一标题与否定 `errorMessage`。

## Migration Plan

1. 合入 engine/exec 测试与实现。
2. 云端 rsync + `start-backend.sh` 重启（无 Pi 任务在跑时）。
3. 跑 P2-W1/W2/W3；通过前不 archive。
4. 回滚：恢复旧 jar 即恢复 errorMessage 注入；无 schema 迁移。

## Open Questions

无。QA 隔离目录名用 `stageRunId` 已由 `RepairWorkspaceFactory.requireSafeTaskDirectoryName` 约束，雪花 ID 合法。
