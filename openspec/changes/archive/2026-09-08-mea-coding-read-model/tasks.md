# Tasks: mea-coding-read-model

## 1. B04 冻结合同

- [x] 1.1 按计划 §4 固化字段/枚举/分页；写 `fixtures/coding-mea-v1.json`
- [x] 1.2 核对真实关联路径与完整结果缺口，记入 design
- [x] 1.3 固定不变约束：无新业务表、GET 不改数据、Manager 时间不改 hash

## 2. B05 查询实现

- [x] 2.1 新增 Port/Snapshot/QueryEngine 与 Postgres 只读适配器
- [x] 2.2 mapper：task-scoped 分页与显式 ID 批量查询
- [x] 2.3 单元 + 真实 PG 并发快照/归属/纯度测试（`PostgresCodingMeaRepeatableReadRealSmokeTest`；`-Drd.integration.coding-mea.enabled=true`）

## 3. B06 HTTP 与完整结果

- [x] 3.1 CodingMea / StageResult Controllers + 测试
- [x] 3.2 finalization 精确读取与角色解包；截断/下载
- [x] 3.3 真实 HTTP；向前端提供 fixture 与 W1g/W2/W3 identity（2026-09-07 部署 jar `04205367…`；W1g/W2B/W3J/W1e/C06 `coding-mea` 均 200；W1g stage-result 200；跨 task stage 404）
- [x] 3.4 `openspec validate mea-coding-read-model --strict`
