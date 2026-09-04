## Status (2026-09-03 alignment with `mea-audit-only-writeback`)

Code for `HostVerificationPort`, `p15_host_verification.sql`, orchestrator gate, profile `buildCommands`/`staticCommands`, retry phase `HOST_VERIFY`, and `HostVerificationController` is already in the tree. Tasks below stay historical; they were never checkbox-synced after landing.

**Durable production path is not owned by this change.** `boundedRolePlan` disables the orchestrator inner host-verify loop. `mea-audit-only-writeback` adds durable command `HOST_VERIFY` that calls the same port. See this change's `design.md` D2 amendment. Do not archive this change as “production path complete” until that command is live, or archive only after the D2 durable-path note is synced into the main spec.

Verify: `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict`

## 1. 领域模型与计划开关

- [ ] 1.1 为 `HostVerificationRun` / step / artifact 写失败测试与 record（engine），含终态不可复活与 parentRunId
- [ ] 1.2 扩展 `AgentWorkflowPlan`：`hostVerifyRemediationEnabled`、`hostVerifyMaxRemediationPasses=2`；生产 plan 打开；禁止验证关闭时配置 >2
- [ ] 1.3 `TaskFailurePhase` 增加 `HOST_VERIFY`，并补 `TaskRetryPointResolver` 失败测试：最新验证失败则从 Coding 恢复

## 2. QA profile 命令字段

- [ ] 2.1 扩展 `QaValidationProfile` / `Command` / Service / Postgres store / `p15` 前的 profile 列：`buildCommands`、`staticCommands`；旧 JSON 缺字段视为未声明
- [ ] 2.2 命令校验拒绝凭据字面量、换行，以及 BUILD 中的 `npm run dev` / `next dev`
- [ ] 2.3 `QaRepositoryProfileDetector` 按 design D3 探测 BUILD/STATIC；docs-only 时两步都跳过

## 3. 持久化

- [ ] 3.1 新增 `p15_host_verification.sql` 与 README 行；表含 runs / steps / artifacts
- [ ] 3.2 实现 `HostVerificationStore` 内存与 Postgres 适配，含防退化测试（生产路径无 LinkedHashMap 真值）
- [ ] 3.3 任务删除走既有 retention，验证对象一并清理

## 4. 宿主执行

- [ ] 4.1 定义 `HostVerificationPort`；bootstrap 适配器复用 `CleanHostVerifierWorkspaceFactory` 重放 candidate patch
- [ ] 4.2 白名单执行 BUILD 再 STATIC；独立 exit code / 日志 / SHA-256；BUILD 失败不跑 STATIC
- [ ] 4.3 挂 task `/work/cache`；超时单独墙钟；超时记基础设施失败，不打回 Coding
- [ ] 4.4 Coding `testStatus=PASSED` 但宿主退出码非 0 时验证必须失败（契约测试）

## 5. 编排与返工

- [ ] 5.1 `RequirementAgentStageOrchestrator`：Coding 成功后、派发 QA 前调用验证 Port
- [ ] 5.2 `PRODUCT_DEFECT` 且廉价次数 < 2 且 Coding attempt < 3 时只新建 Coding attempt，注入失败摘要
- [ ] 5.3 环境/基础设施/歧义失败走 `FAILED_NEEDS_HUMAN`，不建 Coding
- [ ] 5.4 验证未成功时不得创建 QA attempt；QA 返工仍最多 1 次且仍同时建 Coding+QA
- [ ] 5.5 补 `RequirementAgentStageOrchestratorTest` / `RequirementDeliveryEngineTest` 覆盖上述分支

## 6. 管理 API

- [ ] 6.1 `GET /admin/rd-tasks/{taskId}/host-verifications` 与 run 详情、证据内容；双重归属校验
- [ ] 6.2 QA profile GET/PUT 接受并回传 `buildCommands` / `staticCommands`，旧客户端缺字段兼容
- [ ] 6.3 Controller 测试 + 前端代理合同测试夹具（后端保证路径稳定；页面由前端计划实现）

## 7. 提示与协议锁步

- [ ] 7.1 Coding 角色提示增加「宿主验证失败反馈」段落；不得要求 Coding 把自报测试当终审
- [ ] 7.2 若验证在容器内落日志，bridge 预校验与宿主校验使用同一 `verify-evidence/` 约定；本期默认宿主本机执行则只测宿主校验

## 8. 聚焦验证

- [ ] 8.1 `./mvnw -pl engine -am -Dtest=RequirementAgentStageOrchestratorTest,RequirementDeliveryEngineTest,TaskRetryPointResolverTest,AgentWorkflowPlanTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] 8.2 `./mvnw -pl rag,exec,bootstrap -am -Dtest=QaValidationProfileServiceTest,QaRepositoryProfileDetectorTest,HostVerification*Test,QaValidationProfileControllerTest,HostVerificationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] 8.3 前端代理合同：`cd frontend && node --experimental-strip-types --test test/viteProxy.test.ts`（仅保证新 API 路径被代理；UI 不在本任务）
