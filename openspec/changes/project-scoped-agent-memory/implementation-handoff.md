# RD-Bot 项目级持久记忆实施交接

> 交接日期：2026-08-30（Asia/Shanghai）  
> Change：`project-scoped-agent-memory`  
> 当前结论：**仅完成基础骨架，尚不可用于生产、真实项目或管理员操作。** 任务清单为 **5/31 已勾选，26/31 未完成**；其中 1.3 虽已勾选，仍须优先复核 claim/lease/fencing 是否真的完整。

## 1. 接手人先相信什么

本文件是当前工作副本的交接基线。接手人应保留现有文件和未提交修改，不要从头重写，不要因局部代码粗糙而整体回退。已经验证的领域骨架、SQL 策略、候选校验、提升策略可以继续沿用；未完成的最终化原子登记、worker、检索接线、治理和真实验收必须按本文件继续补齐。

以下三类事实必须严格区分：

1. **本交接会话已验证**：命令在当前工作副本实际执行并得到明确结果。
2. **先前实施代理报告通过**：可以作为线索，但因当前 Java 23 环境无法复跑 Mockito inline 测试，不能等同于当前已验证。
3. **未验证或未实现**：不得在任务、提交说明或验收报告中表述为完成。

## 2. 精确仓库检查点

| 项目 | 当前值 |
|---|---|
| 仓库 | `/Users/wish233/Documents/RD-Bot` |
| 分支 | `codex/improve-requirement-pr-description` |
| 基线 HEAD | `65babda1a3e97aa7b4f35f6d3eec1be3e594441d` |
| Git 状态 | 有未提交修改和未跟踪新增文件 |
| 提交/推送 | 未提交、未推送 |
| OpenSpec archive | 未执行；实现和验收完成前禁止 archive |
| 当前 JDK | Homebrew OpenJDK 23.0.2 |
| 项目要求 | Java 21；本机当前未安装 Java 21 |
| 实 PostgreSQL | `127.0.0.1:55432` 不可用；`pg_isready` 未安装 |

当前已修改的 tracked 文件：

- `engine/src/main/java/com/wish/rd/engine/requirement/job/RequirementStageFinalizationPort.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/job/InMemoryRequirementStageFinalizationPortTest.java`

其余本 change 的实现、测试、迁移和 OpenSpec 文件目前均为 untracked。接手后第一条命令必须是 `git status --short`，确认没有其他人新增修改后再继续。

## 3. 已确定且不得漂移的设计约束

1. **项目是硬安全边界**：每条 memory、revision、source、operation、查询和治理动作都必须显式绑定 `projectId`；禁止 repository 或其他模糊字段兜底。
2. **新记忆是独立领域**：不得包装或复用 `WorkflowExperienceEntry` 作为新持久记忆的生产真相。
3. **PostgreSQL 是唯一权威真相**：memory identity、不可变 revision、当前 head、source snapshot、operation 状态和项目模式均须持久化；不得依赖 JVM 内共享状态。
4. **版本推进使用 CAS**：每个 memory 最多一个当前 ACTIVE head；冲突使用 `SUPERSEDE`、失效期或 `QUARANTINE`，不得物理覆盖旧证据。
5. **外部副作用使用确定性 operation key**：登记、claim、lease、fencing、settle 和重放必须可审计；旧 fencing token 不得完成新 owner 的操作。
6. **可靠捕获点是 stage 最终化事务**：最终化与 memory operation 或 `SKIPPED_BY_POLICY` marker 必须同事务登记；worker 失败不得回滚已完成交付。
7. **检索必须 SQL 有界**：数据库层强制 project/role/ACTIVE head/有效期/脱敏/质量谓词和 candidate limit；禁止加载全部 revision 后在 JVM 过滤。
8. **Prompt 中记忆是不可信证据**：使用 `UNTRUSTED_PROJECT_MEMORY`，包含 memory/revision/source hash；记忆不能扩大工具、网络、凭证、审批或 QA 权限，也不能单独满足验收。
9. **按项目独立模式**：`capture_mode=OFF|SHADOW|ACTIVE` 与 `read_mode=LEGACY|SHADOW|DUAL|PRIMARY` 独立持久化；默认关闭并 fail closed。
10. **治理必须可信授权**：请求体 actor 不可信；修改需要 host principal、项目范围和 `PROJECT_MEMORY_GOVERN`。Purge 另需 `PROJECT_MEMORY_PURGE`、preview、短期确认 token、重新授权和高风险评审。
11. **OpenViking 不属于本 change**：本 change 只允许 PostgreSQL retrieval。OpenViking projection 必须等 PostgreSQL shadow 门禁通过后另开 OpenSpec change。

## 4. 已完成或已有可复用基础

### 4.1 已勾选任务

| 任务 | 当前产物 | 交接判断 |
|---|---|---|
| 1.1 领域契约 | `rag/.../project/memory/` model、ports、in-memory stores 与契约测试 | 本会话聚焦测试已通过，可继续沿用 |
| 1.2 SQL 策略/迁移 | `p19_project_agent_memory.sql`、`ProjectAgentMemorySqlPolicyTest` | 静态策略测试通过；迁移从未在真实 PostgreSQL 执行 |
| 1.3 PostgreSQL adapters | entity、mapper、store、operation store、Mockito 测试 | 已勾选但证据不完整；claim/lease 适配器能力需复核，必要时先取消勾选 |
| 2.1 Candidate validator | `ProjectMemoryCandidateValidator` 与测试 | 本会话测试通过 |
| 2.2 Promotion policy | `ProjectMemoryPromotionPolicy` 与测试 | 本会话测试通过 |

### 4.2 当前新增文件地图

RAG 领域与检索基础：

- `rag/src/main/java/com/wish/rd/rag/project/memory/`
  - `ProjectMemoryStore`、`ProjectMemoryOperationStore`、`ProjectMemorySearchPort`、`ProjectMemoryModeStore`
  - memory/revision/source/operation/search/mode 的 models
  - in-memory contract implementations
- `rag/src/main/java/com/wish/rd/rag/retrieval/impl/ProjectMemoryRetrievalChannel.java`
- `rag/src/main/java/com/wish/rd/rag/context/ProjectMemoryUntrustedContext.java`
- 对应测试位于 `rag/src/test/java/com/wish/rd/rag/...`

Engine 基础：

- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryCandidateValidator.java`
- `engine/src/main/java/com/wish/rd/engine/project/memory/ProjectMemoryPromotionPolicy.java`
- `ProjectMemoryCaptureEvidence`、`ProjectMemoryPromotionEvidence`、`model/ProjectMemoryCandidate`
- 对应测试位于 `engine/src/test/java/com/wish/rd/engine/project/memory/`

Bootstrap PostgreSQL 草案：

- entity：`ProjectMemoryRow`、`ProjectMemoryRevisionRow`、`ProjectMemorySourceRow`、`ProjectMemoryOperationRow`
- mapper：`ProjectMemoryMapper`、`ProjectMemoryRevisionMapper`、`ProjectMemorySourceMapper`、`ProjectMemoryOperationMapper`
- adapter：`PostgresProjectMemoryStore`、`PostgresProjectMemoryOperationStore`
- migration：`bootstrap/src/main/resources/sql/postgres/p19_project_agent_memory.sql`
- tests：`ProjectAgentMemorySqlPolicyTest`、`ProjectAgentMemoryPostgresStoreTest`

### 4.3 已有但尚未接线的半成品

以下代码有价值，但对应任务仍应保持未勾选：

- `ProjectMemorySearchPort` 和 PostgreSQL `searchActiveHeads`：已有精确项目、role、ACTIVE、有效期、脱敏、质量阈值、稳定排序和 `LIMIT` 基础；尚未形成生产 retrieval adapter 接线。
- `ProjectMemoryRetrievalChannel`：已有独立通道和 `rd-memory://projects/{projectId}/memories/{memoryId}/revisions/{version}` URI；尚未接入 scoped aggregator。
- `ProjectMemoryUntrustedContext`：已有不可信块渲染；尚未接入 `RoleContextBuilder`、input manifest 和宿主 prompt。
- memory modes：已有 model/in-memory store；尚无 PostgreSQL mode store、管理 service、路由和项目禁用 fail-closed 接线。

## 5. 必须先处理的危险半成品

### 5.1 `RequirementStageFinalizationPort` 尚未完成

文件只新增了嵌套 `ProjectMemoryOperationDraft` record（含 `none()` 和 `enabled()`），但：

- 尚未加入 `FinalizationCommand`；
- in-memory finalization port 未登记 operation；
- PostgreSQL finalization adapter 未登记 operation；
- 没有证明与 stage 最终化同一事务；
- 当前新增测试仅验证默认 no-operation，不是任务 2.3 的完成证据。

接手人不得在这个状态上继续堆 worker。必须先用 TDD 完成 2.3，或者在改变设计时同步更新 OpenSpec artifacts。

### 5.2 `InMemoryProjectMemoryStore.replaceRevision(...)` 是 stub

当前实现直接返回 `false`。在实现 consolidator/resolver 前必须决定：

- 若接口确实需要它，按 CAS、不可变 revision、单一 head 语义实现并补契约测试；
- 若 resolver 不需要它，删除未使用能力并同步测试/设计。

不能让生产路径静默得到 `false`。

### 5.3 PostgreSQL adapter 需要重审

- `PostgresProjectMemoryStore` 和 `PostgresProjectMemoryOperationStore` 有明显压缩成单行的代码，可读性不足，公开契约说明不完整。
- `PostgresProjectMemoryStore` 构造器允许 revision/source mapper 为 `null`，随后通过 `UnsupportedOperationException` 失败；必须改为明确、可注入、fail-fast 的生产组件边界。
- mapper 中存在 `claimNext()`，但 `PostgresProjectMemoryOperationStore` 对外目前只清楚暴露 register/find/settle；任务 1.3 要求的 claim/lease/fencing 是否完整尚未被证明。
- `ProjectAgentMemoryPostgresStoreTest` 的现有六个测试主要覆盖解析、CAS、写 revision/source、operation replay/settle 和查询谓词，未清楚证明 adapter 的 claim/lease acquisition。

动作：切到 JDK 21 后重跑测试，逐条对照 1.3；若 claim/lease 仍缺失，先把 `tasks.md` 中 1.3 改回 `[ ]`，补失败测试后再实现。

### 5.4 p19 迁移只是草案

迁移包含 memory/revision/source/operation/legacy link、项目强 FK、同 memory 复合 FK 和索引，但从未在真实 PostgreSQL 应用。必须用一次性测试库验证：

- 建表和约束顺序；
- circular/DEFERRABLE FK 行为；
- task 删除后 source snapshot 保留；
- 并发 head CAS；
- operation claim/lease/fencing；
- schema initializer 确实显式加载 p19。

## 6. 任务账本：31 点精确状态

| 任务 | 状态 | 下一动作/完成证据 |
|---|---|---|
| 1.1 | 已勾选 | 保持契约；JDK 21 再跑一次 |
| 1.2 | 已勾选 | 真实 PG 前不得称迁移可用 |
| 1.3 | 已勾选但待审 | 复核 claim/lease/fencing；缺一项即取消勾选 |
| 1.4 | 未完成 | 扩展 real smoke，证明 task delete、双 head 拒绝、旧 fence 拒绝 |
| 2.1 | 已勾选 | 保持 host-owned validation/redaction |
| 2.2 | 已勾选 | 保持 candidate-only 和 promotion gate |
| 2.3 | 未完成且已有危险草稿 | 完成 finalization 同事务 operation/skip marker 登记 |
| 2.4 | 未完成 | TDD 实现 ADD/NOOP/UPDATE/SUPERSEDE/QUARANTINE consolidator |
| 2.5 | 未完成 | TDD 实现有界 retry/backoff、claim、lease、fencing、checkpoint worker |
| 2.6 | 未完成 | 只补 operation 的 reconciliation；禁止重跑交付 |
| 3.1 | 未完成，有基础 | 接 production PostgreSQL search；审计 examined rows |
| 3.2 | 未完成，有基础 | 接独立 retrieval channel，验证排序/隔离/排除 |
| 3.3 | 未完成 | knowledge/memory/legacy 共享 topK/token 预算并跨通道去重 |
| 3.4 | 未完成 | 收敛 delivery engine/orchestrator 两条旧绕行入口 |
| 3.5 | 未完成，有基础 | 接 untrusted block、manifest、role contract 和权限不变量测试 |
| 4.1 | 未完成，有基础 | PostgreSQL mode store、管理 service、路由、禁用 fail closed |
| 4.2 | 未完成 | legacy 仅 inventory/link/candidate；模糊记录不激活 |
| 4.3 | 未完成 | 版本化 shadow corpus、基线、指标和 gate revision |
| 4.4 | 未完成 | leakage/阈值阻断 PRIMARY；项目级 canary/回滚隔离 |
| 5.1 | 未完成 | engine admin service、可信 authorizer、审计、CAS |
| 5.2 | 未完成 | controller/DTO、principal、分页和 403/404/409 翻译 |
| 5.3 | 未完成 | frontend model/service/route/page/Vite proxy |
| 5.4 | 未完成，高风险 | purge 协议先测试和专项评审，后实现；禁止碰真实数据 |
| 5.5 | 未完成 | 真 HTTP/浏览器/DB 前后状态和证据引用 |
| 6.1 | 未完成 | architecture/config test 固定 OpenViking 缺席、future SPI 默认关 |
| 6.2 | 本 change 不实现 | shadow 通过后另开 OpenSpec change |
| 7.1 | 未完成 | 完整 rag/engine 聚焦套件并记录 SHA/通过数 |
| 7.2 | 未完成 | JDK 21 + throwaway PG，real smoke 必须 executed 非 skipped |
| 7.3 | 未完成 | frontend 全契约/typecheck/build；按影响范围跑 Pi 验证 |
| 7.4 | 未完成 | 冻结数据集 shadow；leakage=0 且门槛全过才升级 |
| 7.5 | 未完成 | 补实际 evidence，strict validate；一致前不得 archive |

## 7. 推荐接手顺序，不要跳点

### 第 0 步：恢复可信工具链

1. 安装/选择 Java 21，不要修改测试来迁就 Java 23。
2. 在 Java 21 下重跑当前全部聚焦测试。
3. 若 Mockito 测试仍失败，再按测试本身诊断；不要把当前 Java 23 attach 错误误判为业务回归。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
./mvnw -q -pl rag,engine,bootstrap -am -DskipTests compile
```

### 第 1 步：审计并收紧 1.3

逐项检查 `ProjectMemoryOperationStore`、operation mapper、Postgres adapter 是否共同提供：register、claim、lease expiry、fencing token、matching replay、mismatch fail closed、settle。先写缺失失败测试；证据不足先取消 1.3 勾选。

### 第 2 步：完成 2.3 finalization 原子登记

先增加下列语义测试，命名可贴合项目惯例，但不得减少覆盖：

- `registersProjectMemoryOperationInsideFinalizationBoundary`
- 相同 operation replay 幂等；
- 不可变输入 mismatch fail closed，且 task/command 不被错误最终化；
- 策略跳过时登记确定性 `SKIPPED_BY_POLICY`；
- PostgreSQL adapter 在同一事务写 finalization 与 operation；
- worker 故障不回滚已经提交的交付。

然后同步修改：

- `RequirementStageFinalizationPort.FinalizationCommand`
- in-memory finalization port
- PostgreSQL row/mapper/finalization adapter
- in-memory 与 PostgreSQL tests

### 第 3 步：再做状态机和运行可靠性

顺序固定为 2.4 consolidator/resolver → 2.5 worker → 2.6 reconciliation。不要在 finalization durability 未成立时先写 worker。

关键规则：CAS 失败只重读一次；不可裁决冲突进 QUARANTINE；重放不重复 revision；reconciliation 只补 deterministic operation，绝不重新执行交付。

### 第 4 步：用真实 PostgreSQL关闭 1.4/7.2

只使用一次性测试实例，例如 `pgvector/pgvector:pg16`，目标固定为 `127.0.0.1:55432/rdbot_acceptance`。不得连接共享或生产数据库。更新 `PostgresClasspathSchemaInitializer` 显式加载并验证 p19 后执行：

```bash
./mvnw -q -pl bootstrap -am \
  -Drd.integration.stage-finalization.enabled=true \
  -Dtest='*ProjectMemory*,ProjectScopedRequirementKnowledgeSearchAdapterTest,PostgresRequirementStageFinalizationAdapterTest,PostgresRequirementStageFinalizationRealSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

报告必须写明 PostgreSQL 版本、测试 SHA、real smoke 非 skipped、重复/并发/crash replay/task delete/fencing 的具体证据。

### 第 5 步：接入检索和安全上下文

按 3.1 → 3.2 → 3.3 → 3.4 → 3.5 进行。重点追踪：

`ProjectScopedRequirementKnowledgeSearchAdapter → RoleContextBuilder → RoleExecutionInputManifestBuilder → RequirementDeliveryEngine/RequirementAgentStageOrchestrator`

完成时必须证明没有旧 legacy 预取绕过 aggregator，没有重复注入，所有通道共享总预算，且 memory 不影响权限和验收规则。

### 第 6 步：模式、迁移和 shadow 门禁

按 4.1 → 4.2 → 4.3 → 4.4。先完成 per-project 持久模式，再做 legacy inventory，最后冻结 shadow corpus。任何项目从 SHADOW/DUAL 升 PRIMARY 前必须满足 leakage=0 和全部冻结阈值。

### 第 7 步：治理、前端、purge 和真实验收

按 5.1 → 5.2 → 5.3 → 5.4 → 5.5。Purge 是单独高风险节点：先专项评审和测试，禁止提前实现物理删除，更禁止在真实用户数据上试验。

## 8. 当前验证证据

### 8.1 本交接会话实际通过

```bash
./mvnw -q -pl rag \
  -Dtest='ProjectMemoryCoreContractTest,ProjectMemoryModeTest,ProjectMemorySearchPortTest,ProjectMemoryRetrievalChannelTest,ProjectMemoryUntrustedContextTest' test
# 11 tests, 0 failures, 0 errors, 0 skipped

./mvnw -q -pl engine -am \
  -Dtest='ProjectMemoryCandidateValidatorTest,ProjectMemoryPromotionPolicyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# exit 0; 4 tests

./mvnw -q -pl bootstrap -am \
  -Dtest=ProjectAgentMemorySqlPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
# exit 0; 1 test

./mvnw -q -pl rag,engine,bootstrap -am -DskipTests compile
# exit 0

OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict --no-interactive
# 11 passed, 0 failed

git diff --check
# exit 0
```

### 8.2 当前无法认证为通过

包含下列 Mockito inline 测试的父会话复跑在 Java 23 上失败：

- `InMemoryRequirementStageFinalizationPortTest`：3 个既有 recovery 测试报错；
- `ProjectAgentMemoryPostgresStoreTest`：5 个测试报错。

根因文本为 `Could not initialize inline Byte Buddy mock maker` / `Could not self-attach to current VM using external process`。这是当前 JDK/host 工具链问题，**不是已经证明的业务失败，也不是通过证据**。先前实施代理曾报告 store/finalization 聚焦测试通过，但接手人必须在 Java 21 下重新确认。

### 8.3 完全未做的验收

- p19 真实 PostgreSQL migration/smoke；
- 全 Maven suite；
- 真实 HTTP；
- frontend contract/typecheck/build；
- 桌面/移动浏览器证据；
- shadow 数据集和跨项目泄漏探针；
- 管理治理与 purge；
- 目标环境运行验证。

## 9. 最终验证命令清单

后续实现完成后至少执行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw -q -pl rag,engine -am \
  -Dtest='*ProjectMemory*,RequirementDeliveryEngineInjectionTest,RequirementAgentStageOrchestratorTest,RoleExecutionInputManifestBuilderTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -q -pl bootstrap -am \
  -Drd.integration.stage-finalization.enabled=true \
  -Dtest='*ProjectMemory*,ProjectScopedRequirementKnowledgeSearchAdapterTest,PostgresRequirementStageFinalizationAdapterTest,PostgresRequirementStageFinalizationRealSmokeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

cd frontend
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build
cd ..

OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict --no-interactive
git diff --check
git status --short
```

若修改 role input/bridge/Pi 资源，还必须执行仓库 `AGENTS.md` 规定的 Pi npm、Maven 和镜像重建/目标环境复验，不能仅靠宿主静态测试。

## 10. 禁止事项

- 不删除、改名或清空 `RULE.md`。
- 不直接修改 `openspec/specs/` 主规格；行为变化继续在本 change delta 中维护。
- 不改写 p0-p18 migration。
- 不复用 `WorkflowExperienceEntry` 作为项目记忆真相。
- 不使用 repository fallback 补 projectId。
- 不把 in-memory store 当生产共享真相。
- 不在本 change 实现或启用 OpenViking memory projection。
- 不因 mock、skipped test 或页面可打开就勾真实环境门禁。
- 不在 finalization durability 未完成时宣称可靠捕获。
- 不对实际项目执行 purge；没有专项评审、preview、确认 token 和重新授权时禁止物理删除。
- 不丢弃或覆盖当前未提交文件；若需大改，先保存 diff 和说明原因。
- 未跑完真实验证前不要 commit、push 或 archive。

## 11. “完成”定义

只有同时满足以下条件，才可把本 change 称为完成：

1. `tasks.md` 31 项均有与文字一致的代码/测试/证据；6.2 应以“已创建后续 change 且本 change 保持不实现”这一边界验收，不得偷跑 OpenViking。
2. finalization 同事务登记、operation replay、claim/lease/fencing、consolidation、worker 和 reconciliation 均有故障窗口测试。
3. Java 21 下聚焦测试与受影响全套测试通过。
4. 一次性 PostgreSQL 上 p19 初始化和 real smoke 实际执行且非 skipped。
5. 检索全链路有 project isolation、共享预算、去重、bounded SQL 和 untrusted prompt 证据。
6. shadow corpus 版本冻结，cross-project leakage=0，所有 gate 达标后才有项目级 PRIMARY canary。
7. 管理 API/前端有可信授权、并发冲突、禁用项目、治理动作和真实浏览器证据。
8. Purge 经过单独高风险评审和完整协议测试；未授权/过期/越项目全部 fail closed。
9. `proposal.md`、`design.md`、delta spec、tasks、实际代码和证据一致，`openspec validate --all --strict` 通过。
10. 最终提交记录精确 SHA、测试数量、PostgreSQL 版本、未完成边界；随后才允许 archive。

## 12. 给下一位实施者的首条指令

可直接复制以下内容：

> 在 `/Users/wish233/Documents/RD-Bot` 接手 `project-scoped-agent-memory`。先完整阅读 `RULE.md`、本交接文档、proposal/design/delta spec/tasks 和最相关主 spec；保留当前未提交修改。先切换到 Java 21 并复跑现有聚焦测试，然后复核任务 1.3 的 claim/lease/fencing，证据不完整就取消勾选。第一项实现工作只做任务 2.3：用 TDD 把 stage finalization 与确定性 memory operation/`SKIPPED_BY_POLICY` marker 放进同一事务，并证明 replay idempotent、mismatch fail closed、worker 故障不回滚交付。完成并报告 diff/测试后，再进入 2.4；不要提前做 OpenViking、purge 或真实数据操作。
