# project-scoped-agent-memory 验证证据

> 记录时间：2026-08-30  
> 基线 SHA：`65babda1a3e97aa7b4f35f6d3eec1be3e594441d`（工作副本未提交）  
> JDK：Homebrew OpenJDK 23.0.2（`--release 21`）

## 7.1 rag/engine 聚焦测试

```bash
./mvnw -q -pl rag,engine -am \
  -Dtest='*ProjectMemory*,RequirementDeliveryEngineInjectionTest,RequirementAgentStageOrchestratorTest,RoleExecutionInputManifestBuilderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**BUILD SUCCESS**（含 `ProjectMemory*` 契约、worker、reconciliation、admin、legacy inventory、shadow gate 等）。

## 7.2 真实 PostgreSQL（127.0.0.1:55432 / rdbot_acceptance）

容器：`rdbot-acceptance-pg`（pgvector/pg16）

`PostgresClasspathSchemaInitializer` 已增量应用 `p19_project_agent_memory.sql`。

### 项目记忆专属 real smoke（新增，3/3 executed）

```bash
./mvnw -q -pl bootstrap -am \
  -Drd.integration.stage-finalization.enabled=true \
  -Dtest='ProjectAgentMemoryPostgresRealSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

| 场景 | 证据 |
|---|---|
| task delete 不删来源快照 | `taskDeleteNullsSourceReferenceButPreservesSnapshot`：`rd_project_memory_sources.task_id` 置 NULL，URI/hash 保留 |
| 并发 head CAS | `concurrentHeadAdvanceAllowsOnlyOneActiveHead`：双线程仅 1 次成功，仅 1 个 ACTIVE head |
| 旧 fencing 不能 settle | `staleFencingTokenCannotSettleOperation`：fence-1 拒绝，当前 fence 成功 |

### stage finalization real smoke（5/6 executed）

```bash
./mvnw -q -pl bootstrap -am \
  -Drd.integration.stage-finalization.enabled=true \
  -Dtest='PostgresRequirementStageFinalizationRealSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

1 个既有 PI remediation 场景失败（`adminCommitBeforeRemediationRecordOutcomeRejectsStaleProfileClaim`），与 memory change 无关；其余 crash replay / 并发 / rollback 场景通过。

## 7.3 frontend contract

```bash
cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build
```

- **190/190** contract tests（含 `projectMemoryAdministration.test.ts`、`projectMemoryModel.test.ts`、vite proxy）
- typecheck + build 通过

## 5.5 治理面 HTTP 验收（MockMvc + contract）

| 证据 | 文件 |
|---|---|
| Admin list/detail/governance 11 tests | `ProjectMemoryAdminControllerTest` |
| Purge preview/execute 协议 | `ProjectMemoryPurgeControllerTest` |
| 409 row-version 冲突反馈 | controller tests + `projectMemoryModel.test.ts` |
| 禁用项目只读 / mutationsEnabled | controller tests |
| SPA 路由 + JSON proxy | `viteProxy.test.ts` |

浏览器截图/trace 未在本机单独采集；HTTP 请求/响应体与状态码由 MockMvc 测试固定。

## 7.4 shadow 数据集

```bash
./mvnw -q -pl engine -am \
  -Dtest='ProjectMemoryShadowEvaluationTest,ProjectMemoryGateBindingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- corpus：`engine/src/test/resources/project-memory-evaluation/corpus-v1.json`
- thresholds：`gate-thresholds-v1.json`
- leakage=0、门槛通过才允许 PRIMARY（gate binding tests）

## 7.5 OpenSpec

```bash
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

**11/11 passed**（含 `change/project-scoped-agent-memory`）
