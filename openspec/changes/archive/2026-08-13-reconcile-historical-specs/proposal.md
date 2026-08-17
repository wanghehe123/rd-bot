## Why

仓库已经积累了大量 `docs/superpowers/specs/` 历史设计、验收记录和证据报告，但它们的状态并不一致：有些描述已实现能力，有些是已确认但尚未实施的路线图，还有些只记录一次历史验证。此前建立的 OpenSpec 只覆盖 OpenViking 管理面的一个切片，且没有逐份标明历史来源，容易把计划、历史事实和当前行为混为一谈。

现在需要建立可审计的历史来源索引，并修正 OpenViking 主 spec 中对已越过远端发送边界的 `NEEDS_HUMAN` 重试语义，使后续维护只能基于当前代码、测试和明确标注的历史资料更新。

## What Changes

- 新增仓库内的历史 spec 来源审计：逐份登记 `docs/superpowers/specs/` 中的文件、文档状态、当前处置和可追溯证据；明确区分已验证当前行为、历史证据、待实施计划和已被后续规则取代的内容。
- 修正 OpenViking 投影管理主 spec：`RETRY_WAIT` 与未发送的 `NEEDS_HUMAN` 可以恢复提交；已经有 `remote_operation_id` 的 `NEEDS_HUMAN` 或死信只能进入 `UNKNOWN_REMOTE_RESULT`，不得重新发送远端写请求。
- 收紧 OpenSpec 维护规则：每次把历史资料迁入主 spec 前必须记录来源、当前代码/测试锚点和验证结论；没有当前证据的材料只能保留为历史或未来变更输入。

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `knowledge/openviking-projection-admin`: 使 retry 和 dead-letter requeue 的 Requirement/Scenario 准确表达已发送操作的不可重放边界。

## Impact

- Documentation: 新增历史来源审计文档，更新 `AGENTS.md` 和 `openspec/config.yaml` 的 OpenSpec 维护约束。
- OpenSpec: 为既有 OpenViking capability 增加一个行为澄清 delta，归档时同步到主 spec。
- Verification: 运行 OpenViking engine/controller/policy 测试以及 `openspec validate --all --strict`；不修改业务实现、数据库 schema、HTTP 路径或第三方依赖。
