## Context

见 `proposal.md`。当前仓库只有一个 OpenSpec 主 capability：`knowledge/openviking-projection-admin`。它从冻结 WP-0 协议、管理 controller/engine、前端页面和测试中建立，但此前没有一份逐项的历史资料来源清单。

本轮追踪确认 OpenViking 管理链路为 `KnowledgeProjectionAdminController → KnowledgeProjectionAdminEngine → binding/outbox/finding stores 与 ExternalKnowledgeIndexPort`；前端链路为 `App → OpenVikingKnowledgePage → openVikingKnowledgeService → Vite proxy`。在这个链路中，retry 和 dead-letter requeue 都由 outbox 的行版本更新实现；SQL 已按 `remote_operation_id` 区分未发送与已发送的行。

## Goals / Non-Goals

**Goals:**

- 将全部历史 spec 逐份列入可审计索引，记录其文档身份、当前处置和证据等级。
- 仅把可由当前代码、测试和已冻结协议共同支撑的 OpenViking 语义写入主 spec。
- 把未来设计、历史证据和被 supersede 的决策保留为来源资料，而不是主 spec requirement。
- 让后续 agent 在迁入历史资料前执行同样的来源核验。

**Non-Goals:**

- 不把所有历史设计一次性转换为主 spec。
- 不实现 Pi 可读轨迹、Project Autopilot、Evaluation V2、P3 role-context 等待实施设计。
- 不修改 OpenViking 的 Java、SQL、HTTP 或前端实现。
- 不以历史任务、截图或文档自述替代当前代码和测试证据。

## Decisions

### 1. 历史资料采用四级处置，而不是按文件名直接迁入

审计将每份资料标为：`VERIFIED_CURRENT`（本轮读取当前实现和测试并运行聚焦验证）、`IMPLEMENTED_CLAIM`（历史文档声称已实施且存在当前代码/测试锚点，但本轮未重新运行该域的全套验证）、`PLAN_OR_DECISION`（未来设计或待实施裁决）或 `EVIDENCE_OR_SUPERSEDED`（历史证据、验收记录或已被后续规则替代的材料）。

选择此方式是为了保留历史价值，同时防止“文档写了已实现”被误认为当前部署或当前代码已验证。

### 2. 主 spec 只修正由当前实现证明的 OpenViking 行为

`KnowledgeProjectionAdminEngine.retry` 复用 stalled-row 原语；`KnowledgeExternalIndexOutboxMapper.resumeStalled` 和 `requeueDeadLetter` 按远端操作标识分流。已经发出的行不能回到 `PENDING`，否则会重放远端写请求。delta 只修改这一 Requirement，保留其他现有场景。

### 3. 来源索引放在文档层，主 spec 保持行为合同

OpenSpec 主 spec 是当前行为的 WHAT，不把 37 份历史文件和类路径堆入 Requirement。来源、实现锚点、验证结论和未迁入原因写进单独的审计文档；`AGENTS.md` 和 `openspec/config.yaml` 只保留简洁且可执行的维护规则。

## Risks / Trade-offs

- [历史文档与当前实现存在差异] → 审计明确记录为计划或 superseded，不为“完整迁移”强行编造 requirement。
- [测试名称或路径以后移动] → 索引记录能力和当前锚点；后续 behavior change 必须重新追踪，不把链接当永久证据。
- [文档更新误写成已验证] → `VERIFIED_CURRENT` 只能在当前变更实际运行相应命令后使用。
- [已发送操作被重放] → OpenViking 主 spec 明确发送边界，并以 engine、mapper 与 policy test 共同验证。

## Migration Plan

1. 新增历史 spec 来源审计并核对所有文件都被登记。
2. 更新维护规则，要求 future change 先分类历史资料再迁入主 spec。
3. 同步本 delta 到 `knowledge/openviking-projection-admin` 主 spec。
4. 运行 OpenViking 聚焦测试、严格 OpenSpec 校验和文档静态检查；通过后归档本 change。
