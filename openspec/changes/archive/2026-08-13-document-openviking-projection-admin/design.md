## Context

See `proposal.md` for motivation. 当前实现已经形成一条可追踪链路：管理 Controller 只负责把 `KnowledgeProjectionAdminEngine` 的领域结果转换为 `{data: ...}` DTO；Engine 读取 binding/outbox/document/finding 账本，并通过外部索引端口、观测 CAS 写入器和 Reconcile Engine 执行受限操作；PostgreSQL 是生产共享状态真值，in-memory store 只用于测试或显式本地模式。

前端通过 `OpenVikingKnowledgePage`、`openVikingKnowledgeService` 和纯展示 helper 消费同一组 DTO。页面导航 `/admin/knowledge/:kbId/openviking` 必须与嵌套的 `/admin/knowledge-base/:kbId/openviking/**` JSON API 分离，Vite proxy contract test 负责守住这条边界。OpenViking URI、鉴权、错误分类、默认关闭和 `IN_SYNC` 门槛已经由 WP-0 冻结 spec 与真实合同测试约束。

## Goals / Non-Goals

**Goals:**

- 把当前已实现且有测试证据的投影管理行为固化为一个可归档的 OpenSpec 主能力。
- 保留 desired/observed 分离、owned root、CAS、幂等、默认关闭和脱敏等边界。
- 为之后的删除收敛、死信恢复、对账增强和管理页改动提供明确的 delta 落点。

**Non-Goals:**

- 不修改 Java、TypeScript、SQL、配置默认值或外部 OpenViking 合同。
- 不把 `docs/superpowers/plans/2026-08-13-wp4-wp5-projection-convergence-and-admin-plan.md` 中尚未落地的未来任务写成当前行为。
- 不一次性为 RD-Bot 全部历史能力生成 spec；后续按真实 change 增量建立能力。

## Decisions

1. **将本 change 作为已实现能力的 brownfield 建档。**
   - 选择：用一份新增 capability delta 描述当前可观察行为，归档后生成 `openspec/specs/knowledge/openviking-projection-admin/spec.md`。
   - 原因：仓库没有同路径主 spec，且 OpenSpec 的 existing-projects 指南建议以真实、小范围 change 累积 spec。
   - 替代方案：把所有 `docs/superpowers/specs/` 批量转换为 OpenSpec；放弃，因为会扩大范围并混入历史计划或重复约束。

2. **以 frozen protocol spec 作为底层合同来源，OpenSpec 主 spec 只承载管理面行为。**
   - 选择：主 spec 保留状态边界、鉴权/ownership/错误语义和管理 API 的可验证结果，具体 OpenViking JSON 字段仍以 WP-0 fixture/spec 为准。
   - 原因：避免两份文档分别发明 DTO 或 URI；管理面行为可以独立演进，但不能越过冻结协议。
   - 替代方案：复制 WP-0 全部 HTTP 合同到主 spec；放弃，因为会制造重复真值。

3. **把前端路由、数据封套和轮询作为同一 capability 的外部行为。**
   - 选择：在同一 spec 中描述页面导航/API 分离、`{data}` 解包、操作后回读账本和稳定轮询。
   - 原因：这些行为直接决定操作者是否看到权威状态，且已有页面、service、presentation 和 proxy contract tests 共同验证。
   - 替代方案：只记录后端 API；放弃，因为会遗漏当前管理面最容易回归的前后端边界。

4. **归档前只做文档验证，不运行真实 OpenViking 写操作。**
   - 选择：运行无外部副作用的 Maven/Node 合同测试和 `openspec validate`；live smoke 仍只在显式 `rd.openviking.smoke=true` 时由专门流程触发。
   - 原因：本 change 没有运行时代码变更，且仓库规则要求普通测试默认不启动真实 OpenViking。
   - 替代方案：为建档启动容器并执行远端写入；放弃，因为会改变外部状态且不属于本 change。

## Risks / Trade-offs

- [当前实现继续变化而未创建新 change] → 将 `openspec/changes/`、`openspec/specs/` 纳入日常 review；行为变更先写 delta，再实现和归档。
- [冻结协议与管理面 spec 漂移] → 修改 OpenViking 协议时同时更新 WP-0 文档、真实 JSON fixture/合同测试和本 capability 的相关 scenario。
- [只通过静态测试而遗漏真实 HTTP 链] → 保留现有 controller/proxy 合同测试，并在涉及运行时行为的后续 change 中补真实 HTTP 验收；本建档 change 不启用 live smoke。

## Migration Plan

1. 验证 change 下的 proposal、delta spec、design 和 tasks。
2. 运行 `openspec validate --specs` 及本 change 记录的聚焦测试。
3. 通过 archive 将 delta 合并为 `openspec/specs/knowledge/openviking-projection-admin/spec.md`，并保留带日期的归档 change 作为审计记录。
4. 后续修改先创建新的 OpenSpec change；若只是实现已定义行为，则用 verify/apply，不直接改主 spec。

本 change 没有数据库或部署迁移，回滚只需回滚 OpenSpec 新增文件，不触碰运行时代码。
