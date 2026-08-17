## 1. Brownfield evidence

- [x] 1.1 对照 `RULE.md` §3.5.6、`docs/superpowers/specs/2026-08-13-openviking-projection-protocol-spec.md` 和现有 OpenViking 合同 fixture，确认 desired/observed、owned root、默认关闭、错误分类与 `IN_SYNC` 门槛。
- [x] 1.2 追踪 `KnowledgeProjectionAdminController → KnowledgeProjectionAdminEngine → binding/outbox/document/finding stores + ExternalKnowledgeIndexPort`，并核对 controller、SPA route 与 Vite proxy contract tests。

## 2. OpenSpec brownfield artifacts

- [x] 2.1 在 `openspec/config.yaml` 写入 RD-Bot 技术栈、规则和 OpenSpec apply/archive 维护指导。
- [x] 2.2 创建 `knowledge/openviking-projection-admin` delta，覆盖管理 API、账本收敛、错误安全边界与前端契约；不写入尚未落地的 WP-4/WP-5 未来行为。
- [x] 2.3 创建 design，记录当前入口、持久化真值、协议边界、非目标和回滚方式。

## 3. Validation and ongoing maintenance

- [x] 3.1 运行 `openspec validate --changes document-openviking-projection-admin --strict`，修正 proposal/spec/design/tasks 格式问题。
- [x] 3.2 运行 OpenViking 协议/本地基线、projection admin controller/SPA route 与 production-boundary 聚焦测试。
- [x] 3.3 运行 `frontend` 的 Node contract tests、`npm run typecheck` 和 `npm run build`。
- [x] 3.4 将该 change 的 delta 同步并归档为 `openspec/specs/knowledge/openviking-projection-admin/spec.md`，确认 `openspec validate --specs --strict` 通过。
- [x] 3.5 启用 OpenSpec 扩展工作流并执行 `openspec update`，让当前项目的 agent instruction/command 文件与全局 profile 保持同步。
