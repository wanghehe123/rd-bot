# MEA 后端下一阶段进度交接（2026-09-07）

日期：2026-09-07  
用途：交给下一个 Agent；**目标与任务定义以原文为准**，本文只整理进度与证据路径，不改写退出条件。

来源分级（对齐 `docs/openspec/historical-spec-provenance-audit.md` 方法）：

| 材料 | 分级 | 用法 |
| --- | --- | --- |
| `docs/superpowers/plans/2026-09-06-mea-backend-next-phases-plan.md` | PLAN_OR_DECISION | **原始任务/批次/退出条件** |
| 各 `openspec/changes/*/proposal.md` + `tasks.md` | 实施合同跟踪 | 勾选状态以 change `tasks.md` 为准 |
| `docs/superpowers/qa/2026-09-06-*` / `2026-09-07-*` | 真机/HTTP 证据 | 可复核，不得抬升为未跑过的 R0 |
| 计划文件内大量 `- [ ]` | **未随实施回写** | 勿把计划未勾选误读成「未做」；以 change tasks 与 QA 为准 |

---

## 0. 给下一个 Agent 的入口

```text
工作区（独立 worktree，勿混提交）：
- /Users/wish233/Documents/RD-Bot-worktrees/mea-coding-read-model     分支 codex/mea-coding-read-model @ e6715838
- /Users/wish233/Documents/RD-Bot-worktrees/mea-baseline-evaluation   分支 codex/mea-baseline-evaluation @ 82ba6d50
- /Users/wish233/Documents/RD-Bot-worktrees/mea-delivery-outcome-metrics 分支 codex/mea-delivery-outcome-metrics @ 80e475fe
- /Users/wish233/Documents/RD-Bot-worktrees/mea-p3-live-closeout      分支 codex/mea-p3-live-closeout @ 984336c5（工作区仍有未提交变更）

云端：ubuntu@106.55.13.166
- 当前 JVM：java -jar .../bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
- 当前 jar SHA-256：bb88f2eb97ba88265919c2e4cf28701baf63c520d5ac4819a294bacbc7f412d0
  （含 CRM + B08 cherry-pick + OpenCode x-opencode-session 注入）
- 项目：codex-run-test-waimai / MEA_EVAL_PROJECT_ID=7499721648870920192
- 日志：/tmp/rd-bot-backend.log ；启动：deploy/cloud-server/start-backend.sh（按 /proc cmdline 杀 jar，禁止 pkill -f）

必读原文（顺序）：
1. 本文件
2. docs/superpowers/plans/2026-09-06-mea-backend-next-phases-plan.md
   （Goal、Global Constraints、§2 分支表、§5–§6 B00–B09、§10 开工指令）
3. docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md（完整范围 Task 9–15；P3-W1 退出以 Task 11 原文为准）
4. RULE.md + AGENTS.md
5. 各 change：mea-p3-live-closeout / mea-coding-read-model / mea-baseline-evaluation / mea-delivery-outcome-metrics
6. QA：2026-09-06-mea-p3-live-closeout.md、2026-09-07-mea-coding-read-model.md、2026-09-07-mea-b07-slim-b08.md

不要：
- 未经用户授权 git commit / push / archive / 全量 openspec validate --all
- 把 slim C06 或 W1/W2/W3 单例写成 12×3 R0 或 §6.4 过线
- 恢复 BugFix、评测控制台、仅模型 HTTP、Claude 路径（计划 Global Constraints）
- rsync .env.opencode.local / application-local.yaml
- 续跑 DEAD_LETTERED；为证明 ASK 假完成不要伪造 DB
- 未开工就宣称 B09+「已有 AuditedGapSection 两处调用」= Phase2 全完成（计划 B09 原文禁止）
```

---

## 1. 原始总目标（计划原文）

摘自 `docs/superpowers/plans/2026-09-06-mea-backend-next-phases-plan.md`：

> **Goal:** 先验证当前 P3 修复的真实运行闭环，补齐 Coding 内 MEA 与角色完整产物读取，再按完整方案推进基线、Fresh Executor、稳定契约、预算恢复、审计记忆与模型路由。

> **Architecture:** 保持 PostgreSQL 状态/command/审计/发布账本为真值；Manager 仍是 Host 纯策略，Executor 结果仍为声明，只有 Host 审计写回可晋升验收记录。只读展示与业务治理使用独立 change/分支；前者查询既有数据，后者逐阶段修改已有执行与事务边界，不新建第二套调度器。

> **Spec:** `docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md` 为完整任务范围；`docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md` 的 K5–K8、阶段 0–7、§6.4 为已冻结决策/阈值。

计划 §10「给后端 Agent 的开工指令」原文：

```text
在 /Users/wish233/Documents/RD-Bot 实施本计划，每批独立worktree与第2节指定分支。
先读RULE.md、当前主spec、P3交接/W1e证据、本计划与完整原计划；不要按旧header重做Phase1/P3。
先B00-B03：核对构建物、部署新版、W1g完整交付、W2 ASK与W3pause真机；部署/故障注入遵循接手会话授权。
P3-W1已由W1e满足原退出条件，W1g是新版本闭环验证，不能把两者混写。
随后B04-B06交付Coding内MEA读取和stage完整结果给前端；先锁定DTO，不修改调度来迎合页面。
四角色仍是完整流程；Coding内MEA只引用既有QA Attempt/Host审计，不创造新角色。
Phase2已有AuditedGapSection与两处previousFailure反馈，先核对其余checkpoint入口和未真机项。
Phase4-7按B12-B24逐批delta实施；保持Host完成权、版本/fence/operation/reconcile边界。
严禁把resultPreview当完整产物、把current head当旧Manager的状态、把命令重试次数当Agent Attempt。
不要接管其他任务的P3归档/未提交文件，不恢复已下线评测控制台，不复制secret，不盲重跑远端副作用。
交接必须列出实际测试/HTTP/数据库/真机证据与缺项；未通过的任务保持未勾选。
```

计划 §2 交付顺序（原文表摘要）：

| 批次/分支 | OpenSpec change | 交付目标 |
| --- | --- | --- |
| `codex/mea-p3-live-closeout` | `mea-p3-live-closeout` | B00–B03：新版真机及必要的最小修复，不重做P3 |
| `codex/mea-coding-read-model` | `mea-coding-read-model` | B04–B06：前端只读依赖，可先于长阶段合入 |
| `codex/mea-baseline-evaluation` | `mea-baseline-evaluation` | B07及B08脚本部分 |
| `codex/mea-delivery-outcome-metrics` | `mea-delivery-outcome-metrics` | B08生产指标口径；不恢复评测UI |
| `codex/mea-fresh-executor-episode` | （计划名） | B09–B11 |
| … | … | B12–B24 见计划 §7 |

**尚未创建** `mea-fresh-executor-episode` worktree。

---

## 2. 批次原文目标 + 当前进度

说明：下表「计划退出」摘自计划各节；「change 勾选」摘自对应 `tasks.md`；「证据」指向已有 QA 文件/路径。

### 2.1 B00–B03（`mea-p3-live-closeout`）

**Change Why（proposal 原文）：**

> P3 Manager（`MANAGER_DECIDE` / `MANAGER_GAP_FIX` / `WAITING_USER_INPUT` / pause claim 守卫）已在源码 `3fcd7db6` 落地，且 W1e 已证明三角色有界缺口闭环；但**含 QA 阶段 SUCCEEDED 与 ENVIRONMENT 守卫的同一构建物**尚未完成 W1g 完整交付与 W2/W3 真机。本 change 冻结验证边界并产出可复验证据，避免把历史只读证据或单测绿当成新版闭环。

**计划退出（原文）：**

- B00：后续任务能准确指出验证的是哪个jar/镜像/项目/输入；无含糊“最新环境”。
- B01：服务启动成功且实际构建包含两项修复；没有把“package成功”写成“真机闭环成功”。
- B02：原P3-W1局部闭环+新修复版本完整交付均有证据，普通成功任务不能替代该退出。
- B03：W2/W3真实入口、等待期间claim守卫和恢复链都有证据；B01–B03失败时后续业务阶段不据“单测通过”跳过该批门槛。

**tasks.md：** 1.1–5.1 已勾；**5.2 未勾**（「交接：实际证据路径、未验证项、是否授权 commit/archive」）。

**证据：** `mea-p3-live-closeout/docs/superpowers/qa/2026-09-06-mea-p3-live-closeout.md`

| 探针 | 报告结论 | taskId / 路径 |
| --- | --- | --- |
| W1g | PASS | `7502261872498970624`；PR #39；VM `/tmp/mea-p3-w1g/` |
| W2 | PASS（W2B；W2A 不计） | `7502311153071165440`；`/tmp/mea-p3-w2b/` |
| W3 | PASS（W3J） | `7502300444828504064`；`/tmp/mea-p3-w3j/`；热修 jar `cff00cc5…` |

**工作区注意：** closeout worktree **仍有大量 staged/未提交文件**（mea-live 探针、部分 bootstrap/engine 改动、计划/QA 等）。下一 Agent 勿假定已全部 commit；是否提交须用户授权。

**后续相关提交（CRM 谱系，非 closeout tip）：** `984336c5 fix(requirement): route reviewer NEED_INFO to Manager ASK` —— 对应 W2A「评审 NEED_INFO」失败面的产品修复；真机 ASK 另有 C06 证据（见 §2.4）。

---

### 2.2 B04–B06（`mea-coding-read-model`）

**Change Why（proposal 原文）：**

> 前端任务工作台需要在 Coding 内展示 MEA 因果邻域与完整角色结果，但当前管理端只有四角色 overview 的 `resultPreview` 与审计页，没有按 task/Coding stage 聚合的只读快照，也没有从已落盘 finalization 读取完整 RESULT_JSON 的入口。本 change 冻结并实现只读合同，不改调度与完成权。

**What Changes（proposal 原文要点）：**

- `GET /admin/rd-tasks/{taskId}/coding-mea`
- `GET /admin/rd-tasks/{taskId}/manager-decisions/{decisionHash}`
- `GET /admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/result` 与 `/result/content`
- fixture `fixtures/coding-mea-v1.json`；无新业务表、无调度写路径

**计划退出（原文）：**

- B04：接口合同可被前后端分别实现，fixture不含运行secret，不谎称是真实API响应。
- B05：精确身份与一致快照通过单元和真实Postgres读取测试，无新增写路径。
- B06：前端F02–F10不再需要模拟生产数据；API只有读能力，默认payload不含Prompt/结果/事件正文。

**tasks.md：** 1.1–3.4 **全部已勾**（含真实 HTTP 与 `openspec validate mea-coding-read-model --strict`）。

**证据：** `mea-coding-read-model/docs/superpowers/qa/2026-09-07-mea-coding-read-model.md`  
当时部署 jar `04205367…`；W1g/W2B/W3J/W1e/C06 的 `coding-mea` 均为 200；跨 task stage → 404；RR smoke：`PostgresCodingMeaRepeatableReadRealSmokeTest`（`-Drd.integration.coding-mea.enabled=true`）。

**分支 tip：** `e6715838`（在 CRM 之上又合入 B08 cherry-pick `bb7ec6db`、session 修复与 QA 文档）。

---

### 2.3 B07（`mea-baseline-evaluation`）

**Change Why（proposal 原文）：**

> MEA 下一阶段比较需要独立、可重复的 waimai 基线任务集和正确的成功分母。现有 W1/W2/W3 单例不能当 R0。生产观测 SUCCESS 口径由并行 change `mea-delivery-outcome-metrics` 修正；本 change 只提供离线评测脚本，不恢复评测产品页面。

**计划 B07 原文要求（摘要，完整见计划 §6）：**

- 创建 change；首次新比较前写入原总体方案 §6.4 阈值；历史结果须披露。
- 至少 12 例（多文件、路由页、假完成、下单回归、晚到约束、需要输入/审批、执行中断、finalize 中断、过时记忆、checkpoint、错误分支、非目标保持）；独立 hidden checker。
- `run_cases.py` 真实 create/submit/poll；同组至少 3 次；分类 PRODUCT/AGENT/INFRA/HARNESS。
- **R0 边界原文：**「现有W1/历史PR单例不是12例×3的R0。」「模型/版本/镜像无法复现时标不可比较；不据此填写R0/R1/R3阈值通过。」

**thresholds.json 冻结值（与计划 JSON 一致）：**

`falseCompleteReductionMin=0.50`、`injectedFailureRecoveryRateMin=0.80`、`verifiedProgressLossRateMax=0`、`medianCostPerSuccessRatioMax=1.8`、`managerTokenShareMax=0.10`、`unauditedClaimPromotedToCompletedMax=0`、`auditorMutatesProtectedStateMax=0` 等（见 `deploy/cloud-server/mea-eval/thresholds.json`）。

**tasks.md：**

| 项 | 状态 |
| --- | --- |
| 1.1–1.2 阈值与披露 | [x] |
| 2.1–2.3 十二例与 checker | [x]（`cases.json` 现含 c01–c12） |
| 3.1–3.4 harness + unittest | [x] |
| **4.1 隔离窗口跑 12 例 × 3，保留失败，写 QA 报告** | **[ ]** |
| **4.2 可比身份齐全时填 R0** | **[ ]** |

**Harness 行为（代码，非计划原文）：** `--profile` 默认 **`slim`** = 仅 `c06_needs_input`；全量需 `--profile all`。另：`is_provider_infra_failure` 将 `MissingSessionID` / Pi lifecycle 聚合标为 INFRA（commit `82ba6d50`）。

**计划要求的 QA 报告路径：** `docs/superpowers/qa/2026-09-06-mea-baseline-evaluation.md` —— **尚未按 12×3 写成 R0 报告**；slim 证据在 `2026-09-07-mea-b07-slim-b08.md`。

---

### 2.4 B08（`mea-delivery-outcome-metrics` + eval `summarize.py`）

**Change Why（proposal 原文）：**

> 生产交付观测把 `COMMITTED` 和 `MERGED` 都算进 SUCCESS 分子。这把「PR 已存在 / 已合入」当成了任务完成。冻结 MEA 方案决策 5 / 第 7 节协调表要求：成功只承认 `COMPLETED`，或 `MERGED` 且 `rd_task_status_events` 含 `COMPLETED`。`COMMITTED`、`WAITING_USER_INPUT`、`WAITING_APPROVAL` 是在途。

**计划 B08 原文口径：**

> 当前 `SUCCESS={COMPLETED,COMMITTED,MERGED}` 改为：COMPLETED成功；COMMITTED在途；MERGED只有历史存在COMPLETED事件才计成功。  
> WAITING_USER_INPUT与WAITING_APPROVAL属于在途，前者不计SUCCESS/FAILURE/TERMINAL；保留暂停作为独立标志。

**tasks.md：** 1.1–4.1 **全部已勾**（含 `DeliveryObservabilityQueryServiceTest`、RULE 追加、不归档 `upgrade-delivery-observability`、不改评估 UI）。

**合入 CRM 运行线：** cherry-pick 为 `bb7ec6db`（源 commit `80e475fe`）。  
**Live：** `GET /admin/observability/delivery/overview?window=7d` → 200（路径以代码为准，不是 `/admin/delivery-observability/...`）。

**计划退出原文：**「退出：R0/R1/R3含相同分母规则、样本身份和不可比原因。」—— **R0/R1/R3 比较尚未产生**；仅口径与 slim 汇总器已可用。

---

### 2.5 Slim C06 真机（支持 B07，**不是** R0）

证据文件：`docs/superpowers/qa/2026-09-07-mea-b07-slim-b08.md`

| Output | taskId | systemStatus | hiddenPass | 备注 |
| --- | --- | --- | --- | --- |
| `/tmp/mea-eval-slim-c06-needinfo` | `7502550384997699584` | `WAITING_USER_INPUT` | true | 较早 ASK 证明 |
| `/tmp/mea-eval-slim-b07b08` | `7502581502434217984` | `FAILED_NEEDS_HUMAN` | false | OpenCode 400 `MissingSessionID` |
| `/tmp/mea-eval-slim-b07b08-r2` | `7502582150533877760` | `FAILED_NEEDS_HUMAN` | false | 同上 |
| `/tmp/mea-eval-slim-b07b08-sessionfix` | `7502611853802082304` | `WAITING_USER_INPUT` | true | session 修复后；relay 9×200 |

**非计划批次、但阻塞 slim 的修复（CRM `e6715838`）：** Host `PiCredentialRelayService.ensureOpenCodeRoutingHeaders` 对 OpenCode 注入稳定 `x-opencode-session`（`stageRunId`→`taskId`）与 `User-Agent=rd-bot-pi-relay/1.0`。依据 OpenCode Go 文档「Send a stable session ID in `x-opencode-session`」；RULE 已追加对应【强制】。

---

### 2.6 B09 及之后（**未开工**）

计划 **B09 标题原文：**「补齐所有恢复入口的已审计缺口Prompt」

计划 **当前差异原文：**

> 两处 `previousFailureFeedbackSection` 已读取head/lastRun并调用 `AuditedGapSection.render`，后者已有MAX_IDS=16/MAX_CHARS=2000，不读取旧errorMessage。旧完整计划的“新增该能力”步骤不可原样执行。`recoveryPromptSection/downstreamFailureFeedbackSection` 是需继续核验的另一条checkpoint入口。

计划 B09 **退出原文：**

> 退出：测试覆盖实际所有恢复入口，不能只凭两处方法已改就宣称Phase2全完成。

后续 B10–B24 仍以计划 §6–§7 原文为准；**无 worktree / 无 change 实施证据。**

---

## 3. 明确不声称（忠于计划与 QA）

| 不声称 | 依据 |
| --- | --- |
| 12 例 × 3 的 R0 已跑 / §6.4 阈值已通过 | B07 tasks 4.1/4.2 未勾；计划 R0 边界 |
| slim C06 = 基线通过 | 计划：W1/历史单例不是 R0；slim 仅 `c06` |
| B08「退出：R0/R1/R3 同分母比较」已完成 | 仅口径与 overview/summarize 就绪 |
| closeout 已授权 archive / 5.2 交接勾选 | tasks 5.2 仍 `[ ]` |
| 计划文件内 B04–B08 的 `- [ ]` 表示未做 | 计划未回写；以 change tasks + QA 为准 |
| Phase2 / B09 已完成 | 计划明确禁止仅凭两处 renderer 宣称完成 |
| 前端 F01–F10 本分支已交付 | 计划：前端独立分支 `codex/mea-task-workbench`；CRM proposal「本 change 不改前端布局」 |

---

## 4. 关键路径速查

| 类型 | 路径 |
| --- | --- |
| 实施计划 | `docs/superpowers/plans/2026-09-06-mea-backend-next-phases-plan.md` |
| 完整范围计划 | `docs/superpowers/plans/2026-09-04-mea-next-phases-waimai.md` |
| 冻结阈值/决策 | `docs/superpowers/specs/2026-09-03-rd-bot-mea-transformation-plan.md`（§6.4 等） |
| Closeout change / QA | `openspec/changes/mea-p3-live-closeout/`；`docs/superpowers/qa/2026-09-06-mea-p3-live-closeout.md` |
| Coding MEA change / QA | `openspec/changes/mea-coding-read-model/`；`docs/superpowers/qa/2026-09-07-mea-coding-read-model.md` |
| Baseline change / harness | `openspec/changes/mea-baseline-evaluation/`；`deploy/cloud-server/mea-eval/` |
| Metrics change | `openspec/changes/mea-delivery-outcome-metrics/`（metrics worktree；CRM 含 cherry-pick） |
| Slim+B08 QA | `docs/superpowers/qa/2026-09-07-mea-b07-slim-b08.md` |
| 更早 P3 Manager 交接 | `docs/superpowers/qa/2026-09-06-mea-p3-manager-handoff.md` |

---

## 5. 建议下一 Agent 选项（须用户点名，本文不擅自开跑）

以下均来自计划未完成项或未勾 tasks，**不是新发明目标**：

1. **B07 tasks 4.1/4.2**：隔离评测窗口 `--profile all --repeats 3`，写 `2026-09-06-mea-baseline-evaluation.md`；仅可比时填 R0。  
2. **B09**：按计划创建 `mea-fresh-executor-episode` change/worktree，先列已实现/剩余/未真机三清单。  
3. **closeout 5.2**：用户授权后整理 commit/archive；处理 closeout dirty 工作区。  
4. **推送/合入**：各分支目前以本地 tip 为主；是否 push/PR 须用户授权。

---

## 6. 本交接文档自身边界

- 本文 **不** 修改计划 Goal、批次退出条件或 OpenSpec proposal。  
- 勾选状态摘录自 2026-09-07 各 worktree 内 `tasks.md`；若后续有人改勾选，以仓库文件为准复核。  
- 计划正文内 `- [ ]` **未**批量改为 `[x]`；实施跟踪以 change `tasks.md` + QA 证据为准。
