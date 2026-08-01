# 2026-08-01 角色上下文「尚未验证不确定项」历史任务验证报告

## 0. 文档元信息

| 项 | 值 |
| --- | --- |
| 状态 | `EVIDENCE / 历史任务交叉验证` |
| 对应方案 | `docs/superpowers/specs/2026-08-01-role-context-optimization-validation-and-improvement-plan.markdown` 第 12 节 |
| 验证日期 | 2026-08-01 |
| 证据来源 | 管理台 API `http://127.0.0.1:18080` + PostgreSQL `ragent`（docker `postgres`） |
| 仓库 HEAD | `0f91e781bcc51ef888923218d19bb907bfe4c55b`（`main` ahead 63；工作树仍有未提交改动） |
| 方法 | 只读查询历史 MERGED 任务；不改代码、不重跑任务、不写库 |

### 0.1 样本任务（管理台截图中的 4 条）

| taskId | 标题 | 状态 | 项目 |
| --- | --- | --- | --- |
| `7487805148849377280` | `[autopilot:v2:6] Add document notes` | MERGED | Next Js 16 Sqlite Drizzle Orm |
| `7487656533929627648` | `[autopilot:autopilot-local-kbr-20260724-v2:4] 添加文档搜索功能` | MERGED | 同上 |
| `7487668615836209152` | `[autopilot:v2:5] Add document import button` | MERGED | 同上 |
| `7487549960595050496` | `[autopilot:...:1]/[...:3] 添加文档导出功能` | MERGED | 同上 |

四个任务合计：

- `rd_agent_stage_runs`：**38** 条 stage attempt
- `rd_role_context_packages`：**37** 个上下文包
- `rd_rag_retrieval_runs`：**58** 条成功检索（另有空白 role 的早期 run）
- `rd_agent_execution_profile_snapshots`：**37** 条，**全部 `runtime_type=PI`**
- `PROMPT_SNAPSHOT` / `RESULT_JSON`：各 **37**
- `AGENT_EVENTS`：**36**；`AGENT_RUNTIME_META`：**33**
- `HANDOFF_MARKDOWN`：**17**（均指向 `s3://rd-role-handoffs/...`）
- 工作区文件 `file:///tmp/rd-bot/repair-workspaces/<taskId>/...`：**已清理**（目录仅剩 `_role-handoff-skills` / `_qa-skills`），原始 jsonl 只能依赖 DB `content_preview`（事件预览约截断在 **65548** 字符）

---

## 1. 总表（相对方案第 12 节）

| # | 不确定项 | 历史任务验证后状态 | 结论摘要 |
| --- | --- | --- | --- |
| 1 | 工作树未提交，源码可能变化 | **已证实** | 工作树仍脏；文档描述的编排器与 HEAD 不等价 |
| 2 | migration/CAS 能否防语义重复升版 | **部分证实 → 生产侧未观察到重复行，但约束仍缺失** | 四任务无 `(task,role,package_version)` 重复行；evidence 每次重试都变，无法用样本证明「仅 retrievalRunId 导致升版」；**schema 仍无语义唯一约束** |
| 3 | Recorder / Pi / usage·cache 是否启用 | **已用历史任务大幅收窄** | 四任务均启用检索与 **Pi runtime**；usage/cache 字段在 `AGENT_EVENTS` 中存在且 cacheRead 可非零；管理台 overview **未聚合实际 token** |
| 4 | Pi 嵌套 AGENTS/CLAUDE 发现规则 | **样本内无法观察嵌套加载；代码规则仍部分证实** | 33/33 `AGENT_RUNTIME_META` 的 `contextFiles:[]`，无 AGENTS/CLAUDE 加载痕迹 |
| 5 | Claude vs Pi 事件粒度 | **本样本全是 Pi；全局库有 Claude 对照** | 四任务无 Claude；全库 snapshot：`PI=68` / `CLAUDE_CODE=17`。Pi 事件含 TOOL_* / PROVIDER_* / usage；无工具错误 fingerprint、无状态栏 |
| 6 | 真实任务链路与源码是否一致 | **部分证实（高度一致，有可解释偏差）** | Prompt/package/retrieval/profile/handoff/S3 链路齐全；本地 workspace 已删；事件预览截断；overview 无实际 token |
| 7 | 飞书实验数字 / KV Cache 收益 | **仍不能采用为验收数字；但 cache 元数据可测** | 飞书 15/21、60%/95% 无出处；历史事件有 `cacheRead`（含非零），但未做 A/B，不能证明「状态栏优化收益」 |
| 8 | handoff skill 失败降级 | **部分证实** | 成功 stage 多数有 S3 handoff；失败 stage **0** handoff；未见 `bridge-result-metadata`；Prompt 要求 `next_prompt`≤1200；失败主因是协议/验收拒绝，不是 skill 缺失启动失败 |
| 9 | 状态栏注入点与 cache 行为 | **状态栏仍未落地；cache 字段有实测** | 无 `AgentStateSnapshot`；cacheRead/Write 在 TURN/PROVIDER usage 中出现；与「每轮工具后追加状态栏」无关 |
| 10 | facts/manifest 兼容性 | **部分证实** | 历史只使用 `environmentNotes` / `PROMPT_SNAPSHOT` / `RoleContextPackage`；无 `facts[]`、无 input manifest；引入时必须兼容这些消费面 |

---

## 2. 分项详细结论

### #1 工作树未提交风险

**状态：`已证实`**

- 验证时 `git status` 仍显示大量未提交改动（约 139 条 short status）；HEAD 仍为 `0f91e781...`。
- 方案文档已声明：`RequirementAgentStageOrchestrator` 等为工作树行为，不等于已提交 HEAD。
- **对历史任务的含义**：DB 中的 MERGED 任务是**当时已部署运行时**产生的，不保证等于当前脏工作树代码；解读审计时必须区分「历史运行时」与「当前工作树」。

**剩余缺口：** 未还原这 4 个任务执行当日的精确 git SHA / 镜像 digest。

---

### #2 PostgreSQL migration / 唯一约束 / CAS 与并发升版

**状态：`部分证实`**

#### 2.1 Schema（仍不足以防语义重复）

`rd_role_context_packages` 实际约束：

- PK：`id`
- 普通索引：`(task_id, role, package_version)` —— **非 UNIQUE**
- 普通索引：`retrieval_run_id`（部分）
- **无** `(task_id, role, content_hash)` / 语义签名唯一约束
- **无** package 写入 CAS

`rd_agent_stage_runs` 有 `(task_id, role, attempt_no)` 与 idempotency 唯一约束；`rd_rag_retrieval_runs` 有 `uk_rd_rag_retrieval_run_idempotency`。这些保护的是 stage/retrieval，不是 context package 语义去重。

#### 2.2 四任务观测

按 `(task_id, role)` 统计：`versions == packages == retrievals == distinct_hashes`，且 **无** `package_version` 重复行。

对多 attempt 角色比较 `md5(evidence_json)`：

- 每一次新 package 的 evidence MD5 **都不同**
- `same_evidence_diff_retrieval_pairs = 0`

因此：

1. **不能**用这批历史数据单独证明「只因 `retrievalRunId` 变化就升版」（因为证据内容本身也在变——重试后 handoff/风险/材料摘要会变）。
2. **可以**确认生产路径下未出现「同一 version 插两行」的并发症状（样本太小，且通常单 worker 串行跑单任务）。
3. **仍然成立**的工程结论：仅靠现有 migration **无法在多 worker 下保证**语义相同 package 不重复升版。

**剩余缺口：** 需要专门的并发集成测试，或构造「证据字节级相同、仅 retrievalRunId 不同」的受控用例。

---

### #3 Recorder、Pi runtime、上下文窗口 / usage / cache

**状态：`已用历史任务大幅收窄`（配置默认值 vs「这些任务实际发生了什么」已分开）**

| 问题 | 仓库默认（application.yaml） | 这 4 个 MERGED 任务实际 |
| --- | --- | --- |
| Context retrieval recorder | Spring 可装配；生产 plan 开检索 | **已启用**：58 条 `SUCCEEDED` retrieval；每角色 stage 有 `retrieval_run_id` 绑定 package |
| Pi runtime | `rd.executor.agent-runtime.enabled` 默认 `false` | **这 4 个任务全部 PI**（37/37 profile snapshot）；provider=`longcat-anthropic`，model=`LongCat-2.0`，profile 如 `pi-coding-nextjs-kbr` |
| 管理台 token 实际值 | overview 可展示估算 | `tokenBudget.actualAvailable=false`，`finalActualTokens=0`；仅为模型估算 |
| usage/cache metadata | Pi 协议有字段 | `AGENT_EVENTS` preview 中普遍含 `usage.cacheRead` / `cacheWrite`；四任务中 **36/36** 事件产物出现 cacheRead 字段，且 **存在非零 cacheRead**（单文件样本 max=16384，nonzero 次数>0） |
| contextWindow | bridge 默认 200000 | 历史 meta/事件预览中**未**作为一等字段暴露到管理台 overview |

补充：

- 全库 execution profile：`PI=68`，`CLAUDE_CODE=17` —— Pi 在本环境曾被真实使用，不是纸面开关。
- 当前进程 actuator 未暴露 env（404），**不能**据此推断「此刻」开关；只能断言「这 4 个任务执行时 Pi 已启用」。

**剩余缺口：** 无法从 overview API 得到与 provider 账单对齐的聚合 cache hit 率；事件 `content_preview` 截断导致尾部 `RESULT_SUBMITTED`/`AGENT_SETTLED` 常不可见。

---

### #4 Pi SDK 嵌套 `AGENTS.md` / `CLAUDE.md`

**状态：`样本内无法观察；代码规则仍部分证实`**

历史证据：

- 33 份 `AGENT_RUNTIME_META` **全部** `"contextFiles":[]`
- `RESOURCES_LOADED` payload 同样 `contextFiles:[]`，`extensionPaths:[]`
- 无任何 `AGENTS.md` / `CLAUDE.md` 路径进入 runtime meta

含义：

1. 这批 Next.js 任务在 Pi 包装层视角下**没有加载仓库规则文件**（仓库当时可能没有、或被 allowlist 滤掉、或 cwd/root 未命中）。
2. 因此历史任务**不能验证**嵌套发现/排序/合并/覆盖的真实运行表现。
3. 代码层结论（SDK 向上遍历 + 拼接；RD-Bot basename 过滤）仍然有效，但缺少「嵌套多文件仓库」的实测样本。

**剩余缺口：** 需要选用含多层 `AGENTS.md` 的仓库跑一次只读 rehearsal，并核对 `RESOURCES_LOADED` 与 `RuntimeContextManifest`（尚未实现）。

---

### #5 Claude runtime 与 Pi 的事件粒度

**状态：`本样本全 Pi；对照依赖全库 + 代码`**

四任务：

- 事件协议：`rd-agent-event/v1`
- 可见类型（CODING 成功 attempt 预览内）：`RUNTIME_READY`、`RESOURCES_LOADED`、`AGENT_STARTED`、`TURN_*`、`PROVIDER_*`、`ASSISTANT_TEXT_*`、`TOOL_STARTED/PROGRESS/COMPLETED` 等
- 工具名示例：`bash`、`read`、`edit`
- **未出现**：工具错误 fingerprint、`AgentStateSnapshot`、状态栏注入事件
- `USAGE_UPDATED` 独立事件名在 SQL 计数中为 0；usage 嵌在 TURN/PROVIDER 类 payload 的 `usage` 对象中

全库对照：存在 `CLAUDE_CODE` snapshot（17），但**不在**这 4 个 MERGED autopilot 任务中。Claude 路径的粗粒度 trace 结论仍主要来自代码审计，而非这批样本。

**剩余缺口：** 选一个 `CLAUDE_CODE` 历史任务做同口径事件对比；实现工具 fingerprint 后再比双方能力。

---

### #6 真实任务 artifact / bridge event / DB / 对象存储一致性

**状态：`部分证实（源码链路与历史落库高度一致）`**

已对齐的链路：

```text
retrieval_run → role_context_package → stage_run.context_package_id
                 → PROMPT_SNAPSHOT (prompt_artifact_id)
                 → execution_profile_snapshot (runtimeType=PI)
                 → AGENT_EVENTS / AGENT_RUNTIME_META (file:// URI，文件已删，预览在 DB)
                 → RESULT_JSON
                 → HANDOFF_MARKDOWN @ s3://rd-role-handoffs/...（成功路径）
```

管理台可复核：

- `GET /admin/rd-tasks/{taskId}/execution-overview`
- `GET /admin/rd-tasks/{taskId}/role-prompts`（含 prompt preview + context package 字段）

偏差与限制：

1. **workspace 文件已清理**：DB 中 `file://` URI 全部 missing；不能再打开完整 jsonl / session。
2. **事件预览截断**：`AGENT_EVENTS.content_preview` 约 65548 字符，尾部生命周期事件常被切掉——与方案所述「`RESULT_SUBMITTED`/`AGENT_SETTLED` 缺失只证明聚合不完整」一致；历史失败里多次出现精确错误文案 `Pi result requires RESULT_SUBMITTED followed by AGENT_SETTLED`。
3. **Prompt 与 RoleContext usedChars 不等**：例如 reviewer `promptLen≈6.8k` 而 `usedChars≈1.5k`。差额来自角色合同、通用执行器基线、任务/仓库/输出协议等，不全是「材料旁路」。本样本 `# 需求材料` 节本身很短（约 189–259 字符的 MANUAL_TEXT），**不能**用这批任务证明「巨大 contentPreview 全量倾倒」，但**能**证明 Prompt 远大于 RoleContext 预算计数口径。
4. **角色合同冲突已在真实 Prompt 中出现**：所有角色 Prompt 都拼入「你是 RD-Bot 的需求交付执行器。请在受控仓库中完成需求编码、测试，并准备可审查 PR。」——即便 REQUIREMENT_REVIEWER / SOLUTION_ARCHITECT 自身职责写「不修改代码，不创建 PR」。

**剩余缺口：** S3 对象是否仍可读未用 SDK 全量拉取；私有 session 文件（`sessionFile` 在容器 `/work/output/private/...`）未保留。

---

### #7 飞书实验数字与 KV Cache 收益

**状态：`数字仍不可作为已证实事实；cache 元数据可采集`**

- 飞书文档中的「15 vs 21 次」「60%→95%」在仓库与这 4 个任务中**均无对应实验记录**。
- 历史 Pi 事件中的 `usage.cacheRead`：
  - 字段存在；
  - 可为 0，也可非零（样本出现 8576…16384 等）；
  - `cacheWrite` 在抽样中多为 0。
- 管理台 overview **没有**把这些 usage 聚合成任务级 cache hit / 实际 token。
- 因此：**不能**把飞书数字或「静态前缀优化可降本 X%」写成验收门槛；但 Phase 0「先采集 cache/usage」在历史数据上已证明**字段可观测**。

**剩余缺口：** 受控 A/B（同模型、同任务、改/不改 prompt 结构）才能谈收益。

---

### #8 `role-handoff-document` 失败降级

**状态：`部分证实`**

| 观察 | 证据 |
| --- | --- |
| 成功 stage 常有 handoff | `SUCCEEDED` 21 次中 **17** 次带 `HANDOFF_MARKDOWN`；URI 为 `s3://rd-role-handoffs/<uuid>.md`；预览为正常 markdown 交接，非明显 bridge 合成腔调 |
| 失败 stage 无 handoff | `FAILED_*` / `CANCELLED`：**0** handoff |
| 未见 bridge 合成标记 | `RESULT_JSON` preview 中 `bridge-result-metadata` 全为 false（受 20k 预览限制） |
| Prompt 合同 | 明确要求 skill 写 `/work/output/handoff/next.md`，`next_prompt.summary`「最多 1200 字符」 |
| 失败主因 | `AGENT_RESULT_REJECTED`（QA evidence / 验收 / `RESULT_SUBMITTED`+`AGENT_SETTLED` 聚合）等，不是「skill 未安装导致应用起不来」 |

结论修正方案原文的担忧：

- 在这批成功 MERGED 任务上，handoff **多数按设计落到 S3**，下游可消费。
- **稳定降级为结构化 blocker** 仍未形成统一类型：失败表现为 stage `FAILED_NEEDS_HUMAN` + `error_category`，而不是独立 `HANDOFF_BLOCKER`。
- 历史样本**没有**覆盖「skill 文件缺失但仍继续跑并用 `next_prompt` 塞长文」的负面场景；该场景仍依赖代码路径（bridge 可合成 handoff）而非这 4 个成功任务。

**剩余缺口：** 人为制造 skill 挂载失败 / 不写 next.md 的 rehearsal；核对合成 handoff 的 `source` 字段是否曾出现在完整 RESULT（非 preview）。

---

### #9 状态栏注入与 provider cache

**状态：`状态栏未落地（仍不确定实现点）；cache 行为有历史片段`**

- 全任务 Prompt / RESULT / events：**无** `AgentStateSnapshot`、无「状态栏」产物。
- 工具循环后**没有**额外的 Harness 状态消息事件；可见的是正常 TOOL_* / TURN_* / PROVIDER_*。
- cache：见 #7；存在于 usage 对象，**不是**状态栏实验的结果。

**剩余缺口：** 实现状态栏后，必须用同任务对比 cacheRead 曲线与命中率，不能外推飞书数字。

---

### #10 facts / manifest schema 与旧任务兼容

**状态：`部分证实`**

历史任务实际契约：

| 现存字段/产物 | 四任务中 |
| --- | --- |
| `environmentNotes` | 多份 SUCCESS RESULT 含有；QA PASSED 结果常无 |
| `next_prompt` | 与 envNotes 同现于非 QA 成功结果 |
| `facts[]` / `AgentStateSnapshot` / `RoleExecutionInputManifest` | **零** |
| `PROMPT_SNAPSHOT` | 37，管理台 role-prompts 可读 |
| `RoleContextPackage` | 37，含 evidence / maxChars=18000 / usedChars |
| 评测 run 绑定这些 taskId | 本轮 SQL 未找到直接 evaluation_runs 行（表结构无直接 task_id 列或未跑过） |

兼容判断（不变）：

1. **必须兼容**：`environmentNotes` 传播、`PROMPT_SNAPSHOT` 审计 API、`RoleContextPackage` 字段、S3 handoff 元数据。
2. **新 manifest 可 additive**：历史任务没有消费方。
3. **`facts[]` 替换 envNotes** 必须双读/派生，否则下游 handoff 文案与角色合同会裂。

---

## 3. 对改进方案的直接含义

1. **Phase 0（manifest + 度量）优先**仍然正确：历史任务已证明 Pi 事件里有 usage/cache，但管理台与评测未把它变成可验收指标。
2. **D1 语义复用**：历史重试每次 evidence 都变，复用收益要用「同证据不同 retrievalRunId」的受控实验证明，不能从这 4 个任务直接统计「浪费升版次数」。
3. **D2 材料隔离**：本样本材料很短，旁路「炸弹」未爆；但通用执行器基线污染评审/架构 Prompt **已在真实任务爆发**，应优先修 renderer（对应方案 Phase 1.4）。
4. **D4 仓库规则文件**：这批任务 `contextFiles` 为空，说明「默认 root-only」改动对它们近乎无行为差；真正风险在有多层 AGENTS 的仓库，需要另选样本。
5. **D6 Pi 先行**：这 4 个 autopilot 任务已经是 Pi 生产路径，状态栏/manifest 灰度以 Pi 为先与历史运行态一致。
6. **失败叙事**：`RESULT_SUBMITTED`/`AGENT_SETTLED` 聚合失败在真实 CODING/QA attempt 中反复出现；上下文优化不应把该错误解读成 event ordering 违规（与 AGENTS.md / 方案第 3 节一致）。

---

## 4. 建议的复现查询

```bash
# 列表
curl -s 'http://127.0.0.1:18080/admin/rd-tasks?status=MERGED&page=1&pageSize=20'

# 单任务
TID=7487805148849377280
curl -s "http://127.0.0.1:18080/admin/rd-tasks/$TID/execution-overview" | jq .
curl -s "http://127.0.0.1:18080/admin/rd-tasks/$TID/role-prompts" | jq '.stagePrompts[] | {role,attemptNo,status,promptLen:.prompt.contentLength,used:.context.usedChars,max:.context.maxChars}'

# DB
docker exec postgres psql -U postgres -d ragent -c "
SELECT runtime_type, count(*) FROM rd_agent_execution_profile_snapshots
WHERE task_id IN (7487805148849377280,7487656533929627648,7487668615836209152,7487549960595050496)
GROUP BY 1;
"
```

原始导出（本机验证缓存，可不入库）：`tmp/role-context-validation/`。

---

## 5. 仍无法仅靠历史任务闭合的项

1. 多 worker 下语义 package 并发双写（需压测）。
2. 嵌套 `AGENTS.md` 真实加载列表（需换仓库样本）。
3. Claude 与 Pi 同任务事件对照（需 `CLAUDE_CODE` 样本）。
4. 飞书 15/21、60%/95%（无实验源）。
5. 状态栏注入点（未实现）。
6. workspace / 完整 event jsonl / 私有 session（已清理）。
7. 当前进程是否仍开启 `RD_EXECUTOR_AGENT_RUNTIME_ENABLED`（actuator 不可用；与历史任务无关）。

---

## 6. 一句话裁决

这 4 个 MERGED autopilot 任务证明：**Pi + 检索 + RoleContext + Prompt 快照 + S3 handoff 的主链路在真实交付中跑通**；同时证明方案指出的 **角色合同与通用编码/PR 指令冲突、RoleContext 预算口径与真实 Prompt 脱节、usage/cache 未进入管理台真值、状态栏/facts/manifest 尚未落地、package 层无语义唯一约束** 都不是纸面想象。飞书外部实验数字与嵌套 AGENTS 行为仍不能从这批任务证实。
