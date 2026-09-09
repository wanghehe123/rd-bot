# RD-Bot Personal Open Source Release Acceptance

- **Isolated A/B/D acceptance SHA:** `a53c117caeec84e03aef21b81b662ebb8c5c8dfe`
- **G12 closure SHA:** `0ac3ca559c1d3e3072e1609e13fb36f1fe13a6fd`（分支 `feat/open-source-release-prep`）
- **Post-review repair SHA:** `8437fccdd2aaf6fd1ca77690093430ad1f6935b6`
- **Started/finished at:** 2026-09-09 05:05 → 05:35；G12/closure 复核完成于 16:40；发布前复审、修复与回归完成于 17:44 (UTC+8)
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
| G02 Public hygiene | **BLOCKED** | E02：当前树与 release commits 经 gitleaks 复核无确认凭据；个人简历/面试材料/会话快照/历史截图/个人 VM 脚本已从当前树移出 | 被删除的 `resume_optimized.md`、历史截图、interview/cloud-server 材料仍可从现有 Git 历史读取；不得直接把当前私有仓库切为 public，需以干净 snapshot/新历史发布或另行获准重写历史 |
| G03 Local security | PASS | E03：`ApplicationSecureDefaultsTest`（loopback 默认 + 四开关关闭 + token 空）；`SecurityPostureLogger` 启动摘要无凭据；实测宿主仅 `127.0.0.1:18080` 监听（lsof）；mutation 空 token 503 | — |
| G04 Image bootstrap | PASS | E04：唯一 tag 空机链 Pi→QA→app 全成功（Pi `--no-cache`）；Chromium headless `CHROMIUM_LAUNCH_OK`；QA 镜像无 socket、无凭据 env；policy tests 全绿 | 构建需绕过本机 registry 故障（base digest 钉住 / oci-layout 命名上下文 / DOCKER_CLI_URL 覆盖）——公共网络环境直接用默认 URL |
| G05 Full compose | PASS | E06：contract 测试 13/13；真机空卷 up 全 healthy；迁移首跑 applied=24、二跑 applied=0 skipped=24（p8_zz 种子只执行一次）；`down` 保留卷 | — |
| G06 Agent plumbing | PASS | E06：后端容器经 socket 创建 alpine 探针容器写 same-path workspace，宿主回读成功；Pi task 容器 task-local 网络隔离由 51 项 `DockerPiAgentExecutorTest` 钉住 | 真实 agent relay 流量验证归 G12 |
| G07 Operator UX | PASS | E07：launcher 行为测试 8 项（stub docker）+ acceptance env 隔离测试；0600 env、不覆盖、外部 workspace purge 在 Compose/删除前拒绝、日常 runtime.env 不被验收 smoke 改写；build 失败非零 + 诊断 | — |
| G08 Admin onboarding | PASS | E08：controller 测试 7/7；`/admin`、`/admin/model-providers`、`/admin/dashboard` 直达/刷新 200；API 404 仍是 404；onboarding checklist 展示真实 provider 配置状态；README/launcher 明确 GitHub PAT 只能在 runtime.env 配置 | 缺凭据在受影响 Agent/Git 操作处显式失败；不再声称任务创建一定被阻断 |
| G09 Regression | PASS | E09：全量 Maven **1359 tests, 0 failures, 0 errors, 66 skipped**（显式 real-smoke）；core suite `test-open-source-core.sh` exit 0；无 failure.ignore | 66 skipped 均为需真实中间件/凭据的 smoke（按计划显式跳过） |
| G10 Dependencies | **BLOCKED** | E10：npm 双扫描器 0 命中；fresh CycloneDX+OSV 聚合 Maven SBOM 扫描得到 82 个唯一 advisory ID，其中 41 组 CVSS≥7、涉及 13 个 runtime package entries（Tomcat 最高 9.8）；Trivy 三镜像扫描已执行并修复 Pi/QA 每张 2 个可修 GnuTLS Critical | Maven runtime-path High/Critical 尚未升级；Pi/QA 剩余无修复版本的 findings 尚未逐项适用性分流；postgres/redis/minio 镜像未扫。现有 CI 仅扫 source，不闭环镜像扫描 |
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
| `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict` | 0 | 初始验收 22 items passed；G12 closure SHA 上复跑为当前集合 21/21 |
| `bash scripts/docker/tests/compose_contract_test.sh` | 0 | resolved compose 13 项合同断言 |
| `bash scripts/docker/tests/launcher_test.sh` | 0 | 启动器行为 8 项（stub docker，新增 purge 边界 + GITHUB_PAT 槽位） |
| `bash scripts/docker/tests/acceptance_env_isolation_test.sh` | 0 | 验收失败路径仍保持部署 runtime.env 字节不变，并使用 run-local env |
| `ACCEPTANCE_TAG=acceptance-t13-050529 ACCEPTANCE_PROJECT=rd-bot-oss-acceptance-t13-050529 bash scripts/docker/acceptance.sh` | 0 | 隔离栈 A/B/D smoke PASS（E13） |
| `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s .agents/skills/rd-bot-project-autopilot/scripts/tests` | 0 | autopilot 脚本回归（本机 run 6 同批执行） |
| `gitleaks git . --redact` | findings=24→0 confirmed | 历史 354 commits 逐条判定（E02） |

G12 closure SHA `0ac3ca55` 与 post-review repair SHA `8437fccd` 上的 fresh push gate
（2026-09-09 16:37–17:44，UTC+8）：

| Command/check | Exit | Result |
| --- | --- | --- |
| `env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./scripts/test-open-source-core.sh` | 0 | backend focused suite、frontend 265/265 + typecheck/build、Pi bridge 111/111、OpenSpec strict 21/21 全通过；沙箱内首次运行仅因 Mockito/Byte Buddy 禁止 self-attach 失败，放开该环境限制后同命令通过 |
| `env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./mvnw -pl bootstrap -am -Dtest=PiExecutorCredentialResolverWiringTest,AgentRuntimeExecutorConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | wiring 3/3 + 关联配置 3/3 |
| `env -u SPRING_CONFIG_ADDITIONAL_LOCATION ./mvnw -pl exec -am -Dtest=DockerPiAgentExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0 | Docker Pi 执行器 51/51 |
| `bash scripts/docker/tests/compose_contract_test.sh && bash scripts/docker/tests/launcher_test.sh && bash scripts/docker/tests/acceptance_env_isolation_test.sh` | 0 | Compose 13 项、launcher 8 项、acceptance env 隔离全部通过 |
| `docker run --rm --entrypoint sha256sum rd-bot/pi-agent:oss-test /opt/rd-pi-bridge/src/rd-pi-relay-sidecar.mjs`（QA 镜像同命令） | 0 | Pi 与 QA 镜像内 sidecar 均为 `351f6c957197f36df9a22d8e37017df74e7cb9b52ff4d72cd7237dc3220e5151`，与 closure SHA 工作树源码一致 |
| QA 镜像内 Chromium headless launch | 0 | `CHROMIUM_LAUNCH_OK`；修复后 Pi digest `sha256:e618f6fd4bb220489f8fa16eddb2617f4fe55fcecde373e5184b41c68b5e0f90`，QA digest `sha256:2df4862ced6c904e7d8b15456c3dc966114f398819dd15b5e768e547348d6a89` |
| `gitleaks git . --redact --log-opts='origin/main..HEAD' --no-banner` | 0 | 11 个 release commits，0 leaks |
| CycloneDX aggregate SBOM → `osv-scanner scan source --sbom /private/tmp/bom.json --format json` | 1（findings） | 113 packages；82 unique advisory IDs / 83 groups；41 groups CVSS≥7，13 个 runtime package entries |
| `trivy image --skip-db-update --pkg-types os --scanners vuln --severity HIGH,CRITICAL --format json ...` | 0（scanner） | app/Pi/QA 均完成 arm64 OS-package 扫描；exit 0 只表示扫描器成功，不表示零 findings |

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
| QA criteriaId | `AC-001` PASSED；acceptanceResults 的 17 个去重引用覆盖 `qa-evidence/console/…`、`qa-evidence/network/…`、`qa-evidence/traces/healthz-browser.zip`、`qa-evidence/screenshots/healthz-desktop-1440x900.png` + `healthz-mobile-390x844.png`；数据库持久化 18 个 evidence objects（含 manifest），该 QA stage 共 23 件 Pi execution artifacts |
| publication | operation `sha256:2ea5a918a1a9c0ef7268faae00c2c2e3bf93822e0327a124e45e61d669c6f5b3`，status COMMITTED，remote_head `19700af12232227de0965af3c3ef40083accaf25`，PR `https://github.com/wanghehe123/hello-rd-bot/pull/1`（OPEN），work branch `requirement/7503312963403649024` |
| completion binding | audit_run `7503315665424617472`，state_version 7，hash `sha256:1b2e88a470c97362de967456033dd7bee444440d67ca93bbf83845716c940c84` |
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
5. **发布复审发现的运维安全缺口**：acceptance smoke 原先无条件覆盖日常
   `deploy/docker/runtime.env`；`purge --yes` 可跟随 env 删除仓库外路径；launcher/README
   把 GitHub PAT 错写为管理台配置。现已改为 run-local acceptance env、purge 路径硬边界、
   生成显式空 `GITHUB_PAT=` 并给出真实配置入口；新增两类 shell 行为回归。
6. **镜像可修 Critical**：fresh Trivy 发现 Pi/QA 的 `libgnutls30` 各 2 个可修
   Critical；Pi 基础构建增加 Debian security upgrade，两张镜像按规则重建后
   `3.7.9-2+deb12u5 → 3.7.9-2+deb12u7`，fix-available findings 归零。

## Security and dependency triage

- 历史扫描：gitleaks 8.30.1 全量 354 commits，24 处命中逐条判定为 mock fixture /
  测试自造 PEM / 已删文件历史 blob；唯一真实值是一条 Feishu wiki 节点 ID
  （文档标识符，非访问凭据，smoke 门控测试内使用）。
- 依赖：npm 面清零；Maven 聚合 SBOM 与 app/Pi/QA 镜像已完成 fresh scan，结果确认
  G10 不能条件 PASS。完整计数、可修 GnuTLS 修复与剩余分流边界见
  `docs/superpowers/qa/2026-09-08-open-source-dependency-triage.md`。
- 固定 token / `StrictHostKeyChecking=no` / 全局 Git 改写 / 个人入口：仓库已清零
  （CI `license-and-secrets` job 持续钉住）。

## Restart/data preservation

acceptance.sh Phase D（本 run）：ledger 计数与 token 前后一致，restart + `up --wait`
后 `/admin` 200。普通 `down` 保留卷（launcher 测试 + 真机验证）。

## Known limitations

- 本机 registry 路径仍不稳定，但 Trivy DB 与 OSV SBOM 扫描已实际完成；不得再用网络
  限制把 G10 记为条件 PASS。
- CI（T12，SHOULD）已创建 workflow 但尚未有 GitHub run；README 未展示 build badge。
- macOS Docker Desktop arm64 是本次唯一实测平台；Linux 形态未经真机验收（README 已如实声明）。
- G12 运行使用真实第三方服务（opencode.ai zen 通道 + GitHub）；其可用性不属于本项目 SLA。

## Release decision

**暂不可开源；允许把功能分支推送到当前私有 origin 供复核。** G12 真实交付本身为
PASS：真实 PR `hello-rd-bot#1`、四角色、HOST_VERIFY、审计、发布与完成绑定均已闭环。
但发布总门禁仍有两个阻断：

1. **G10**：fresh Maven SBOM scan 已确认 supported runtime path 存在 High/Critical
   advisories；Pi/QA 无 fix findings 与基础设施镜像扫描也未闭环。
2. **G02-public-history**：当前树已清理，但现有私有仓库历史仍包含已删除的个人材料；
   公开发布必须使用干净 snapshot/新历史，不能直接切换现有仓库可见性。

因此本报告撤回先前「G10 附 CI 条件即可开源」的结论。现有 CI 未作任何新增或修改，
且它不包含镜像漏洞扫描，不能替代上述阻断项。
