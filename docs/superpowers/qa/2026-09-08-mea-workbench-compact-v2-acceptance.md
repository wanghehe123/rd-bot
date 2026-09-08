> 公开发布说明（2026-09-08）：本报告引用的截图 PNG 已因隐私原因从公开发布分支移除；文本证据仍为本报告权威内容。


# MEA 工作台首屏精简 V2 验收记录

日期：2026-09-08。分支：`codex/mea-workbench-compact`。工作区：`/Users/wish233/Documents/RD-Bot/.worktrees/mea-workbench-compact`。

**结论：V2 展示合同在真实页面验收通过（V01–V08 本地实机/只读完成，V09 已在云端部署后经公网真实页面复验）。后端零功能修改（源码逐字节一致，jar 内类字节差异为 JDK 微版本编译产物）；261/261 前端测试、typecheck、build、OpenSpec 22/0 全部通过。未 commit/push/archive。**

## 1. 基线与来源（P00）

- 来源 `codex/mea-demo`（HEAD `f3e7bcc9`）的未提交修复通过 `git diff HEAD --binary`（/tmp/mea-workbench-v2-baseline.patch，1,015,064 字节）与 17 项 untracked 文件清单（逐文件 shutil.copy2 + SHA 校验）完整继承，未从裸 HEAD 开始。
- untracked 清单审阅：Python 回归测试、本 V2 计划、demo closeout/QA 证据、两个 OpenSpec delta（mea-demo-audit-scope-verification、requirement-review-need-info-priority）；无 .env/缓存/node_modules。
- 基线测试 247/247 + typecheck 通过后才开始改动。
- 展示合同建档：`openspec/changes/mea-workbench-compact-v2/`（proposal/spec/tasks），`openspec validate --all --strict` 22/0。
- 修改前截图（真实 d707 页面，经 SSH 隧道 127.0.0.1:18080）：`before-qa-{1280x720,1440x900,900x900,390x844}.png`、`before-coding-{1280x720,1440x900}.png`。

## 2. 实施内容（P01–P04）

| 项 | 文件 | 内容 |
| --- | --- | --- |
| P01 | `RdTaskDetailPage.tsx` | 删除空"快速操作"容器（成功任务不再渲染空壳）；提交/预算审批/补充信息三操作按原条件与 handler 合并进页头操作区；`TaskSummaryBand` 大卡删除，等待补充信息/恢复中/当前阻断提示迁至 `TaskHeaderNotice`（仅有内容时渲染）；次级行携带任务 ID、项目、当前流程（角色·阶段状态）、创建/更新时间、累计 Token、已审计 n/m |
| P01 | `TaskRoleWorkbench.tsx` | 移除与页头重复的 阶段推进/当前角色/耗时；overviewError 保留可见 |
| P02 | `workbenchSummaryModel.ts`（新） | `summarizeChecks`（PASS/PASSED 计通过，SKIPPED/BLOCKED/未知保持 other）、`isPlaceholderResultSummary`、`roleCardSummary`（四角色一句话摘要；截断预览不计算完整数量；QA 审计/证据计数与 Coding 宿主验证/PR 不依赖解析） |
| P02 | `roleDeliverableModel.ts` | View 增加 `allDeliverables`/`allEvidence` 完整列表（deliverables/keyEvidence 保持前三/前五兼容视图）；占位 resultSummary 不再作为执行概述 |
| P02 | `TaskRoleWorkbench.tsx` | 角色卡第三行由"无当前阻断"占位替换为真实产物摘要；异常时仍显示 Prompt 绑定异常 |
| P03 | `RoleDeliverablesPanel.tsx` | 顺序重排：当前阻断（含任务 head 阻断单列，标注"不计入所选 Attempt"）→ 产物摘要（每项两行）→ 快捷入口（PR/关键证据（N）/完整产物，均为真实跳转）→ Coding MEA → 五项关键证据 + 当前页查看全部 → 折叠检查明细（命令/evidenceRefs/来源）→ 折叠原始结果；执行概述两行 + aria-expanded 展开按钮；展开状态随 stageResultIdentity 重置；完整结果自动读取不回退 |
| P03 | `CodingMeaPanel.tsx` | 当前轮压成单行三职责摘要（Manage/Execute/Audit + QA 跳转 + 查看决策详情）；理由/合同/命令 ID 留详情；历史轮次折叠 |
| P03 | `HostVerificationCard.tsx` | 新增 `compact`（仅 roles 视图启用）：成功压成一行（状态/构建/静态/耗时/廉价返工/查看步骤）；失败仍完整展示失败原因 |
| P04 | `TaskRoleWorkbench.tsx` | 单份状态 DOM：aside 移至内容 Tabs 之前（窄屏状态先于正文），桌面显式 grid 放置右列（lg:col-start-3）；所选阶段行增加 当前/历史 Attempt 徽标；占位 `ROLE result json` 副标题删除，改为所选 Attempt 自身摘要 |
| P04 | `RoleAgentStateCard.tsx` | Seq/Hash/注入详情移入"查看 Hash 与标识信息"；资源与预算收进折叠详情 |

## 3. 真实页面验收（V01–V09）

V01–V08 环境：本地 Vite dev（`npm run dev`，代理 `RD_BOT_BACKEND_TARGET=http://127.0.0.1:18080` → SSH 隧道 → 云端 d707 后端）。V09 环境：候选包部署后的公网 Nginx 入口 `http://106.55.13.166/admin`，未使用隧道。真实任务均为 `7502920392433078272`（COMPLETED，QA stage `7502920712605274115`，23 条证据）。所有本地截图在本目录。

| ID | 结果 | 证据 |
| --- | --- | --- |
| V01 QA 桌面 | PASS：唯一任务摘要行；无空操作条；状态/角色卡/Prompt Tab/产物摘要（QA 卡"任务审计已完成 7 项 · QA 证据 23 条"）/关键证据（23）与完整产物入口均首屏可见 | `after-qa-1280x720.png`、`after-qa-1440x900.png`（对照 before-*） |
| V02 Coding 桌面 | PASS：PR #40（卡片摘要+PR 徽章双入口）、宿主验证通过、MEA 三职责摘要（Manage/Execute/Audit 行 + QA Attempt 1·SUCCEEDED 跳转）首屏可见；外层四角色保留；1440×900 一屏完整到宿主验证一行概要 | `after-coding-1280x720.png`、`after-coding-1440x900.png` |
| V03 QA 窄屏 | PASS：900×900 与 390×844 状态卡（最新状态）先于内容页签；无横向溢出（scrollWidth-clientWidth=0）；展开完整状态与待办可用 | `after-qa-900x900.png`、`after-qa-390x844.png`、`after-qa-390x844-scrolled.png` |
| V04 展开与折叠 | PASS：查看全部证据 → 23 条全部在当前页可达（截图含 traces/screenshots/network/manifest/console/commands）；检查明细展开显示 6 条自报命令与 AC-001/AC-002 审计明细（evidenceRefs、"来源: 未记录 sourceStageRunId"如实）；执行概述 aria-expanded 状态正确；展开/收起未触发 audit-content 或任务日志下载 | `after-qa-expanded-23-evidence.png`、`after-qa-check-details-expanded.png` |
| V05 旧 07l Attempt 切换 | PASS：任务 `7502759980530012160`（CANCELLED）Attempt 2→1→2：徽标 当前/历史 Attempt 正确；Attempt 1 显示"最后状态"+ 自身 stageRunId `7502759985672228866` + 自身错误（Pi result requires RESULT_SUBMITTED…），切回 Attempt 2 后该错误消失、状态卡恢复 `7502761979875037184`；CANCELLED/可重试失败如实显示；任务级阻断提示留在页头 | `after-old07l-coding-attempt2.png`、`after-old07l-coding-attempt1.png` |
| V06 Tab/角色切换 | PASS（抽查）：QA/Coding 之间与四内容 tab 切换，状态卡身份跟随所选 Attempt；Prompt 一次点击可达（内容页签第二项） | 截图组 |
| V07 W1e 只读 | PASS：V05 同任务即历史缺口样本；任务失败结论未被 Manager 历史改变；"当前记录暂无法关联到具体 Manager 决策"如实显示 | `after-old07l-*.png` |
| V08 请求行为 | PASS：四角色卡仅消费已加载 stage/证据/审计字段；完整结果仍只为所选 Attempt 读取一次（沿用 stageResultIdentity 防抖与过期保护，stageResultService 测试未变）；展开状态切换不发新请求 | 代码路径 + `after-qa-expanded-23-evidence.png` |
| V09 云端版本 | PASS：云端运行包 SHA-256 为 `82bcb02d…a06eb58`；公网 `/admin/dashboard`、真实任务页与任务 API 均为 200；公网获取的 `admin-RdTaskDetailPage.js`、`admin-knowledge.css` 哈希逐项等于候选包内资源。新开公网任务页实际显示 QA「任务审计已完成 7 项 · QA 证据 23 条」、Coding「宿主验证通过 · PR #40」、状态卡、23 条证据折叠入口、检查明细和原始产物折叠入口 | 2026-09-08 云端部署记录；资源 SHA：`2464b13f…a7fd81b`、`fbac684a…31f861`；公网任务页 `/admin/rd-tasks/7502920392433078272?role=QA_AGENT&attempt=1&tab=issues` |

**既有 FE-01/03/11 的更正**：来源工作区 `2026-09-06-mea-task-workbench-acceptance.md` 曾以 Node 正则/类名断言记为视觉 PASS；本轮以真实页面同视口截图为准重新验证——旧"唯一任务状态摘要"当时未达成（TaskSummaryBand 与工作台进度头并存）、"首屏密度"未达成（空操作条+大卡占满首屏）。本轮截图为准，不改写旧报告。

## 4. 静态与构建验证（P05）

- 前端：`node --experimental-strip-types --test test/*.test.ts` 261/261（基线 247 + 新增 14：摘要模型 10、完整列表 1、契约调整与新增守卫若干）；`npm run typecheck`、`npm run build` 通过。
- OpenSpec：`openspec validate --all --strict` 22/0；`git diff --check` 干净。
- 组合 jar：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw -o -pl bootstrap -am -DskipTests package`（跳过 Java 测试；本计划零 Java 修改）。
  SHA-256：`82bcb02dd0db11d316490bb1ea55c20838a6f4f46b8f1c96f42dd3c32a06eb58`
- 与 d707 包递归比较：
  - 管理端静态资源仅 `admin-RdTaskDetailPage.js` 与 `admin-knowledge.css` 变化（V2 全部改动落在该页 chunk；CSS 因新工具类）。
  - `BOOT-INF/classes/executor/pi/node_modules` 仅存在于 d707——系 mea-demo 工作区资源目录内的本地 npm 安装产物被打入 d707；Dockerfile 自带 `COPY package*.json` + 安装步骤，jar 内嵌 node_modules 非运行必需，新包省略不影响 Pi 镜像构建。
  - engine/exec/rag/skill lib jar 字节不同但**源码逐字节一致**：对全部 12 个字节差异类做 `javap -p -c -constants` 对比，仅 `RequirementDeliveryEngine`（及 exec 1 类）存在字节码偏移平移（大小差 15 字节），无指令/常量池语义差异——系两次构建间 JDK 21 微版本更新所致，非功能变更。

## 5. 发布与回滚（已执行）

- 候选包 `bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar`（SHA-256 `82bcb02dd0db11d316490bb1ea55c20838a6f4f46b8f1c96f42dd3c32a06eb58`）已部署至 `ubuntu@106.55.13.166:/home/ubuntu/RD-Bot/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar`。
- 切换前确认旧运行包为 `d707cdafa1d470a21f193131fef49323098bc3650459b53efd4243f1f0c56de4`、唯一 8080 JVM 为 PID 3542622、没有 Pi/Playwright/Coding/QA 实际执行进程；三个基础设施容器健康。随后保存旧包、在目标目录写入并校验临时文件、原子替换、沿用 `start-backend.sh` 重启。新 JVM PID 3692081 启动后本机与公网读取均成功。
- 公网入口为 Nginx `http://106.55.13.166/admin/dashboard`（80 → `127.0.0.1:8080`）。安全组继续屏蔽公网 8080，未建立 SSH 隧道，也未改变 Nginx 配置。
- 回滚包已保留为 `/home/ubuntu/rd-bot-rollbacks/d707cdafa1d470a21f193131fef49323098bc3650459b53efd4243f1f0c56de4.jar`。异常时将其复制到目标目录临时文件后原子替换，并运行同一 `start-backend.sh`。
- 真实业务读取验收：`verify_demo.py --task-id 7502920392433078272` 为 PASS；任务列表、shell、execution overview、role prompts、coding-mea、stage result 与跨任务真实 stageRunId 的 404 身份隔离均通过。3 项显式 SKIP 来自该既有完成任务没有有界 Coding 修复决策、也没有缺完整产物的真实 stage，未伪造为通过。

## 6. 未验证范围

- V09 以公网资源哈希、HTTP 响应和实际页面可访问性复验；未在该次部署后单独导出新的浏览器 console 文件。V01–V08 的真实页面记录已确认 console 0 error，不能将其表述为新的 V09 console 捕获。
- 未新造 WAITING_USER_INPUT/运行中任务/预算耗尽样本；等待与阻断提示的视觉验证来自真实 CANCELLED 任务的任务级阻断条（V05）。
- Pi 状态栏（PI_AGENT_STATE_V2）在现网任务未启用，仅验证其"未启用"真实提示展示。
- 390/900 仅做受影响页面布局冒烟，未扩大为移动业务专项。

## 7. 差异与所有权核对（P06）

- 本次新增：`workbenchSummaryModel.ts`、`test/workbenchSummaryModel.test.ts`、`openspec/changes/mea-workbench-compact-v2/`、本目录截图与报告。
- 修改：P01–P04 表列文件及对应测试；构建产物 static/admin 两个文件。
- 主工作区 `/Users/wish233/Documents/RD-Bot` 与来源工作区 `.worktrees/mea-demo` 未被改动（仅只读读取与 scp 下载）。
- 未 commit/push/merge/archive；实施结果停留在可审查 diff。
