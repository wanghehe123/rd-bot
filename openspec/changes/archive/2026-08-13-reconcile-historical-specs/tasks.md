## 1. 历史来源审计

- [x] 1.1 盘点 `docs/superpowers/specs/` 的每份历史资料，并在 `docs/openspec/historical-spec-provenance-audit.md` 中标明文档状态、当前处置、来源层级和代码/测试锚点。
- [x] 1.2 将待实施设计、历史证据和已被后续决策取代的资料明确排除在主 spec 之外，避免把它们当作当前行为。

## 2. 维护护栏与主 spec 同步

- [x] 2.1 更新 `AGENTS.md` 和 `openspec/config.yaml`，要求 future spec 迁入记录历史来源、当前实现锚点和本轮验证结论。
- [x] 2.2 按 delta 同步 `knowledge/openviking-projection-admin` 主 spec，保留完整 requirement 并补齐已发送操作不可重放的 retry/requeue 场景。

## 3. 验证与归档

- [x] 3.1 运行 `KnowledgeProjectionAdminEngineTest`、`KnowledgeProjectionAdminControllerTest` 和 `OpenVikingProductionBoundaryPolicyTest`，证明 retry/requeue 发送边界与管理层分层未回归。
- [x] 3.2 运行 `openspec validate --all --strict`、检查审计索引覆盖全部历史 spec，确认变更满足归档前提。
