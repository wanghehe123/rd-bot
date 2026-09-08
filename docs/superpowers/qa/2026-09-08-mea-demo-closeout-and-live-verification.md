# MEA Demo 两项收口与真实业务验证记录

日期：2026-09-08。分支：`codex/mea-demo`。HEAD：`f3e7bcc93285420cf5a320d242f2ec9c50fbe342`，修复尚未提交。

**最终结论：Demo 范围验收通过。两项源码收口、真机追加发现的前端读取修复、最终部署及页面复验均完成。普通任务 7502920392433078272 已 COMPLETED，PR #40 已核验，两个 AC 经 QA 与宿主审计完成，23 条 QA 证据可从默认页直接读取。D00–D05 在本次精简 Demo 范围内收口；不代表完整 MEA/P2/P3 治理计划全部完成。**

范围合同：`openspec/changes/mea-demo-audit-scope-verification/`。不恢复 P2 kill 矩阵、12×3 或 B12–B24 治理功能。

## 1. 本轮修复

### 任务最新审计归属

- 页面明确使用“任务最新审计”，每条记录显示真实 `sourceStageRunId`；缺来源显示“未记录来源”。
- 所有 `blocking && status != COMPLETED` 记录按来源分类：精确属于所选阶段的进入该 Attempt 的问题；异轮或未知来源进入单独的任务当前阻断区。
- 异轮记录不会冒充历史 Attempt 的结果；PENDING、BLOCKED、UNTRUSTED 均被保留。
- 即使没有自报或已完成检查、只有任务当前阻断，审计区域仍显示。
- 不新增历史快照 API，不修改审计写入或状态机。

文件：`frontend/src/pages/admin/rdtask/roleDeliverableModel.ts`、`frontend/src/components/admin/rdtask/RoleDeliverablesPanel.tsx`、对应模型测试。

### HTTP 验收断言

- 正例必须 HTTP 200，且 task/stage/decision 身份与请求一致。
- 跨任务、非法阶段和不可用全文的拒绝严格要求 HTTP 404；400/401/403 不能替代归属验证。
- HTTP 404 正文即使带 `source`，也不能被正例判为 PASS。
- 列表接口失败如实记录失败，不转换为空数据；缺少测试对象时显式 SKIP/unverified。
- 真实历史任务不一定在列表第一页；校验合法分页 DTO，不错误要求当前页含目标任务。该问题先在真实旧任务上复现，再补第 8 个回归。

文件：`deploy/cloud-server/mea-live/verify_demo.py`、`test_verify_demo.py`。

## 2. 实际完成的本地验证

| 验证 | 结果 | 边界 |
| --- | --- | --- |
| 先红后绿：异轮与无来源记录 | 通过 | 旧逻辑污染 Attempt 缺口，新逻辑隔离 |
| 先红后绿：BLOCKED/UNTRUSTED | 通过 | 非 COMPLETED 阻断均保留 |
| 前端相关测试 | 26/26；主代理另复跑 16/16 | 全文读取、过期响应、结果可用性、审计模型 |
| 前端全量 Node 测试 | 247/247 | 最终前端源码；非浏览器真机结果 |
| TypeScript typecheck | 通过 | 最终源码 |
| 生产前端 build | 通过 | 最终产物已进入 static/admin |
| Python HTTP 回归 | 9/9 | 本地 HTTP stub，非云端业务数据 |
| Python 编译检查 | 通过 | pycache 在 /tmp |
| OpenSpec 全量 strict | 21/0 | 规范结构，不是运行时证明 |
| Java 21 离线 package | BUILD SUCCESS | 使用 -DskipTests，不能声称此命令跑了 Java 测试 |
| 独立 Luna 最终复审 | 指定范围无阻断 | 两个追加边界已复核 |

Python 测试包括：真实形状双任务通过、单任务明确 SKIP、错误身份失败、HTTP 404 成功形状正文失败、400/401/403 错误拒绝失败、列表失败原因明确。初版修复前有 5 项失败；随后补真实分页和 QA 空合同回归，最终 9/9 通过。

实际命令：

```bash
# frontend 目录
node --experimental-strip-types --test test/*.test.ts
npm run typecheck
npm run build

# worktree 根目录
python3 deploy/cloud-server/mea-live/test_verify_demo.py
PYTHONPYCACHEPREFIX=/tmp/mea-demo-pycache-final python3 -m py_compile deploy/cloud-server/mea-live/verify_demo.py deploy/cloud-server/mea-live/test_verify_demo.py
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw -o -pl bootstrap -am -DskipTests package
git diff --check
```

本地 HTTP 测试首次普通沙箱执行因 loopback bind EPERM 失败；允许该本地测试后重跑 9/9，通过的是实际重跑结果。上一轮同 Java 源码的 107/107 回归不作为本轮新执行命令；本轮没有改 Java。

## 3. 组合构建与版本边界

### 真实业务执行包

- jar：`bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar`
- SHA-256：`1bc13039ada5953e69af216a7ef381e374c74422c74f6941d7eb4aed7d17eecb`
- 大小：106783408 bytes。
- 生成核验时间：2026-09-08T02:32:06Z。
- jar 内 50 个前端静态资源与最终工作区文件逐字节一致。
- jar 内 `RequirementReviewProtocol.class` 与当前编译类一致，包含显式 NEED_INFO 优先修复。
- verify 脚本 SHA-256：`130b951c3aed44da2a8b48b041ad6e15a349b9803826618c853efd4c3bcd1650`。
- 旧 `a93fd28b…` 为修复前构建；`732a77b4…` 为补充两个边界前的中间包，均不得作为最终候选部署。
- 构建日志与机器可读核验：`/tmp/mea-demo-closeout/package-final.log`、`frontend-build-final.log`、`build-manifest-final.json`。

追加取消图标修复后的包 SHA-256 为 `5a3b0621f98b6868393b6d404b7f86602c8fc4284c6f0def1a0df68d41c10abf`（106783425 bytes）。50 个静态资源与源码构建一致。与 1bc13039 包逐项解包对比，57,294 个非前端内容项无差异，证据为云端 `backend-byte-comparison.json`。用户单独明确授权后上传至 `/tmp/mea-demo-closeout/bootstrap-ui-final.jar`，在真实任务 COMPLETED 后切换成功，PID 3517573；任务 API 与管理台入口均 HTTP 200，取消图标已真机复验。日志为 `frontend-build-ui-final.log` / `package-ui-final.log`、`deployment-ui-final.json` / `deployment-ui-final-health.json`。

### 默认产物读取修复包（最终已部署）

- SHA-256：`d707cdafa1d470a21f193131fef49323098bc3650459b53efd4243f1f0c56de4`，106783919 bytes，2026-09-08T03:54:30Z 核验。
- 50 个前端资源与 build 输出一致；相对已部署 5a3 包，57,294 个非前端内容项完全一致。
- 默认产物页独立加载 QA evidence；所选阶段已有 resultPreview 或 SUCCEEDED 时自动读取结果，截断 FINALIZATION_RESULT 沿同一身份补读 `/result/content`。
- metadata/content 两阶段都丢弃过期响应；状态/preview 变化后重新读取。reset state 提交后才尝试新签名，cleanup 清除加载标记，避免旧闭包或 StrictMode 清理导致漏读。
- 加载时显示正在读取，失败显示明确不可读原因；保留原风格、按 stageRunId 过滤与下载接口，不扩展后端。
- 最终日志：`/tmp/mea-demo-frontend-node-tests.log`（247/247）、`/tmp/mea-demo-frontend-typecheck.log`、`/tmp/mea-demo-closeout/frontend-read-focused-root.log`（16/16）、`frontend-build-read-final.log`、`package-read-final.log`、`build-manifest-read-final.json`。
- 用户明确授权该具体 SHA 的上传、切换和复验后，重新确认普通任务 COMPLETED、旧 07l CANCELLED 且 paused=true、仅基础容器运行。备份 5a3 包，精确 TERM 旧 PID 3517573 后原子替换，启动成功。当前服务 PID **3542622**、实际 jar hash **d707cdaf…**；管理台与任务 API 均 HTTP 200。原始记录为 `deployment-read-final.json`、`deployment-read-final-health.json`、`preflight-read-final.json`。
- 普通业务完整执行发生在 1bc 包；后续两包只改前端且非前端内容逐项一致。最终 d707 包已重新验证真实接口和浏览器读取，不伪称在它上面又运行了一条业务。

## 4. 已授权的云端切换

用户明确回复“授权以上全部操作”，覆盖最终候选及脚本上传、指定旧 07l 的 pause/stop、无其他实际执行时切换 8080、创建并执行一条普通 Demo 需求。

- 旧任务 `7502759980530012160` 的 pause、stop 均 HTTP 200；状态为 CANCELLED、paused=true、version=12、fencingToken=13，保留 EXECUTION_STOP_REQUESTED / EXECUTION_STOPPED 审计。
- 其 command `7502761254046535680` 留有过期 RUNNING 账本记录；无运行 Pi 容器或 harness。没有直接修改 command 或数据库。取消且暂停的任务不应被新实例继续 claim。
- 切换前只有 postgres/redis/minio 基础容器；唯一 8080 JVM 为 PID 2912673。精确 TERM 后确认端口释放，再原子替换候选 jar。
- 旧 `df3edea32bdbdf21318caafc2342be72cf77e2955e4997a5af4202645171ac99` jar 完整备份在云端 `/home/ubuntu/rd-bot-rollbacks/<sha256>.jar`。
- 沿用既有 `deploy/cloud-server/start-backend.sh`；启动成功，新 PID 3470616，8080 任务 API HTTP 200；运行 jar 校验为 `1bc13039…`。
- `/admin/` 和未暴露的 `/actuator/health` 返回 404；管理台正确入口 `/admin/dashboard` 返回 200 HTML，不将错误入口误记为启动失败。
- 原始记录：本机及云端 `/tmp/mea-demo-closeout/deployment.json`、`deployment-health.json`、`control.json`。

早期上传中间包曾被自动审批拒绝（私有代码目的地缺明确授权），没有绕过；本轮明确授权后，最终包与脚本已正常上传，该阻塞已解除。

## 5. 同候选真实接口与桌面验证

### 实际业务读取

在运行 `1bc13039…` 的服务上执行：

```bash
RD_BOT_BASE_URL=http://127.0.0.1:8080 python3 /tmp/mea-demo-closeout/verify_demo.py \
  --task-id 7499743232415371264 \
  --output /tmp/mea-demo-closeout/candidate-history-read
```

结果：15 PASS、0 FAIL、1 SKIP。task list、shell、overview、role-prompts、coding-mea、阶段结果均为 HTTP 200；真实跨任务 coding-mea / stage result 均为 404；真实无全文阶段返回 ARTIFACT_PREVIEW + FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND，content 404。Manager 全文因该历史任务 decisions 为空明确 SKIP（unverifiedCount=1），不能声称此任务覆盖了 Manager。

### Manager 合同按真实路由复验

初次读取 W1e 的脚本出现 15 PASS/1 FAIL：错误要求首条 EXECUTE → QA_AGENT 的 boundedContract 非空。当前 `ManagerPolicy` 在 HOST_VERIFY 通过后本来就以空合同继续 QA；只有有界 Coding 缺口修复才携带非空合同。这是验收脚本误判，并非后端合同丢失。

修复后分开验证全文身份/字段形状和适用 Coding 合同，HTTP 200、taskId、decisionHash、route、executorRoute、targetRecordIds、roundNo、stateVersion、stateHash、sourceCommandId 仍严格匹配。没有适用 Coding 决策时明确 SKIP。

- W1e `7502196308401328128`：17 PASS / 0 FAIL / 0 SKIP。首条 QA 空合同合法；round 2 EXECUTE → CODING_AGENT，targetRecordIds=[AC-003]，非空合同长度 24，全文身份一致。W1e 实际仍为 FAILED_RETRYABLE，不声称完成，也不重新认定 P3 阶段退出。
- 新 Demo `7502920392433078272` 执行中读取为 16 PASS / 0 FAIL / 1 SKIP；在 COMPLETED 且切换 5a3 包后为 **14 PASS / 0 FAIL / 3 SKIP**：无有界 Coding 修复决策，且四角色完整产物均存在，缺全文的 JSON/404 两个负例不适用。三个场景均由 W1e 的真实历史数据补足。不是把 SKIP 计为 PASS。
- 日志：本机 `/tmp/mea-demo-closeout/candidate-w1e-read-fixed/verify.log`、`candidate-new-business-read-fixed/verify.log`，对应 summary 均 PASS。
- 同项目 90 条元数据未发现明确 BUDGET_EXCEEDED 样本；真实预算耗尽行为本轮未覆盖，不能用其他失败类别代替。
- 最终脚本 SHA-256 `130b951c3aed44da2a8b48b041ad6e15a349b9803826618c853efd4c3bcd1650` 已上传。复测用临时 18081 隧道已释放。

### 实际桌面操作

公网管理页出现 ERR_EMPTY_RESPONSE；通过本机专用 SSH 隧道 `127.0.0.1:18080 → 127.0.0.1:8080` 打开同一真实服务，没有使用 mock。

- 从 `/admin/dashboard` → 任务管理 → 新任务详情打开，默认“产物与证据”。四角色与当前任务状态可见。
- Prompt → 静态 Prompt 显示本次实际材料和 promptArtifactId `7502920793572118528`，绑定需求评审 stageRunId `7502920712605274112`。
- 项目该 Attempt 未开启 PI_AGENT_STATE_V2，页面明确提示不可用；右侧角色、Attempt 与真实运行状态仍可读。不声称动态 todo/state 内容已验证。
- 切换到未执行 QA 后，右侧 stageRunId 变为 `7502920712605274115`，旧评审 Prompt 被清除。
- 只有任务 head PENDING 阻断时，仍显示“任务最新审计”和“任务当前阻断（不计入所选 Attempt 结论）”；无来源明确显示未记录，未借用 QA 的 ID。
- 旧 07l Coding Attempt 2 的全文提示 UNAVAILABLE；切换 Attempt 1 后可请求 ARTIFACT_PREVIEW；再次切换 Attempt 2 后已加载结果清空，“查看完整产物”重新出现，ID 准确变为 `7502761979875037184`。
- 运行中的审计 head 在点击“刷新”后更新；确认 GATE-BUILD/GATE-STATIC 出现在任务最新 Host 审计区，AC 仍为 PENDING。不声称审计 head 自动实时刷新。
- 浏览器 error/warn 日志为空。已在当前对话中采集审计区域真实截图。
- 额外发现并修复：已 CANCELLED 的 Coding/QA 卡片图标 aria 错误回落“待开始”；仅补取消图标分支，未改变状态机。typecheck 和相关 29/29 通过。业务结束后切换 5a3 包，旧 07l 的 Coding/QA 卡片均已真机显示“已取消”。
- 最终页复验暴露两处读取问题：直接打开 QA“产物与证据”显示证据 0 项，先点 Prompt 再返回才出现 23 项；QA `/result` 返回 truncated=true 的 20,000 字符 JSON 预览，直接解析失败，虽有完整结果仍显示自报检查 0 项。根因由 Luna 追踪到默认 tab 未调用 evidence loader、面板未跟随 `/result/content`。这两处修复已在最终 d707 包上复验：直接 reload QA 的 issues 页面，全程不进入 Prompt，自动出现 23 条证据、6 条自报检查及完整执行概述；两个业务 AC 在任务最新审计中仍为 COMPLETED。

### 最终包追加复验

- `/tmp/mea-demo-closeout/read-final-business/verify.log`：14 PASS / 0 FAIL / 3 SKIP；`read-final-w1e/verify.log`：17 PASS / 0 FAIL / 0 SKIP。两组脚本 exit=0，summary.result=PASS，SKIP 原因保留在 JSON。
- QA 默认页 reload 后无需点击“查看完整产物”，自动读全 JSON；显示的 6 条 Agent 自报与 7 条任务 head 审计仍分区，未把自报当 Host 结论。
- 从默认页点击桌面展开截图链接，实际打开 `.../qa-evidence/7502929647697530880/content`，浏览器显示 1440×900 图片，内容与保存的真实营业说明截图一致。
- 页面“下载脱敏 JSON”指向同 taskId、QA stageRunId 的 `/result/content`；该内容也已由页面补读并成功解析。
- 旧 07l Coding Attempt 2 手动读取显示 UNAVAILABLE；切换 Attempt 1 后自动读取 ARTIFACT_PREVIEW，展开可见正确来源；切回 Attempt 2 后旧预览和正文均消失，读取按钮恢复，stageRunId 为 `7502761979875037184`。
- 当前普通任务 Coding 页显示 Manage (Host Manager) round 2/DONE、Execute Coding Attempt 1/SUCCEEDED、Audit Host 审计闭环，PR #40 链接与 GitHub 核验一致。四角色定义未变。
- 最终浏览器 error/warn 日志为空。没有创建第二条需求、重跑已完成任务或操作其他实验。

## 6. 普通真实业务执行

- 项目：`codex-run-test-waimai` / `7499721648870920192`。
- 需求：首页新增营业说明区，标记 `MEA-DEMO-20260908`，初始收起，可展开/收起固定说明正文，保留订餐入口与导航。两个 AC；没有故障注入。
- 创建：`POST /admin/rd-tasks/requirements`，autoExecute=false、tokenBudgetOverride=0（沿用项目默认，不表示零预算）。HTTP 200，taskId `7502920392433078272`。
- 提交：`POST /admin/rd-tasks/7502920392433078272/submit`，body `{}`，仅执行一次，HTTP 200。
- 请求与响应：`business-request.json`、`business-created.json`、`business-submit-attempt.json`、`business-submitted.json`，保存于云端 `/tmp/mea-demo-closeout/`。
- 真实状态、角色、MEA 快照保存在云端 `/tmp/mea-demo-closeout/live/` 和 `live-monitor/`。最终任务 **COMPLETED**，errorMessage 为空；四角色均 SUCCEEDED，耗时分别 277375 / 110142 / 314497 / 1167485 ms。
- 需求评审、方案设计、Coding 均 SUCCEEDED；宿主验证 runId `7502923910023876608` SUCCEEDED。Manager round 1 决策 `sha256:a05d96d0c3c2ae0e013a3690859165c89d494d450f233d82a81f4f0f0091ac2b` 为 EXECUTE → QA_AGENT，理由为 host verification 成功后继续 QA；无缺口返工。
- 业务代码只读核验：云端 branch=`requirement/7502920392433078272`，Coding 后 HEAD=`a4bb7f287cce95db634ca6eef2ef267d45d8bfd8`，业务 diff 仅 `client/src/pages/customer/Home.tsx`；唯一标记/固定正文各出现一次，默认收起与切换逻辑、原导航保留。该检查不代替浏览器点击，证据为本机 `business-artifact-code-check.json`。
- QA 完成前的真实快照中 AC-001/AC-002 仍 PENDING，仅 GATE-BUILD/GATE-STATIC 由宿主审计完成，Coding 自报 CLAIM 仍 UNTRUSTED；没有因 Coding 自报成功直接晋升业务 AC。
- QA 完成后，AC-001/AC-002 均 PASSED；任务最新审计中两项均 COMPLETED，stateVersion=6、lastAuditRunId=`7502929648003715072`。head 记录的 sourceStageRunId 为空，页面应如实显示；通过 evidenceRefs.auditRunId → coding-mea.auditRuns 可查到 subjectStageRunId=`7502920712605274115`，没有补造 head 字段。
- Manager round 2 为 DONE → DETERMINISTIC_REVIEW；COMPLETION/REPORTING 均 SUCCEEDED。只有一次 Coding，没有人为制造缺口返工。
- PR [#40](https://github.com/wanghehe123/rd-bot-waimai-acceptance-20260624-141045/pull/40) 已用 `gh pr view` 独立核验为 OPEN、非草稿，分支 `requirement/7502920392433078272` → main，head=`0d698b549d6b46aa454f752a00a1e0a4743d0cf6`。仅 `client/src/pages/customer/Home.tsx` 修改，26 行新增；未 merge。
- QA 结果记录容器的 `start.sh` 因 server 缺少 TypeScript 失败，随后使用等价生产构建与服务启动路径完成浏览器验证；不声称原启动脚本在 QA 容器里一次通过。宿主验证另有 SUCCEEDED 记录，桌面/移动端证据已取回查看。
- QA stage=`7502920712605274115`，结果 artifact=`7502929646971916288`，source=FINALIZATION_RESULT，failureCategory=NONE。23 条 QA evidence 的 stageRunId 全部精确匹配；两 AC 引用的证据并集含 desktop/mobile 截图、console、network 和 trace ZIP。
- 已下载并人工查看桌面/移动端展开截图：标记、说明正文、按钮和原导航/商家卡片可见。四张展开/收起截图及 SHA 摘要保存在 `docs/superpowers/qa/assets/mea-demo-2026-09-08/`；接口和审计关联说明见其中 `summary.json`。

## 7. 收口清单

- [x] 取得明确云端授权，上传候选和最终脚本。
- [x] 指定旧任务 pause/stop，保留审计并核对过期 command 与实际执行。
- [x] 部署 1bc13039 候选，保留完整回滚包；验证同候选真实接口。
- [x] 桌面验证默认页、Prompt、状态归属、Attempt 切换及仅 head 阻断场景。
- [x] 当前业务结束后部署追加取消图标修复的包，复验真实取消状态与关键接口。
- [x] 完成普通需求执行，记录真实终态、PR/产物、Manager 和 QA/审计证据。
- [x] 修复真机追加发现的默认 QA 证据加载和截断 JSON 读取；构建最终包，按明确授权切换后复验同一任务。

本轮没有对 RD-Bot 仓库 commit、push 或 archive。业务流水线产生的业务仓库提交与 PR #40 已作为交付产物单独记录。真实预算耗尽、动态 PI_AGENT_STATE_V2、P2 恢复/kill 矩阵没有覆盖；审计 head 目前需手动刷新。以上不扩展到本次普通 Demo 的退出条件。

主工作区及两个来源工作区的 HEAD 和 `git status --porcelain=v1 -z` 指纹与本轮开始一致（13/12/42 项原 dirty），证据为 `/tmp/mea-demo-closeout/source-worktrees-before.json` 与 `source-worktrees-after.json`。此项是 Git 状态清单核对，不宣称逐字节审计其所有 dirty 内容。

机器可读汇总：`docs/superpowers/qa/2026-09-08-mea-demo-final-evidence.json`。本轮临时验收标签已关闭，18080/18081 SSH 隧道均已释放；云端最终服务保持运行，回滚包保留。
