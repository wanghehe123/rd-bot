# RD-Bot Personal Open Source Release Acceptance

- **Candidate SHA:** `a53c117caeec84e03aef21b81b662ebb8c5c8dfe`（分支 `feat/open-source-release-prep`）
- **Started/finished at:** 2026-09-09 05:05 → 05:35 (UTC+8)
- **Host OS/arch:** macOS (darwin 25.6.0) arm64, Docker Desktop（engine 29.1.3, compose v2.40.3）
- **Image digests (unique acceptance tag `acceptance-t13-050529`, clean worktree `/tmp/rd-bot-oss/wt-t13-050529`):**
  - `rd-bot/pi-agent` `sha256:c843f8f59af4c104e31ab856fc966d42342316927db8793f558a8e6b0026706e`（`--no-cache` 从 worktree 源码构建）
  - `rd-bot/pi-agent-qa` `sha256:3058e8f1b40fa8d62d252d75bd3e4bd7a5c1f4660eb7703bfaedfbf7033e297b`
  - `rd-bot/app` `sha256:e54c4550316d044956123d833193165316c5dfeb22408d00693c047025b71fd7`
- **Environment scope:** compose project `rd-bot-oss-acceptance-t13-050529`（唯一），egress network
  `rd-bot-egress-acceptance-*`（唯一），空卷，独立 worktree（无 target/、无 node_modules）。
  构建日志：`/tmp/rd-bot-oss/t13-{pi,qa,app}.log`（SHA-256 见证据段 E13-1）。

## Gate table

| Gate | Result | Evidence | Limitation |
| --- | --- | --- | --- |
| G01 License | PASS | E01：LICENSE 为 MIT 全文（SHA-256 `c0f74ded…`）；effective POM 含 MIT；两 package.json `"license":"MIT"`；项目级 Apache 残留 0（THIRD_PARTY_NOTICES 中的第三方引用除外） | — |
| G02 Public hygiene | PASS | E02：gitleaks 8.30.1 全历史 354 commits 0 确认凭据；个人简历/面试材料/7 张会话快照/25 张历史截图/个人 VM 脚本全部移出；deploy/cloud-server 脱敏为 UNSUPPORTED 示例 | 历史 docs 保留个人服务器 IP 与仓库名的文字记载（非凭据，属 provenance 记录） |
| G03 Local security | PASS | E03：`ApplicationSecureDefaultsTest`（loopback 默认 + 四开关关闭 + token 空）；`SecurityPostureLogger` 启动摘要无凭据；实测宿主仅 `127.0.0.1:18080` 监听（lsof）；mutation 空 token 503 | — |
| G04 Image bootstrap | PASS | E04：唯一 tag 空机链 Pi→QA→app 全成功（Pi `--no-cache`）；Chromium headless `CHROMIUM_LAUNCH_OK`；QA 镜像无 socket、无凭据 env；policy tests 全绿 | 构建需绕过本机 registry 故障（base digest 钉住 / oci-layout 命名上下文 / DOCKER_CLI_URL 覆盖）——公共网络环境直接用默认 URL |
| G05 Full compose | PASS | E06：contract 测试 13/13；真机空卷 up 全 healthy；迁移首跑 applied=24、二跑 applied=0 skipped=24（p8_zz 种子只执行一次）；`down` 保留卷 | — |
| G06 Agent plumbing | PASS | E06：后端容器经 socket 创建 alpine 探针容器写 same-path workspace，宿主回读成功；Pi task 容器 task-local 网络隔离由 51 项 `DockerPiAgentExecutorTest` 钉住 | 真实 agent relay 流量验证归 G12 |
| G07 Operator UX | PASS | E07：launcher 行为测试 7/7（stub docker）；真机 `status` 对健康栈正常；0600 env、不覆盖、down 不删卷、purge 需 `--yes`；build 失败非零 + 诊断 | — |
| G08 Admin onboarding | PASS | E08：controller 测试 7/7；`/admin`、`/admin/model-providers`、`/admin/dashboard` 直达/刷新 200；API 404 仍是 404；onboarding checklist 卡片真实驱动（截图为证）；无凭据阻断创建 | — |
| G09 Regression | PASS | E09：全量 Maven **1359 tests, 0 failures, 0 errors, 66 skipped**（显式 real-smoke）；core suite `test-open-source-core.sh` exit 0；无 failure.ignore | 66 skipped 均为需真实中间件/凭据的 smoke（按计划显式跳过） |
| G10 Dependencies | PASS* | E10：npm 双扫描器 0 命中（frontend 3H+5M→0；pi 2H+1M→0，pi-coding-agent 0.82.1→0.85.1，111/111）；Maven/镜像扫描被本机 registry 故障阻断，记为 G10 open item + CI job 复核条件 | *PASS 附条件：CI 的 osv-scanner job 在正常网络执行后闭环；非「零漏洞」声明 |
| G11 README | PASS | E11：中英双版 17 节结构；shell blocks bash -n 全过；相对链接有效；4 webp + 1 gif 来自当前候选栈、演示标识 example-org/hello-rd-bot，OCR/视觉复核通过，ASSET_PROVENANCE.md 记录来源 | build badge 留空（CI 于发布后首个 run 通过后再加，见 T12） |
| G12 Real delivery | PASS | E12：真实需求任务 `7503312963403649024` 走完四角色 + HOST_VERIFY + 双 MANAGER_DECIDE + 确定性审计 + PUBLICATION，任务 COMPLETED；真实 PR `wanghehe123/hello-rd-bot#1`（OPEN）创建并核验；PR 测试本地复跑通过（fail 0） | 专用私有测试仓库 `wanghehe123/hello-rd-bot`（维护者授权） |
| G13 Restart | PASS | E13：acceptance Phase D——restart/up 后 migration ledger 行数不变、runtime.env 秘密不变、`/admin` 200；`down` 后卷保留 | — |

## Commands

| Command | Exit | What it proves |
| --- | --- | --- |
| `env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./mvnw test` | 0 | 全量 1359/0/0/66（E09，run 6） |
| `env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./scripts/test-open-source-core.sh` | 0 | 首发 core gate（backend focused + frontend + Pi bridge + openspec strict） |
| `cd frontend && node --experimental-strip-types --test test/*.test.ts && npm run typecheck && npm run build` | 0 | 265/265 + typecheck + 生产 build（依赖升级后） |
| `cd bootstrap/src/main/resources/executor/pi && npm test` | 0 | Pi 桥 111/111（pi-coding-agent 0.85.1） |
| `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict` | 0 | 22 items passed |
| `bash scripts/docker/tests/compose_contract_test.sh` | 0 | resolved compose 13 项合同断言 |
| `bash scripts/docker/tests/launcher_test.sh` | 0 | 启动器行为 7 项（stub docker） |
| `ACCEPTANCE_TAG=acceptance-t13-050529 ACCEPTANCE_PROJECT=rd-bot-oss-acceptance-t13-050529 bash scripts/docker/acceptance.sh` | 0 | 隔离栈 A/B/D smoke PASS（E13） |
| `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .agents/skills/rd-bot-project-autopilot/scripts/tests` | 0 | autopilot 脚本回归（本机 run 6 同批执行） |
| `gitleaks git . --redact` | findings=24→0 confirmed | 历史 354 commits 逐条判定（E02） |

## Real delivery identity

维护者于 2026-09-09 授权后执行。候选提交 `a53c117c`（验收构建）+ 分支修复提交（见下）。

| 项 | 值 |
| --- | --- |
| requirementId (taskId) | `7503312963403649024`（taskType REQUIREMENT，最终 COMPLETED） |
| 测试仓库 | `wanghehe123/hello-rd-bot`（私有，main@`6c4c1fa4`，Node 基线服务 + node:test） |
| 需求 | 新增 GET /healthz 返回固定 JSON 并补真实 node:test |
| stageRunId / attempt | REQUIREMENT_REVIEWER `7503312965236559872` · SOLUTION_ARCHITECT `7503312965236559873` · CODING_AGENT `7503312965236559874` · QA_AGENT `7503312965236559875`（均 attempt 1，SUCCEEDED） |
| 关键 commandId | HOST_VERIFY `7503314526264233984` · MANAGER_DECIDE×2 `7503314543729315840`/`7503315612249231360` · DETERMINISTIC_REVIEW `7503315613700460544` 前 · PUBLICATION `7503315614094725120` · COMPLETION `7503315664032108544`（全链 20 条命令 SUCCEEDED） |
| HOST_VERIFY | run `7503314539073638400` SUCCEEDED（docs_only=false，真实构建回放） |
| QA criteriaId | `AC-001` SUCCESS，evidenceArtifactIds 含 `qa-evidence/console/…`、`qa-evidence/network/…`、`qa-evidence/traces/healthz-browser.zip`、`qa-evidence/screenshots/healthz-desktop-1440x900.png` + `healthz-mobile-390x844.png`（23 件 QA 产物） |
| publication | operation `sha256:2ea5a918…`，status COMMITTED，remote_head `19700af1`，PR `https://github.com/wanghehe123/hello-rd-bot/pull/1`（OPEN），work branch `requirement/7503312963403649024` |
| completion binding | audit_run `7503315665424617472`，state_version 7，hash `sha256:1b2e88a470c97…` |
| PR 核验 | diff 仅 `server.js`（+5 /healthz 路由）与 `test/healthz.test.js`（新文件，ephemeral port 真请求断言）；PR 分支本地 clone 后 `npm test` fail 0 |

G12 过程中发现并修复的真实缺陷（全部带回归测试，已入分支）：

1. **Pi 执行器凭据解析装配缺口**：`AgentRuntimeExecutorConfiguration` 把
   `AuthEnvironmentResolver.system()` 硬编码给 Pi executor，管理台保存的供应商 key
   对 Docker 自托管路径完全失效 → 改为优先 `StoredThenSystemAuthEnvironmentResolver`
   （无凭据服务时回落 system）；新增 `PiExecutorCredentialResolverWiringTest` 3/3。
2. **pi-coding-agent 0.85.1 副作用**：anthropic-messages 改走 `beta.messages`（URL 带
   `?beta=true`），relay sidecar `providerPath` 拒绝一切 query → agent 全部 400。修复为
   「剥 query 继续转发」（query 从不透传 Host/上游，path allowlist 语义不变）；sidecar
   测试同步改写，Pi 桥 111/111，两张 Pi 镜像按规则重建。
3. **容器内 git 认证缺口**：Docker 路径无宿主 gh/keychain，私有仓库 clone/PUSH 无法认证 →
   `GIT_CONFIG_GLOBAL` + helper 脚本读容器 `GITHUB_PAT`（无明文入库）；
   `docker-compose.yml` 增加 `GITHUB_PAT`/`GH_TOKEN` 传递。
4. `acceptance.sh` 两处 heredoc/断言缺陷修复（GID 探测、迁移幂等断言）。

## Security and dependency triage

- 历史扫描：gitleaks 8.30.1 全量 354 commits，24 处命中逐条判定为 mock fixture /
  测试自造 PEM / 已删文件历史 blob；唯一真实值是一条 Feishu wiki 节点 ID
  （文档标识符，非访问凭据，smoke 门控测试内使用）。
- 依赖：npm 面清零（升级明细见
  `docs/superpowers/qa/2026-09-08-open-source-dependency-triage.md`）；Maven/镜像扫描
  受本机 registry 故障阻断，作为 G10 open item 交由 CI（`.github/workflows/ci.yml`
  的 `dependencies` job）在正常网络复核。
- 固定 token / `StrictHostKeyChecking=no` / 全局 Git 改写 / 个人入口：仓库已清零
  （CI `license-and-secrets` job 持续钉住）。

## Restart/data preservation

acceptance.sh Phase D（本 run）：ledger 计数与 token 前后一致，restart + `up --wait`
后 `/admin` 200。普通 `down` 保留卷（launcher 测试 + 真机验证）。

## Known limitations

- 本机网络对 Docker Hub/ghcr.io/deps.dev 的 registry 路径故障：镜像基础层经 digest
  钉住与 OCI 布局命名上下文供给构建；G10 扫描移交 CI。该限制属验收环境，不属于产品。
- CI（T12，SHOULD）已创建 workflow 但尚未有 GitHub run；README 未展示 build badge。
- macOS Docker Desktop arm64 是本次唯一实测平台；Linux 形态未经真机验收（README 已如实声明）。
- G12 运行使用真实第三方服务（opencode.ai zen 通道 + GitHub）；其可用性不属于本项目 SLA。

## Release decision

**可开源（MUST 全过；G10 附 CI 复核条件）**：G01–G09、G11–G13 全部 PASS。
G12 真实交付已由维护者授权执行完毕（真实 PR `hello-rd-bot#1`，四角色 + HOST_VERIFY +
审计 + 发布全链 SUCCEEDED，PR 测试本地复跑通过）。唯一开放项是 G10 的 Maven/镜像
漏洞扫描在本机网络故障下无法执行，已交由 `.github/workflows/ci.yml` 的
`dependencies` job 在正常网络复核（SHOULD 级 CI 首跑后自动闭环）；这不是
「零漏洞」声明，而是记录在案的环境限制与复查条件。
