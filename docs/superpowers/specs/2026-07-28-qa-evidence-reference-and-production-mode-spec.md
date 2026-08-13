# QA 证据引用协议与浏览器验证生产模式护栏

日期：2026-07-28
状态：已实施。浏览器证据引用与生产模式护栏经 pull/2、pull/3 验证；docs-only 跳过与 `dockerMetadataJson` 通道经 2026-08-13 任务 `7493354884037742592` / PR #9 验证。
范围：QA 结果的证据引用协议、浏览器 QA 的应用启动模式、hydration 感知的交互纪律、docs-only 例外与元数据通道。

## 1. 问题

### 症状

- 任务 `7487656533929627648`：QA 实质全部通过（生产模式浏览器交互成功、证据齐全），
  仍以 `QA evidence bundle invalid: browser validation requires referenced
  QA_CONSOLE_LOG evidence; ... QA_NETWORK_LOG evidence` 进入 `FAILED_NEEDS_HUMAN`。
- 任务 `7487668615836209152`：代码正确、typecheck/build/lint 全过，但浏览器点击
  `添加文档` 按钮后对话框不出现，QA 判 `QA_INFRASTRUCTURE`/`HUMAN`。

### 已验证根因

1. 宿主 `exec/.../result/QaEvidenceBundleValidator` L147-155 要求浏览器验证的
   console/network/trace/desktop+mobile 截图必须被 `acceptanceResults` 的
   `logArtifactId` 或 `evidenceArtifactIds` **引用**；agent 只采集并写入 manifest
   而未引用，容器销毁后被整单拒绝且无法补救。这是 bridge 宽松/宿主严格协议裂缝的
   第二例（第一例是角色协议字段形状校验）。
2. `exec/.../qa/QaRepositoryProfileDetector` 对 Next.js 自动探测生成
   `npm run dev` 启动命令。dev 模式在受限容器内按需编译 + hydration 极慢：SSR HTML
   已渲染（按钮可见）但客户端事件处理器未挂载，点击静默无效，连
   `dispatchEvent('click')` 也无效。对照组：同一应用在生产模式
   （`npm run build && npm run start`）下 Playwright 点击 React 组件完全正常，
   证伪了"Playwright 无法触发 React 合成事件"的旧结论。
3. QA 启动超时 `RD_QA_STARTUP_TIMEOUT_SECONDS` 原为 120s，容不下生产构建
   （实测 Next.js build 约 120s），startCommand 含构建后必须同步扩大预算。

## 2. 已验证执行链

```
RequirementDeliveryEngine（QA 契约提示词）
  -> QaRepositoryProfileDetector.autoDetect（qa-profile.json 的 startCommand）
  -> DockerPiAgentExecutor / DockerClaudeCodeExecutor（注入 RD_QA_* 环境变量）
  -> qa-playwright-cli SKILL.md（容器内执行纪律）
  -> rd-pi-bridge result-tool.mjs validateQaReport（容器内提交预校验，可当场补交）
  -> 宿主 QaEvidenceBundleValidator（容器销毁后终审，fail-closed）
```

宿主按 `qa-evidence/` 子目录路径归类证据类型（`DockerPiAgentExecutor.qaArtifactType`）：
`console/`→QA_CONSOLE_LOG、`network/`→QA_NETWORK_LOG、`traces/*.zip`→QA_TRACE、
`screenshots/*.{png,jpg,jpeg}`→QA_SCREENSHOT。bridge 预校验按同一路径约定镜像宿主规则。

## 3. 护栏规则

### 3.1 证据引用协议

- 浏览器验证（`browserValidation.required && performed`）的 QA 结果，
  `acceptanceResults` 的 `logArtifactId` + `evidenceArtifactIds` 合集必须至少各引用
  一次 `qa-evidence/console/`、`qa-evidence/network/`、`qa-evidence/traces/*.zip`，
  以及 `qa-evidence/screenshots/` 下文件名含 `desktop` 和含 `mobile` 的截图各一。
- 只采集、只写 manifest 不算引用。宿主 fail-closed，不得放宽。
- 任何新增宿主端结果校验规则，必须同步在 bridge `result-tool.mjs` 内实现容器内
  预校验镜像，让 agent 在容器存活期就能收到完整违规清单并补交；同时把规则写进
  `RequirementDeliveryEngine` 的角色契约提示词。三处（提示词/bridge/宿主）不一致
  即协议裂缝。

### 3.2 浏览器 QA 启动模式

- Next.js 项目的自动探测 startCommand 必须是生产模式：
  `npm run build && npm run start -- --hostname 0.0.0.0`。不得回退为 `next dev`。
- QA 启动超时预算必须覆盖构建阶段（当前 300s，两个执行器一致）。
- 交互纪律（SKILL.md）：首次交互前等待 `networkidle`；点击无观察效果先等待再重试
  一次；dev 模式下点击仍无效必须改生产模式复验后才允许定性 `QA_INFRASTRUCTURE`；
  报告浏览器 FAILED 前必须抓取 console/network 日志作定性证据。
- 可见按钮点击无 DOM 变化优先怀疑 hydration 未完成，而不是断言工具或产品缺陷。

### 3.3 Docs-only 例外（跳过 build/start/browser）

动机：credential-isolation 网络下 Next.js 全量回归需要 `npm install`/`build`/`start`，
但 docs-only 候选补丁不触及运行时；强制浏览器画像会把正确交付误判为失败。

**判定来源（强制）**：宿主在候选补丁已 `git apply --index` 之后，从仓库真实变更文件集
（`git diff --cached/--name-only` + untracked）判定；写入
`/work/input/qa-profile.json` 的 `candidateChangedFiles` 与 `decisionSource`。
任务描述或 agent 自称 "docs only" **永远不够**。

**Allowlist（全部路径必须命中，否则不是 docs-only）**：

| 类别 | 规则 |
| --- | --- |
| 文档 basename | `README*` / `CHANGELOG*` / `CHANGES*` / `HISTORY*` / `AUTHORS*` / `CONTRIBUTORS*` / `LICENSE*` / `LICENCE*` / `NOTICE*` / `COPYING*` / `CODE_OF_CONDUCT*` / `SECURITY*` / `CONTRIBUTING*`（大小写不敏感；不得带运行时扩展名如 `.js`/`.json`） |
| 文本扩展名 | `.md` / `.mdx` / `.markdown` / `.txt` / `.rst` / `.adoc` |
| docs/ 资源 | 路径位于 `docs/` 下且扩展名为 `.png` / `.jpg` / `.jpeg` / `.gif` / `.svg` / `.webp` |

**Fail-closed**：

- 变更集为 `null`、空、或无法从 git 读取 → `UNDETERMINABLE` → **保持完整浏览器画像**。
- 任一路径不在 allowlist（含 `src/**`、`package.json`、lockfile、CI workflow、Dockerfile、
  配置/部署描述符等）→ `NOT_DOCS_ONLY` → 完整画像不变。
- 仅当分类为 `DOCS_ONLY` 时：`browserRequired=false`，`startCommand=""`，
  `decisionSource=DOCS_ONLY`，跳过 install/build/start/Chromium；宿主
  `QaEvidenceBundleValidator` 与 bridge `result-tool.mjs` 同步不要求 browser evidence，
  且必须用同一 allowlist 复核 `candidateChangedFiles`。

**元数据通道（强制）**：宿主只从 `dockerMetadataJson` 读取
`QaExecutionMetadataKeys`（含 `qaCandidateChangedFilesJson`）。Pi 必须把 QA 判定键写入该通道；Claude 路径已如此。写入 `githubMetadataJson` / `repositoryMetadata` 对宿主不可见，会把容器内 `DOCS_ONLY` 变成宿主 `UNDETERMINABLE`，整单在容器销毁后被拒绝。不得放宽宿主去读第二通道。

**三处锁步**：

1. `RequirementDeliveryEngine` QA 角色契约提示词（声明 docs-only 例外与禁止自称降级）
2. bridge `result-tool.mjs` + `docs-only.mjs`（容器内预校验）
3. 宿主 `QaRepositoryProfileDetector` + `QaEvidenceBundleValidator`（画像与终审）
4. 执行器把 QA 键写入 `dockerMetadataJson`（`DockerPiAgentExecutor` / Claude 对等路径）

## 4. 验收标准

- [x] bridge 拒绝"采集但未引用 console/network"的浏览器 QA 结果，错误信息指明缺失类别。
- [x] `browserValidation.performed=false` 时不触发引用校验（启动失败类报告不被误伤）。
- [x] Next.js 自动探测产出生产模式 startCommand，`reason` 含 `production mode`。
- [x] 两个执行器的 `RD_QA_STARTUP_TIMEOUT_SECONDS` 为 300。
- [x] 实测：任务 7487656533929627648、7487668615836209152 QA 阶段重试后 PASSED，
      任务 COMPLETED 并产出真实 PR（pull/2、pull/3）。
- [x] docs-only 变更集使 QA profile 跳过 build/start/browser，宿主接受无浏览器证据的结果。
- [x] 2026-08-13：任务 `7493354884037742592` docs-only QA 后发布 PR #9；判定键走 `dockerMetadataJson`。
- [x] 触及源文件 / `package.json` / lockfile 时仍要求完整画像与完整证据包。
- [x] 变更集不可判定时 fail-closed 回退完整画像；提示词 / bridge / 宿主判定一致。

## 5. 验证命令

| 检查 | 命令 | 预期 |
| --- | --- | --- |
| Bridge 协议 | `cd bootstrap/src/main/resources/executor/pi && node --test test/protocol.test.mjs` | 含 docs-only 用例全部通过 |
| Docs-only 分类与画像 | `./mvnw -pl exec -Dtest='QaDocsOnlyChangeClassifierTest,QaRepositoryProfileDetectorTest,QaEvidenceBundleValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 全部通过 |
| 探测器与执行器 | `./mvnw -pl exec test -Dtest='QaRepositoryProfileDetectorTest,QaEvidenceBundleValidatorTest,DockerClaudeCodeExecutorTest,DockerPiAgentExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false` | 全部通过（Pi QA `provider-attempts` 隔离测试 `@Disabled`） |
| QA 契约提示词 | `./mvnw -pl engine test -Dtest=RequirementDeliveryEngineTest -Dsurefire.failIfNoSpecifiedTests=false` | 通过且提示词含 docs-only |
| 镜像内校验生效 | `docker run --rm --entrypoint grep rd-bot/pi-agent:local -c checkBrowserEvidenceReferences /opt/rd-pi-bridge/src/result-tool.mjs` | 输出非 0 |
| 镜像内 docs-only | `docker run --rm --entrypoint grep rd-bot/pi-agent:local -c DOCS_ONLY /opt/rd-pi-bridge/src/result-tool.mjs` | 输出非 0 |

## 6. 失败处置

| 症状 | 先查什么 | 处置 |
| --- | --- | --- |
| `browser validation requires referenced ... evidence` | result.json 的引用合集 vs qa-evidence/ 实际文件 | QA 阶段重试即可；agent 会被 bridge 预校验拦截并当场补引用 |
| 点击按钮无 DOM 变化 | qa-profile.json 的 startCommand 是否 dev 模式 | 用生产模式复验；勿直接定性产品缺陷或工具缺陷 |
| 启动阶段超时 | startCommand 是否含构建、`RD_QA_STARTUP_TIMEOUT_SECONDS` | 预算需覆盖构建；构建失败是 ENVIRONMENT 不是启动超时 |
| bridge 通过但宿主拒绝 | 三处协议（提示词/bridge/宿主）差异；Pi 是否把 QA 键写进 `dockerMetadataJson` | 锁步提示词/bridge/宿主；Pi 不得把判定键只写进 github/repo metadata |
| 容器跳过浏览器但宿主报变更集不可判定 | `dockerMetadataJson` vs `githubMetadataJson` | 以宿主通道为准修生产者；不要放宽宿主 |
