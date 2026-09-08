# RD-Bot MEA Demo 快速交付方案

> **For agentic workers:** 使用 `superpowers:executing-plans` 按 D00–D05 执行；代码探索继续使用 Luna。本文件是新的 Demo 范围合同，优先于旧计划中“必须完成完整真机矩阵才能继续”的排期要求。未完成的历史实验保持未完成，不因本次降标改成 PASS。

**Goal:** 快速交付一个能稳定演示普通需求流程的研发管理后台：四角色产物与证据可看，Coding 内 MEA 可追踪，当前状态和 Prompt 不串 Attempt，执行失败能够明确结束并显示原因。

**Architecture:** 复用已提交的后端、已经写好的工作台及现有读取 API，优先修接口不一致和普通流程错误。Demo 不再依赖自动崩溃恢复、完整治理功能、性能消融或大规模真机验证。

**Tech Stack:** 沿用当前 Java/Spring/PostgreSQL/Pi、React/TypeScript/Vite；不新增基础设施、业务表、模型路由框架或页面组件库。

**Spec / 来源:** 用户 2026-09-08 明确要求降低苛刻验证及完整性功能、快速出 Demo；本文件据此缩小 2026-09-06 前后端计划。现有正常执行的状态、归属与证据真实性约束继续沿用 `RULE.md` 和 requirement 主 spec。

## 1. 先回答：现在还差多少

**后端代码已推进到 B11，当前主要卡在“杀 JVM 后自动恢复”的扩展验证；前端主要页面也已写好，但尚未与这个后端版本完成可靠的集成。Demo 不需要再做原计划 B12–B24 的 13 个后续治理任务。**

不能按任务编号计算“完成百分比”：有些任务是读取 API，有些是跨进程恢复，工作量不同；代码存在、报告称通过、当前组合可运行也不是同一件事。按本方案，剩余工作收敛为 D00–D05 共 6 个收口任务。

### 1.1 交接对照

| 原范围 | 当前可确认的进度 | Demo 处理 |
| --- | --- | --- |
| B00–B06，P3基础与读取 | P3/read-model 已有相关提交，fresh 分支包含 Coding MEA、Manager全文、stage result API；历史真机结论按对应报告使用 | 复用；不重新开发读取框架，不重复要求 W1g/ASK/pause 全矩阵 |
| B07–B08，基线与指标 | 脚本、case、成功口径与overview已在提交祖先中；12×3、R0及R0/R1/R3比较未完成，slim样本不证明P95门槛 | 不再是 Demo 前置；保留已有指标，不承诺比较结果 |
| B09，恢复Prompt | change 1.1–1.6 已勾；checkpoint 下游反馈和 marker 测试已做 | 保留现有代码，针对性测试即可，不再主动 kill JVM 验证 |
| B10，信任标记 | change 2.1–2.6 已勾；HINT/VERIFIED 与 manifest 贯通 | 保留，不再扩大信任体系或补全所有历史数据 |
| B11，预算冻结 | slim task `7502657531727187968` 的 Reviewer snapshot/request 已证明 `FROZEN_FROM_ENV` | 接受该有限证据；用现有正数配置，不再为 Demo 采 P95 |
| B11，预算异常 | 3.4仍未勾；bridge有BUDGET_EXCEEDED分类，但executor把synthetic failure归为PI_BRIDGE_PROTOCOL，缺Host端到该结果的断言 | D02核对并最小修复失败归类；不新增预算系统 |
| 完整 P2-W1 | 交接最新快照为 command 再次 RUNNING、deadline 已刷新，但新 Coding Attempt 无 Prompt；未 PASS | 从 Demo 发布门槛移除；保留为后续自动恢复问题 |
| 完整 P2-W2/W3 | 本次完整矩阵尚未运行；隔离已有其他测试/历史证据 | 不再强制新做故障注入；普通演示中自然产生的 QA 记录足够 |
| B12–B24 | 后续稳定契约、反查、预算账本、回执恢复、记忆、模型/消融，不能视为已完成 | 全部移出 Demo 范围 |
| 前端 F01–F10 | 独立 worktree 已有产物面板、Coding MEA、状态栏及读取服务；源码尚未提交 | 迁入候选、修真实字段差异，做桌面单场景验收；不重新做整套 UI |

### 1.2 已发现的真实 Demo 问题

这些问题比继续跑 P2 kill 矩阵更接近研发用户实际体验：

| 文件 | 当前问题 | 最小修法 |
| --- | --- | --- |
| `frontend/src/services/codingMeaService.ts` | 前端 query 用 `stageRunId`，后端读 `codingStageRunId` | 统一使用后端真实参数，避免默认返回错误 Coding Attempt |
| 同文件 | Manager 全文实际字段是 `boundedContract`；前端按预览字段读取 | 为全文响应定义独立 wire type，正常显示实际合同 |
| 同文件 | 后端审计记录为 `id/text/evidenceRefs`，前端 fixture 使用 `recordId/title/evidenceIds` | 一处显式适配，保留完整 EvidenceRef，不把 URI 猜成 artifactId |
| `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx` | `loadCodingMea` 仍传 `{ stageRunId }` | 改为 `{ codingStageRunId: stageRunId }`，保留请求 generation guard |
| `frontend/src/pages/admin/rdtask/codingMeaModel.ts` | 部分关系靠 role+attempt、round 或第一条 decision 回退推断 | 使用实际 links/sourceCommandId/remediation identity；不能准确关联就显示不可用 |
| `frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx` | QA content URL 缺少 taskId | 复用后端 `contentUrl`，或使用 task-scoped 路由 |

以上来自本轮 Luna 的代码阅读，不是已修复结论。前端历史验收报告列出的 236 个测试和 FE-01–FE-14 通过，不能证明这个新组合已经联调通过。

## 2. Demo 范围：留什么，砍什么

本轮的“B端不出错”限定为**研发人员使用的管理后台及其支撑接口**：支持的普通路径能执行、能查看；空数据、失败和无效引用有正确反馈；不白屏、不串任务、不把失败显示成完成。它不是对任意中断、任意并发、任意项目的生产可靠性承诺。

### 2.1 必须保留的演示能力

1. 在一个已有测试项目中提交一个材料明确、小范围的普通需求，按现有四角色流程推进。
2. 四角色仍是需求评审、方案设计、编码、QA；默认打开“产物与证据”。
3. Coding 内展示当前 MEA 决策、执行和审计摘要；QA 引用实际第四角色 Attempt，不复制新角色。
4. 所选 Attempt 状态常显，Prompt 可切换；切角色、刷新和换任务不串数据。
5. 产物可以读预览，关键 QA/Host 证据至少能打开；没有完整产物则如实说明，不要求为历史记录补齐全文。
6. 普通任务可到现有真实完成/交付状态；失败、超时和预算耗尽不能变成绿色成功，不无限自动重启。

### 2.2 本期明确砍掉的功能开发

| 移出项 | 对应原任务 | Demo替代 |
| --- | --- | --- |
| 稳定版本契约表、执行中追加材料自动重规划 | B12–B13 | 演示输入在提交前定好；演示中不改验收合同 |
| 新模型契约反查、扩大三维完整性判定 | B14 | 使用当前已有 Host/QA 审计，不新增模型复核功能 |
| 扩大 PR 最终消费者证据体系 | B15 | 复用现有发布链；演示后实际打开 PR/产物确认一次 |
| 跨角色任务预算账本、成本精算与自动预算治理 | B16 | 保留当前正数 episode 限制和真实失败状态 |
| 新无进展检测系统 | B17 | 保留当前有界次数/期限；异常人工结束，后续再做自动治理 |
| 新执行回执、崩溃窗口自动恢复保障 | B18–B19及P2新增恢复实验 | 演示不主动中断服务；异常不承诺自动续做 |
| 项目记忆晋升、生产worker接线、完整审计记忆功能 | B20–B22 | 不以记忆学习为卖点；保持未启用能力不启用 |
| 风险模型路由、R0/R1/R3消融和降本证明 | B23–B24 | 固定一个当前可用模型配置，不新增自动切换 |
| 完整轮次历史、分页/因果图细节、所有格式下载 | F05/F06扩展部分 | 展示所选 Coding 的当前摘要；历史/完整下载已有可用则保留，不为缺失补造新功能 |
| 移动端和多视口专项优化 | F09专项部分 | 演示只验一个桌面视口，沿用现有响应式 |

“砍掉”指不再为这个 Demo 开发或阻塞发布。已有低成本、正确的代码无需倒拆；例如 QA 指纹、归属校验、完成门已在运行链中，拆除它们反而会新增协议改动与误报风险。本期不关闭 ENFORCE、不把缺证据改成通过，也不为少写测试破坏已实现功能。

### 2.3 真机与测试降标

| 旧门槛 | 本期新门槛 |
| --- | --- |
| P2-W1主动kill、fresh Attempt/reclaim完整闭环 | 不跑；Prompt缺口用现有marker测试确认 |
| P2-W2强制造真实构建失败再自动修复 | 不强制；保留已实现缺口逻辑及聚焦测试 |
| P2-W3强制诱发QA协议重试/隔离矩阵 | 不诱发；正常QA运行中顺手确认已有证据 |
| W1g/ASK/pause整套再次真机 | 不重复；普通需求一次成功，失败显示用已有失败任务或测试验证 |
| 12个case×3轮、P95和§6.4阈值 | 全部移出 Demo，NOT_FROZEN如实保留 |
| 四边界故障注入、恢复率统计、publication kill | 全部移出 Demo |
| 全仓Maven回归、每批重复build/全量证据报告 | Demo阶段只跑本次相关测试，组合候选最后build一次；不宣称全仓全绿 |
| 多任务×390/900/桌面×全链路浏览器证据 | 一个桌面、一个真实普通任务、一个已有失败/空状态样本 |
| 每处改动都重建两镜像 | 没改Pi资源/协议就不重建；改了才按已有同步合同执行 |

以上是用户本轮明确授权的**Demo验收范围缩减**，无需再以旧计划的严格矩阵要求逐项请求放宽。后续若要宣布生产就绪或补齐Phase2，另恢复相应验证；不要在旧文档上把SKIPPED/未执行涂成PASS。

## 3. 候选分支与集成来源

| 角色 | 来源/目标 | 处理 |
| --- | --- | --- |
| 后端候选base | `codex/mea-fresh-executor-episode` 已提交 `9da44a5cacd4cf3eb38a67b390f65c94f064ef43` | 已含读取API与B09–B11代码；它是候选，不是已验证Demo |
| 后端实验工作区 | `/Users/wish233/Documents/RD-Bot-worktrees/mea-fresh-executor-episode` | 保留10个tracked dirty修复和未跟踪harness；不整包带入Demo |
| 前端实现来源 | `/Users/wish233/Documents/RD-Bot/.worktrees/mea-task-workbench`，`codex/mea-task-workbench` | HEAD仍为3fcd7db6，主要实现为未提交改动；逐文件迁入需要的源码/测试 |
| Demo分支 | **`codex/mea-demo`** | 从上述后端候选提交创建，集成前端后修D01问题 |
| Demo工作区 | `/Users/wish233/Documents/RD-Bot/.worktrees/mea-demo` | 新工作区，不覆盖来源worktree |

实施时先重核HEAD与dirty清单，如果来源已继续变化，记录实际采用SHA和文件patch，不自动追入后来实验。前端依赖的已有组件/工具随已审查源码一起迁入；不复制`node_modules`、整个`.git`、缓存或旧静态bundle。前端静态资产由组合候选统一build一次产生。

后端未提交补丁中，deadline刷新、command reopen、立即重调度、Pi lifecycle自动开新Attempt均服务于当前恢复实验，不作为Demo必须项。`RequirementReviewProtocol` 的false-ASK修复可能影响普通流程，可在D02用窄测试证明后单独纳入，不能因想要该修复而把全部dirty代码一起部署。

当前云端jar的 `df3edea3...` 身份来自交接，不能假定它今天仍在运行，也不能把已部署实验包当作本次候选。D03/D04使用同一实际构建物验证；本轮编写方案没有连接云端、停止07l、部署或改任何实验任务。

## 4. 剩余6个任务

本期使用一个集成分支减少组合版本差异；前后端分工仍明确。接手者可以顺序完成，也可由前后端Agent分别负责D01/D02，但不能相互覆盖文件。

| 任务 | 负责范围 | 前置与交付 |
| --- | --- | --- |
| D00 | 集成负责人 | 先固定候选与来源patch；未固定前不开始部署验收 |
| D01 | 前端 | D00后；交付4个文件的接口/关联修复及对应测试 |
| D02 | 后端 | D00后，可与D01并行；交付预算失败与false-ASK的最小处理 |
| D03 | 集成负责人 | D01/D02后；同一构建上的聚焦测试、读取接口与桌面核对 |
| D04 | 演示验收负责人 | D03后；一个普通真实任务和实际交付产物 |
| D05 | 集成负责人 | D04后；可复用入口、版本与最小证据记录 |

### D00：建立一个可追溯的组合候选

**目标：** 结束“前端在一个dirty worktree、后端在另一个实验jar”的验证状态。

**涉及：** 上节三个worktree；新增简短范围记录 `openspec/changes/mea-demo-scope/` 和验收报告 `docs/superpowers/qa/2026-09-08-mea-demo-acceptance.md`。只记录Demo支持/移出项，不再复制25任务大计划。

- [ ] 核对9da44候选含读取API、B09/B10和env预算冻结；记录source SHA、来源dirty清单。
- [ ] 在独立worktree创建 `codex/mea-demo`。不reset/stash/清理来源分支，不改P2原实验结果。
- [ ] 用文件级patch迁入前端已实现的工作台源码与相应测试，新增文件列清单；同路径冲突逐处适配当前后端基线，不整目录覆盖。
- [ ] 把P2完整kill矩阵、B12–B24、12×3及移动专项记录为“移出本次Demo”，不标为功能已完成。

**退出：** 后续编译、接口核对、浏览器和演示均指向同一个候选，来源能追溯。

### D01：修复四个文件中的接口与关联问题

**Files:**

- `frontend/src/services/codingMeaService.ts`
- `frontend/src/pages/admin/rdtask/RdTaskDetailPage.tsx`
- `frontend/src/pages/admin/rdtask/codingMeaModel.ts`
- `frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx`

**测试：** 对应 `codingMeaService`、`codingMeaModel`、`roleDeliverableModel/qaEvidencePresentation` 测试和 `frontend/test/viteProxy.test.ts`；修改现有fixture为真实后端结构，不增加大型mock体系。

- [ ] 把query类型与调用统一为 `codingStageRunId`，保持task+stage请求保护。
- [ ] Manager全文响应从 `boundedContract`读取；列表预览与详情全文分开适配，不把二者当同一个DTO。
- [ ] 审计记录读取真实 `id/text/evidenceRefs`；若内部View使用别名，在服务层显式映射并原样保留引用结构。
- [ ] 去掉第一条decision/role+attempt等无证据fallback；准确关系缺失就显示“当前记录暂无法关联”，不画错循环。
- [ ] QA证据链接优先使用该task响应的`contentUrl`；否则生成带当前taskId的既有路径。一个task的引用不能被换到另一个task。
- [ ] 保留四角色、产物默认、状态常显和原风格；仅展示当前Coding摘要，不为Demo增加历史图/下载新功能。

```ts
// 调用合同：这里的stageRunId来自当前真实选中Coding Attempt。
getCodingMea(taskId, { codingStageRunId: stageRunId })

// QA路径只在对象没有现成contentUrl、且已确认artifact归属时生成。
`/admin/rd-tasks/${taskId}/qa-evidence/${artifactId}/content`
```

**关键断言：** 选择Coding Attempt 1不能返回默认最新Attempt 2；Manager合同不是undefined；AC文本和证据数量来自实际字段；无链接时显示不可用而非自动挑另一轮。

**退出：** 不再需要后端为了前端fixture改API，接口差异在前端适配层收口。

### D02：只补普通路径的失败处理底线

**Files:**

- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementDeliveryEngineTest.java`
- `engine/src/test/java/com/wish/rd/engine/requirement/RequirementReviewProtocolTest.java`
- `exec/src/test/java/com/wish/rd/exec/repair/pi/DockerPiAgentExecutorTest.java`
- 确有缺陷时最小修改 `exec/src/main/java/com/wish/rd/exec/repair/pi/DockerPiAgentExecutor.java` 或对应的 `RequirementReviewProtocol` / engine失败处理；不建新ledger、不改schema。

**已知缺口：** bridge已经输出BUDGET_EXCEEDED，executor的`validateRoleProtocolResult`却把synthetic failure统一转为PI_BRIDGE_PROTOCOL；这证明预算原因在归类时丢失，尚不能直接证明会错误完成或触发产品返工。先用真实synthetic结果结构测到Host，再决定最小修法；不要只构造一个直接送入auditor的理想预算对象来绕过executor。

- [ ] 补BUDGET_EXCEEDED的Host窄断言：不得晋升COMPLETED，不当产品缺陷派发无限Coding修复；输出明确失败/需人工原因。
- [ ] 若现有通用失败路径已正确阻断，只保留可识别的预算耗尽原因并补断言；无需为了名称一致新加一套Auditor blocker模型。
- [ ] 核对 `APPROVED/SUCCESS + advisory missingInformation` 的false-ASK问题；有真实阻断材料缺失仍应阻断，不能一概忽略missingInformation。
- [ ] 若false-ASK修复必须纳入，仅迁入该方法与对应测试；不一起带入command reopen/续租/重调度实验。
- [ ] 已有失败处理满足上述条件时只补验证，不为“完成3.4”新造预算blocker系统或自动恢复机制。

```text
case 1: BUDGET_EXCEEDED → 非COMPLETED，保留原审计进展，无自动产品返工。
case 2: 明确APPROVED且仅建议性补充 → 继续普通流程。
case 3: NEED_INFO或确实缺必需输入 → 明确等待/阻断，不能伪装批准。
```

**退出：** 普通演示不会因建议性字段误卡，真实失败仍诚实结束；不要求JVM kill后恢复。若修改Pi失败归类，按仓库现有要求先追踪profile→executor→bridge，并跑bridge的`npm test`、`DockerPiAgentExecutorTest`及相关Bootstrap配置测试；这些是同一小链路的验证，不恢复完整真机矩阵。仅改Java归类且未改容器资源时，无需重建镜像。

### D03：一次轻量组合回归

**Files:** 既有前端tests、两个读取Controller及测试；可新增短脚本 `deploy/cloud-server/mea-live/verify_demo.py`，只做普通HTTP读取/断言，不包含kill/reopen/故障注入。

- [ ] 运行D01/D02相关测试、typecheck与生产build；只生成一次组合候选的jar和前端bundle，记录hash。
- [ ] 启动该候选后，用已有真实任务验证列表、shell、execution-overview、role-prompts、coding-mea、stage result接口；正常请求返回JSON，不是SPA index.html或500。
- [ ] 只测两个负例：不属于当前task的stage被拒绝；没有完整产物时明确返回不可用/预览，页面不白屏。
- [ ] 浏览器只用一个桌面视口：切两个角色/Attempt、开Prompt、开一条证据、刷新；状态和选择不串，console没有本次页面异常。
- [ ] 新接口字段直接用该候选的真实响应核对；历史前端fixture通过与这次结果分开记录。

```bash
# 从Demo工作区执行。后端仅跑本次涉及的公开读取和普通失败处理测试。
./mvnw -pl bootstrap -am -Dtest=RdTaskCodingMeaControllerTest,RdTaskStageResultControllerTest,RequirementDeliveryEngineTest,RequirementReviewProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test

# D02涉及executor归类时追加此组；bridge npm test在其资源目录执行。
./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl bootstrap -am -Dtest=EngineRequirementExecutionProfileResolverTest -Dsurefire.failIfNoSpecifiedTests=false test

# frontend目录执行；优先现有相关测试，不要求本轮宣称全仓回归。
node --experimental-strip-types --test test/codingMeaService.test.ts test/codingMeaModel.test.ts test/qaEvidencePresentation.test.ts test/viteProxy.test.ts
npm run typecheck
npm run build
```

实施时按迁入源码中的真实测试文件名调整上述前端清单并记录；不能让无匹配测试被当成功。不修改Pi资源就使用既有镜像。没有改状态事务/SQL时不重新跑完整Postgres竞态矩阵，真实服务的普通读写链由D04覆盖。

**退出：** 一个组合版本上的关键页面和读取真实可用；此处没有新创建多轮模型任务。

### D04：一个普通需求的真实演示

**环境/输入：** 固定现有 `codex-run-test-waimai` 项目、一个已可用的模型配置、一个明确且小的需求，例如首页新增唯一标记并完成一个已有交互的小改动。提交前确认需求和材料完整；不故意制造缺页、坏构建、协议失败或中断。

- [ ] 确认候选服务版本与D03一致、运行环境没有本任务harness在反复杀服务；不接管或终止其他人的实验。
- [ ] 新建并提交一条普通需求，观察四角色、Host验证、Manager和QA沿现有流程推进。
- [ ] 在后台打开Coding MEA当前摘要、角色产物、Prompt和一项QA/Host证据；阶段身份一致。
- [ ] 等待本任务实际完成并打开真实PR/交付产物确认；不能只凭四张卡片全绿或Manager DONE算成功。
- [ ] 用一个已有失败任务或D03负例确认错误显示即可，不再另外制造新的真机故障。
- [ ] 若失败，只针对明确的普通流程问题修复并允许一次新任务复验；第二次仍失败则保留Demo未就绪结论，不把它升级为新的长周期恢复矩阵。

**通过条件：** 一条真实普通任务完成、一个实际产物可打开、四角色与Coding内部MEA可看、当前状态/Prompt正确。已有W1e可作为“历史有界返工”附加展示，但必须保留其最终失败状态；无需新跑W1g强制返工来证明本Demo。

**本期不要求：** 杀JVM、重启续做、在线改合同、所有ASK/pause边界、跨任务并发、P95、模型消融、全格式证据手工逐一审计。

### D05：冻结Demo入口与明确未支持项

**Files:** `docs/superpowers/qa/2026-09-08-mea-demo-acceptance.md`；Demo change的任务/范围记录。

- [ ] 写下候选SHA/patch清单、构建hash、环境地址、真实taskId/PR、实际跑过的命令结果。
- [ ] 保存一张桌面总览、Coding摘要和一个证据入口的截图/记录；不再要求全部矩阵证据包。
- [ ] 写一份演示顺序：任务列表→新建/已有任务→四角色产物→Coding MEA→Prompt/状态→QA证据→PR。
- [ ] 明示本期不演示自动崩溃恢复、动态契约、记忆学习和模型自动路由；原P2矩阵保持开放，不伪造旧归档退出。
- [ ] 检查差异只来自本次候选；提交、push、部署/归档依据接手会话实际授权执行，不操作其他工作区。

**退出：** 下一位演示者能直接找到可用版本与任务，不必理解P2 harness或重跑长实验。

## 5. 本期发布判定只有五项

| 判定 | 必须达到 |
| --- | --- |
| 页面 | 支持的桌面流程无白屏、接口字段错误和证据坏链接 |
| 身份 | task/role/Attempt/Prompt/证据一致，不用其他轮次补空白 |
| 执行 | 一条普通真实需求走完现有完成/交付流程 |
| 失败 | 非法引用、缺产物、预算失败有明确结果，不误报成功 |
| 可交接 | 同一候选构建、实际task/PR、最小演示记录齐全 |

全部达到后可写“Demo范围验收通过”。这不是新的RdTaskStatus，也不是“P2完整矩阵通过”或“全平台生产就绪”。凡是已经运行失败的07h/07i/07k等实验，继续保持原结论。

## 6. 给接手Agent的指令

```text
本轮目标已经从完整MEA治理改造，收敛为B端普通流程Demo。
先读本计划、2026-09-08 P2交接、当前RULE/spec；以本计划的新范围排期。
不要按旧交接的“W1 PASS后继续W2/W3”自动恢复完整kill矩阵，也不要跑12×3。
候选后端从9da44已提交版本起，已有coding-mea/stage-result API不用重做。
前端源码在RD-Bot/.worktrees/mea-task-workbench，主要是dirty实现，按文件审阅迁入新codex/mea-demo。
先修codingStageRunId参数、boundedContract/审计字段、QA证据taskId路径及猜测关联。
预算异常只做Host不误报成功的窄验证，false-ASK只带普通路径需要的修复。
不要把P2未提交reopen/deadline/schedule实验整包带入Demo；不要回滚或清理原worktree。
一个桌面、一个真实正常需求、一个已有失败/空状态样本即可。支持能力真实可用后交付。
不开发B12-B24，不新增契约表/回执/预算账本/记忆worker/模型路由；已有核验门和归属保护保留。
旧未PASS项目保留未完成；本轮只声明Demo范围结果。
```

## 7. 本轮核对与未执行范围

来源包括：

- `/Users/wish233/Documents/RD-Bot-worktrees/mea-fresh-executor-episode/docs/superpowers/qa/2026-09-08-mea-p2-w1w2w3-handoff.md`
- 同worktree `openspec/changes/mea-fresh-executor-episode/{tasks,design,proposal}.md` 与delta spec
- 同worktree `docs/superpowers/qa/2026-09-07-mea-p2-slim-b11.md`
- 原 `2026-09-06-mea-backend-next-phases-plan.md` 和 `2026-09-06-mea-task-workbench-frontend-plan.md`
- 两位Luna对实际backend/frontend worktree、代码和本地报告的只读核对

本轮只写新方案、核对本地Git/文档/代码与计划结构。没有重跑测试、连接云端、查询07l当前末态、启动/停止harness、修改业务代码、创建Demo分支、部署、commit或archive。旧交接中的云端时间点与通过记录按原证据范围引用，不能当作本轮服务状态。
