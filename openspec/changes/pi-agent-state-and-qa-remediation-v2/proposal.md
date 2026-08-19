## Why

Pi 角色 Attempt 目前没有由 Java 控制面维护的初始状态栏，bridge 会从空 TODO 状态开始，管理端也无法区分“最新状态”和“最近真正注入的上下文”。同时，现有 QA 自动打回依赖很窄的 failure category 组合且只允许一轮，协议失败又缺少可恢复的结构化判定，导致明确的产品缺陷无法稳定回到 Coding，或直接进入人工处置。

## What Changes

- 为显式启用 capability 的 PI execution profile 增加 Java-owned `rd-agent-state/v2` 初始状态、Host 必做 TODO、受控状态动作、上下文末尾注入和跨实例最新状态投影；历史/未启用 capability 的 PI 以及其他 runtime 保持原行为。
- 扩展 execution profile 的权威 capability、optimistic version 与 immutable snapshot/hash 合同，使状态能力和 QA remediation 能力按 Attempt 冻结。
- 扩展 PI QA 结果合同：QA 可通过结构化、证据绑定的 `remediationRequest` 和 `bugFindings` 明确请求 Coding 修复；PI-v2 产品修复最多两轮，仍受每角色 Attempt 3 的硬上限约束。
- 将 QA 打回信息保存为 bounded、sanitized、hash-bound remediation request，并作为 Coding 的受控 attachment 与详细 Prompt 段落传递。
- 增加 PostgreSQL remediation ledger、immutable execution-plan intent、target profile snapshot 和 command generation identity，保证跨实例、崩溃恢复和重复消费时不重复创建 Attempt。
- 为严格 allowlist 的 Pi 生命周期/结果协议失败增加一次 QA→QA retry；协议失败永不创建 Coding Attempt。
- 扩展只读管理 API，返回同一 stageRunId 的静态 Prompt、最近真实注入的有效上下文和最新状态，并显式返回 freshness/stale provenance；本 change 不修改 React/TypeScript 前端。

## Capabilities

### New Capabilities

- `requirement/pi-agent-context-state`: Java-owned PI 状态快照、TODO、运行中投影、上下文末尾注入与 capability 兼容边界。
- `requirement/pi-qa-remediation`: 证据绑定的 QA→Coding 两轮产品修复、一次 QA→QA 协议重试及其持久化幂等合同。
- `requirement/role-effective-context-audit`: 管理端后端 API 对静态 Prompt、最近注入上下文和最新状态的精确 stage 绑定与安全展示合同。

### Modified Capabilities

无。仓库现有 main spec 只有 `knowledge/openviking-projection-admin`，与本 change 无关；本次以新的 delta capabilities 描述经过当前代码和测试重新核验的行为。

## Impact

- `rag`：execution profile capability/version、snapshot canonical contract、状态与 remediation 持久化端口。
- `engine`：PI 初始状态管理、QA 结果路由、remediation intent、阶段 finalization 与恢复语义。
- `exec`：PI request/result/receipt 校验、状态与有效上下文 artifact、QA evidence 权威验证。
- `bootstrap`：PostgreSQL migration/mapper/adapter、Pi bridge、配置、dispatcher/finalizer 和只读管理 API。
- 协议与数据：新增版本化 state、QA remediation、protocol failure receipt、execution-plan intent 和 PostgreSQL ledger；所有新行为由冻结 PI capabilities 与 Host kill switch 控制，不改变 legacy runtime 合同。
