# 2026-08-01 P0 + P2 实施与验收记录

## 0. 元信息

| 项 | 值 |
| --- | --- |
| 方案 | `docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown` |
| 范围 | Phase 0 + Phase 2（P2-V0～V1 离线 + P2-V2 mock；不含 P2-V4 真实 provider / P3） |
| 实施方式 | composer-2.5 子代理并行实现 + 主代理整合接线 |
| 验收日期 | 2026-08-01 |
| 真实模型调用 | **无**（按方案：离线优先） |
| model-escalation | 仓库 skill 存在于 `.opencode/skills/model-escalation`；本环境无 `premium-advisor` 子代理。协议合并前仍须人工/premium 复核（方案已列为门禁） |

---

## 1. 验收命令与结果

### 1.1 Node（Pi Bridge / P2-V0～V2 mock）

```bash
cd bootstrap/src/main/resources/executor/pi && npm test
```

**结果：39/39 pass**（含 projector、state tools、fingerprint、context injection、facts validation、runtime-context-manifest、既有 protocol 套件）

### 1.2 Java 定向套件

```bash
./mvnw -pl rag,exec,engine,bootstrap -am \
  -Dtest=AgentEventTokenUsageParserTest,RoleExecutionInputManifestTest,\
FactFreshnessEvaluatorTest,AgentTodoStatusTest,RoleExecutionFactTest,\
FactsProtocolValidatorParityTest,AgentRoleResultValidatorTest,\
StructuredResultValidatorTest,DockerPiAgentExecutorTest,\
RdTaskExecutionOverviewControllerTest,RequirementAgentStageOrchestratorTest,\
PiProtocolResourceTest,EngineRequirementExecutionProfileResolverTest,\
PostgresAgentStageArtifactStoreTest,InMemoryAgentStageArtifactStoreTest,\
PiAgentExecutorPropertiesTest,EngineRequirementExecutorAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**结果：BUILD SUCCESS**

| 模块 | Tests run | Failures/Errors |
| --- | ---: | ---: |
| rag | 9 | 0 |
| engine | 19 | 0 |
| exec | 49 | 0 |
| bootstrap | 39 | 0 |
| **合计** | **116** | **0** |

另：`npm test` 39。离线验收合计约 **155** 用例。

### 1.3 未执行（按方案费用门）

| 门 | 状态 | 原因 |
| --- | --- | --- |
| P2-V2 真实 Pi provider rehearsal | **未跑** | 需真实/计费 provider；已用 mock `pi.on("context")` 覆盖注入语义 |
| P2-V3 Docker 离线整链 | **部分** | Host/Bridge 单测与 artifact 映射已通；未重建镜像、未起容器 e2e |
| P2-V4 单项目 canary | **未跑** | 需 provider 余额 |
| Pi 双镜像 rebuild | **未跑** | Bridge 已改，上线前必须 `Dockerfile` + `Dockerfile.qa` 重建并钉 digest |

---

## 2. Phase 0 交付对照

| 退出条件 | 状态 | 证据 |
| --- | --- | --- |
| audit-only `RoleExecutionInputManifest` artifact | ✅ | orchestrator `captureRoleExecutionInputManifest`；类型 `ROLE_EXECUTION_INPUT_MANIFEST` |
| audit-only `RuntimeContextManifest` | ✅ | Bridge `writeRuntimeContextManifest` → `runtime-context-manifest.json` → `RUNTIME_CONTEXT_MANIFEST` |
| Prompt byte/token 估算（chars/4 + estimatorVersion） | ✅ | input manifest budget 字段 |
| Pi usage/cache 聚合，unavailable 不用 0 冒充 | ✅ | `AgentEventTokenUsageParser` → `providerAttemptsJson`；overview `actualAvailable` |
| 不改规则文件策略 / 材料旁路 | ✅ | `mode=LEGACY_OBSERVE_ONLY`；未动 Phase 1 |

---

## 3. Phase 2 交付对照

| 子项 | 状态 | 说明 |
| --- | --- | --- |
| P2.0 协议冻结字段 | ✅ | Profile snapshot：`contextProtocolVersion`、`dynamicStateEnabled`、`maxInjectedStateBytes` 等；默认 `LEGACY_ENVIRONMENT_NOTES` / `dynamicStateEnabled=false` |
| P2.1 Java facts/TODO/state 模型 | ✅ | `rag/.../project/agent/model/*` + freshness/TODO 边测试 |
| P2.1 Schema | ✅ | `rd-agent-fact-v1` / `rd-agent-state-v1` / `rd-agent-state-action-v1`；result additive `facts` |
| P2.2 状态工具 | ✅ | `rd_todo_rewrite` / `rd_todo_update_status` / `rd_record_fact`；flag 开启才注册 |
| P2.3 projector + 不可变 artifact | ✅ | 单 writer 队列；`saveImmutable`（InMemory + Postgres） |
| P2.4 fingerprint / retry guard | ✅ | Node 单测；确定性同参 block |
| P2.5 context hook 注入 | ✅ mock | 禁止 followUp；多轮只保留最新状态；**真实 SDK provider 未验** |
| P2.6 facts Host/Bridge 同 fixture | ✅ | `FactsProtocolValidatorParityTest` + Node fixtures |
| P2.6 下游 facts 传播 | ✅ 最小 | compact handoff 保留 facts；可派生 environmentNotes |
| P2.6 角色合同默认改写为 FACTS_V1 | ✅ | 默认仍 LEGACY；开启 `FACTS_V1` 后 Prompt/Host/Bridge 同步要求 `facts[]`；评审/架构已去掉编码/PR 通用执行器句（`93fed2ec`） |
| P2.7 管理面 state 只读字段 | ✅ | usage/actualAvailable + StageRunView agentState 脱敏摘要（sequence/schema/TODO 计数/hash） |

### 验证门状态

| 门 | 状态 |
| --- | --- |
| P2-V0 | ✅ 通过 |
| P2-V1 | ✅ 通过 |
| P2-V2 | 🟡 mock 通过；真实 provider **待做** |
| P2-V3 | 🟡 Host 接线通过；Docker 镜像/容器 e2e **待做** |
| P2-V4 | ❌ 未做 |

---

## 4. 关键新增路径（节选）

**Java**
- `rag/.../RoleExecutionInputManifest.java`、`RuntimeContextManifest.java`、`RoleExecutionFact*`、`AgentState*`、`FactFreshnessEvaluator`
- `engine/.../RoleExecutionInputManifestBuilder.java`、`AgentStageArtifactStore.saveImmutable`
- `exec/.../AgentEventTokenUsageParser.java`、`RepairArtifactType` 新枚举
- `DockerPiAgentExecutor`：request 透传协议字段 + FACTS_V1 校验 + state artifact 映射

**Node**
- `src/agent-state-projector.mjs`、`agent-state-tools.mjs`、`tool-fingerprint.mjs`、`context-state-injection.mjs`
- `protocol/*.schema.json`（fact/state/action）
- `test/agent-state-*.test.mjs`、`context-state-injection.test.mjs`、`facts-validation.test.mjs`

---

## 5. 上线前必须完成

1. **model-escalation / 人工复核**：facts/state 协议与 immutable store 属高风险面。
2. **重建两个 Pi 镜像**并钉 digest（Bridge/schema/工具已变）。
3. **P2-V2 真实 provider**：确认 context hook 不增 turn、session 不累积状态。
4. **灰度**：仅对新 attempt 设 `dynamicStateEnabled=true` 与/或 `contextProtocolVersion=FACTS_V1`；默认保持 LEGACY。
5. **Prompt 合同**：切到 FACTS_V1 前同步改 `roleInstruction`/`roleOutputContract`，避免 Prompt/Bridge/Host 裂缝。
6. **Phase 1**（角色合同冲突、semantic signature、材料旁路）仍未做——方案入口第 3 步仍建议尽快跟进。

---

## 6. 一句话

P0 度量基线与 P2 离线契约/Bridge 状态栈已落地并通过 **npm 39 + Java 116** 定向验收；真实 provider、镜像重建与 FACTS_V1 Prompt 合同灰度仍是上线门，不是本轮阻塞离线合并的条件。
