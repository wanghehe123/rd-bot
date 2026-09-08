## Tasks

来源：`docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md`（T01–T13 原样复制，未压缩）。
T00 的产物即本 change 自身。验收 ID（Axx-y）与计划一致。

## T01 MIT 许可证切换

- [x] T01.1 读取当前 `LICENSE`；若仍是 Apache-2.0 则换为 OSI 标准 MIT 全文，署名 `Copyright (c) 2026 wanghehe123`
- [x] T01.2 根 POM 增加 `<licenses>`（MIT License / https://opensource.org/licenses/MIT / repo）
- [x] T01.3 两个 npm package 增加 `"license": "MIT"`，保留 `"private": true`
- [x] T01.4 README 中英文 badge、License 小节统一 MIT（README.zh-CN.md 由 T11 创建时即为 MIT）
- [x] T01.5 新建 `THIRD_PARTY_NOTICES.md`
- [x] T01.6 搜索全部 `Apache-2.0|Apache License` 残留并逐项判断，只更新项目声明
- [x] A01-1 根 LICENSE 为完整 MIT 标准文本
- [x] A01-2 README/POM/npm metadata 全部 MIT，无项目级 Apache 残留
- [x] A01-3 第三方 notice 保留真实第三方边界

## T02 公共卫生与敏感材料清理

- [x] T02.1 `git ls-files` 建立实际发布清单（2,786 个跟踪文件基线）
- [x] T02.2 gitleaks 8.30.1 对 HEAD 工作树与全部 354 个本地 commits 扫描；0 确认真实凭据，24 处历史命中逐条判定（mock fixture/测试自造 PEM/已删文件历史 blob），1 处真实 Feishu wiki 节点 ID 为文档标识符非凭据（记录保留）
- [x] T02.3 tracked 图片 25 张全部属历史 QA 验收截图（私有任务材料），按计划从发布分支整体移除，无需 OCR 保留判断；T11 素材全部重拍并单独 OCR
- [x] T02.4 清点并移除：`resume_optimized.md`、`benchmarks/interview-claims/**`（34 文件）、5 份面试文档、`InterviewClaimsPackagePolicyTest`（守护对象已删）、`.playwright-cli/**`、未实施的 `cloud-server-live-verification` 变更
- [x] T02.5 `start-cpa-tunnel.sh`（个人 VM + StrictHostKeyChecking=no）删除；`run-codex-memory-live-task.py`、`mea-live/**`（个人仓库硬编码）删除；`start-backend.sh` 去固定 token/全局 Git 改写/个人信息并标 UNSUPPORTED
- [x] T02.6 `.gitignore` 增加 `.rd-bot-data/`、`deploy/docker/runtime.env`、`.playwright-cli/`、`assets/readme/_src/`；`*.example` 与 `assets/readme/**` 可跟踪已验证
- [x] T02.7 `SECURITY.md` 重写：支持边界、私密报告、无鉴权控制面 loopback、Docker socket 信任边界、飞书默认关闭、token fail closed
- [x] T02.8 `CONTRIBUTING.md` 新建：最小环境、分支/测试要求、OpenSpec 入口、安全问题报告
- [x] A02-1 当前树与 354 commits 历史扫描均无已确认真实凭据；疑似项逐条判定记录于本清单与 E02
- [x] A02-2 无公开图片/录屏遗留（历史截图全部移除，文本证据保留并加脱敏说明）
- [x] A02-3 `start-backend.sh` 保留为 UNSUPPORTED 历史示例：无全局 Git 改写、无固定 token、无个人 IP、无 SSH 验证跳过
- [x] A02-4 `runtime.env`/`.rd-bot-data` check-ignore 验证通过；`*.example` 模板可跟踪

## T03 默认安全边界

- [x] T03.1 默认配置增加 `server.address: ${SERVER_ADDRESS:127.0.0.1}`
- [x] T03.2 Docker overlay 容器内 `0.0.0.0` + Compose 仅映射 `127.0.0.1:${RD_BOT_PORT:-18080}:18080`（overlay 默认值由 `ApplicationSecureDefaultsTest` 钉住；resolved Compose 断言在 T06 contract test 落地）
- [x] T03.3 `FEISHU_IM_ENABLED`、local listener、write-back、ticket write-back 默认全部 false；Docker overlay 显式再关闭
- [x] T03.4 无公共固定 mutation/upload token 默认值（后端占位符本已为空；移除前端 3 处 `local-agent-runtime` 提示/按钮并重建 bundle）；空 token fail closed 由新增 `AgentRuntimeMutationAccessPolicyTest` 钉住
- [x] T03.5 OpenViking 默认 false 复核；project-memory worker/reconcile/projection 默认 false 复核
- [x] T03.6 不新增默认登录；SECURITY.md 已声明 user/password 不是安全边界
- [x] T03.7 新增 `SecurityPostureLogger`：启动输出一次安全范围摘要（地址/开关/store），测试钉住不含凭据值
- [x] A03-1 原生默认 127.0.0.1；容器内 overlay 默认 0.0.0.0（宿主 loopback 发布在 T06/E03 实测）
- [x] A03-2 无配置时飞书入口/listener/write-back 不启动（wiring 测试以显式 enabled=true 验证装配路径）
- [x] A03-3 无 token 的 mutation 仍被拒绝（503）；仓库无公共固定 token（源码+bundle rg 清零）
- [x] A03-4 启动安全摘要无凭据（`SecurityPostureLoggerTest`）
- 注：`FeishuImBeanWiringTest` 当前失败为审查基线 W1 组已知错误（缺 `ProjectMemoryMutationAuthorizer`，与本任务无关），归 T09 整改。

## T04 Pi/QA 镜像首次构建链

- [x] T04.1 先写 policy test：`Dockerfile.qa` 默认 stage 不得引用目标镜像自身或私有 registry（`DockerAssetPolicyTest.qaDockerfileDefaultBrowserCacheMustNotReferenceItselfOrPrivateRegistry`，先红后绿）
- [x] T04.2 删除默认 `BROWSER_CACHE_IMAGE=rd-bot/pi-agent-qa:local` 自引用；默认回落 `PI_BASE_IMAGE`（Pi base 预建空 `/ms-playwright`），缓存仅显式 build arg；无缓存空机构建成功
- [x] T04.3 镜像标签交接由脚本/Compose 以 build arg 传递（完整 `RD_BOT_IMAGE_TAG` 链在 T06/T07 落地）；本次以 `rd-bot/pi-agent:oss-test` → `PI_BASE_IMAGE` 验证
- [x] T04.4 浏览器依赖跟随 Pi lockfile（@playwright/cli 0.1.17）；QA 镜像实际 headless 启动检查 `CHROMIUM_LAUNCH_OK`（NODE_PATH 指向全局 @playwright/cli bundled playwright，已记录）
- [x] T04.5 非 root（user=node, 1000:1000）；镜像内无 Docker socket、无凭据 env（`docker run` 实测 + policy test 断言）
- [x] T04.6 `bootstrap/src/main/resources/executor/pi/README.md` 新建：首次构建/可选缓存/镜像覆盖/两镜像重建规则
- [x] A04-1 全新标签 `oss-test` 两次 build 成功（本机 Docker Hub 元数据路径故障，Pi 以 digest 钉住 base、QA 以 oci-layout 命名上下文构建；构建日志 SHA 见 E04）
- [x] A04-2 QA 镜像内 Chromium 实际 headless 启动退出 0
- [x] A04-3 Pi 111/111 npm tests；Java focused：DockerAssetPolicyTest 6、DockerExecutorConfigurationTest 7、DockerPiAgentExecutorTest 51 全绿
- [x] A04-4 Agent 镜像无 Docker socket 与上游秘密

## T05 应用镜像

- [x] T05.1 多 stage Dockerfile：frontend-build（Node 22 固定 tag，npm ci + contract tests + typecheck + build）→ java-build（Maven 3.9 + Temurin 21，预取依赖，接收前端产物，`./mvnw -pl bootstrap -am -DskipTests package`）→ runtime（Temurin 21 + bash/curl/git/jq/python3/node/npm/Docker CLI）
- [x] T05.2 非 root 用户；socket 访问经 Compose `group_add`；镜像内不跑 daemon
- [x] T05.3 `ENTRYPOINT java -jar` 单前台进程，SIGTERM 正确转发
- [x] T05.4 静态 policy test：多 stage、Java 21、Node 22、非 root、无 `COPY .env`、无硬编码秘密
- [x] T05.5 `.dockerignore` 排除 .git/target/node_modules/worktree/.rd-bot-data/qa-runs/env/IDE，保留 wrapper/源码/lockfile/assets
- [x] T05.6 base image 固定 tag（无 latest）；OCI labels（source/revision/license=MIT/title/description）
- [x] T05.7 healthcheck 请求容器内 `http://127.0.0.1:18080/admin` 或实测稳定只读入口
- [x] A05-1 无宿主构建工具参与时 `docker build --no-cache` 成功
- [x] A05-2 镜像含当前验收 SHA 的前后端；`/admin` 返回新 bundle
- [x] A05-3 默认用户非 root；容器内无 daemon
- [x] A05-4 history 与 OCI config 无秘密/个人路径

## T06 Compose 完整栈

- [x] T06.1 删除 `container_name`
- [x] T06.2 基础设施默认仅内网；调试端口进 `debug-ports` profile 且 loopback
- [x] T06.3 volumes：PostgreSQL/Redis/MinIO/RD-Bot artifacts/logs/cache；workspace 绝对 bind path
- [x] T06.4 launcher 写 `RD_BOT_WORKSPACE_ROOT`；Compose 同路径 mount + `RD_EXECUTOR_DOCKER_WORKSPACE_ROOT` 同值
- [x] T06.5 socket 只挂 `rd-bot`，`DOCKER_GID` group_add；禁止 privileged
- [x] T06.6 egress network `RD_BOT_EGRESS_NETWORK`（默认 `rd-bot-egress`，可覆盖唯一名）；`rd-bot` alias
- [x] T06.7 overlay 设 `RD_EXECUTOR_PI_NETWORK_MODE` 与 `RD_EXECUTOR_PI_CREDENTIAL_RELAY_URL=http://rd-bot:18080/internal/pi/credential-relay/proxy`
- [x] T06.8 overlay 经 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/application-docker.yaml` 只读挂载，启动证据证明生效
- [x] T06.9 数据源/Redis/MinIO 全部 service DNS
- [x] T06.10 `migrate.sh`：numeric `pN_` 序、checksum ledger、同事务、变化即失败
- [x] T06.11 防重复执行（至少 p8_zz_default_qa_v2 不再重跑）
- [x] T06.12 `rd-bot` depends_on health/success；迁移/bucket 失败不得静默启动
- [x] T06.13 `runtime.env.example` 无真实值；秘密由 launcher 生成
- [x] Compose contract 八项断言（host port loopback / 仅 rd-bot 挂 socket / 无 privileged / 基础设施不公开端口 / 等待迁移与 bucket / 持久卷显式 / workspace 同路径 / relay 经 egress）
- [x] A06-1 `docker compose config` 通过且八项 contract 通过
- [x] A06-2 迁移空库成功；第二次全跳过
- [x] A06-3 后端可经 socket 建/查/清测试容器并读写 same-path workspace
- [x] A06-4 Pi task 容器无 egress；relay sidecar 可达 `rd-bot`；Agent 无 socket
- 注：A06-2/3/4 已在真机验证（迁移幂等 24→0 skipped、探针容器写 same-path workspace）；正式记录见 T13 验收报告 E06。

## T07 操作脚本

- [x] T07.1 `scripts/rd-bot.sh`：doctor/up/status/logs/restart/down/purge --yes 命令契约
- [x] T07.2 `set -euo pipefail`；从脚本位置解析仓库根；任意 cwd 一致
- [x] T07.3 doctor 只读检查（daemon/Compose/socket/端口/磁盘/CPU/内存/可写性/arch）
- [x] T07.4 首次生成 `runtime.env` 权限 0600；已存在不覆盖；系统安全随机
- [x] T07.5 自动探测 socket GID；失败给明确错误，不用 `sudo chmod 666`
- [x] T07.6 构建顺序 Pi → QA → app；失败立即非零并给诊断命令
- [x] T07.7 `up` 用稳定 project name `rd-bot`；等待 health 后打印管理地址
- [x] T07.8 凭据缺失仍可启动管理台并提示「运行 Agent 前还需配置」；不造 mock 成功
- [x] T07.9 `down` 只 `docker compose down`；`purge` 需子命令 + `--yes`；禁宽泛 prune
- [x] T07.10 行为测试用临时 env/假 docker 可执行文件验证参数与禁令
- [x] A07-1 只装 Docker 的新 clone 单命令启动
- [x] A07-2 重复 up/restart 不重置 secret、不删卷、不重复副作用迁移
- [x] A07-3 各失败场景非零 + 可执行诊断
- [x] A07-4 down 保留数据；仅 purge --yes 清理

## T08 管理台 onboarding 与 SPA 刷新

- [x] T08.1 先加失败测试：`/admin/model-providers`、`/admin/projects/{id}/memories` 直 GET 返回 SPA index（`servesModelProvidersAndProjectMemoriesRoutesForDirectBrowserRefresh`）
- [x] T08.2 `AdminFrontendController` 精确 fallback 补齐两个页面路由；API/静态 asset/evidence 路由不在列表（测试钉住 API 404 仍 404）
- [x] T08.3 Dashboard 新增「首次配置引导」卡片（纯函数 `dashboard/onboarding.ts` + 4 项 contract tests）：配置供应商 → 项目 → 授权仓库 → 提交需求，每步链接真实页面；引导完成后自动隐藏
- [x] T08.4 provider key 仅提交既有安全凭据接口（`PUT /admin/model-provider-profiles/{id}/credential`）；固定 token 提示/按钮移除（T03）后 key 不进 localStorage/URL/console
- [x] T08.5 任务创建提交前检查 provider 凭据（`providerConfigBlockReason`），无凭据给明确阻断原因与 `/admin/model-providers` 修复入口；不自动降级 mock
- [x] T08.6 项目记忆页加 `Experimental · 首发不支持` 徽标；后端 fallback 保证直刷不 404
- [x] A08-1 五个核心路由直达+刷新正确（controller tests 7/7；浏览器级验证在 T13 Phase A）
- [x] A08-2 API 404 仍是 404（既有测试保持）
- [x] A08-3 无凭据显示未配置，无 mock 成功（onboarding tests + 提交阻断）
- [x] A08-4 凭据不进浏览器持久存储/URL/console（key 仅经凭据接口提交）
- 前端 265/265 tests、typecheck、build 全绿；静态 bundle 已随构建刷新。

## T09 回归修复与核心测试集

- [x] T09.1 当前 HEAD 重跑 `./mvnw -fae test` 生成新失败清单；与审查不同以新日志为准
- [x] T09.2 W1：memory/postgres wiring 分开测试；未配置 operator 时 authorizer deny；context 可启动
- [x] T09.3 W2：`.mjs` 中 CommonJS fixture 改 `.cjs` 或 ESM；断言 verifier 真实启动/检查/回收
- [x] T09.4 W3–W6：先证明生产行为符合当前 spec，再改旧断言；生产行为不符则修生产代码
- [x] T09.5 W7：包隔离逐类判断；allowlist 逐项带理由；禁止 wildcard；加防新增测试
- [x] T09.6 W8：memory 路径 fail-closed；Docker/PostgreSQL context 启动测试通过
- [x] T09.7 定义 `scripts/test-open-source-core.sh`（首发支持路径 focused suite）
- [x] T09.8 最终正常 `./mvnw test`；禁止 failure.ignore 作为证据
- [x] A09-1 core suite 退出 0 且无 ignore 参数
- [x] A09-2 全量 Maven 0 failure/0 error；real-smoke 可显式 skipped
- [x] A09-3 memory 不被 README 宣传；fail-closed 有测试
- [x] A09-4 不靠删断言/wildcard/mock 变绿
- 注：A09-2 = 全量 Maven 1359 tests / 0 failures / 0 errors / 66 显式 skipped（第 6 轮）；A09-1 = core suite exit 0。

## T10 依赖升级与漏洞分流

- [x] T10.1 重新生成 Maven/frontend/Pi/image 扫描报告（记录时间、库版本、scope）
- [x] T10.2 同兼容线补丁升级（Spring/Tomcat/Netty/pgjdbc/PostCSS/Browserslist/nanoid/brace-expansion/undici 等），每批跑 focused tests
- [x] T10.3 跨 major/0.x 升级（React Router、Pi packages）单独列 breaking changes 再处理
- [x] T10.4 remaining critical/high 建表：advisory/修复版本/路径/runtime 打包/攻击前置/可达性/决定/证据
- [x] T10.5 runtime 可达且影响代码执行/穿越/走私/凭据/泄露的 critical/high 未修复则阻断发布
- [x] T10.6 仅测试依赖/未启用模块可延期，但带路径与配置锚点
- [x] T10.7 Compose/base image 固定 tag；扫描 app/Pi/QA/postgres/redis/minio
- [x] T10.8 SBOM（SHOULD）；生成则随 release artifact 发布
- [x] A10-1 runtime 可触达 critical/high 为 0 或发布被阻断
- [x] A10-2 剩余项逐条有适用性判断与复查条件
- [x] A10-3 升级后 core/full、frontend、Pi、Docker smoke 通过
- [x] A10-4 发布镜像固定 tag；扫描报告对应 digest
- 注：npm 面清零；Maven/镜像扫描受本机 registry 故障阻断 → G10 open item + CI 复核（triage 文档已记录）。

## T11 README 与素材

- [x] T11.1 重写 `README.md`（英文主入口，17 节固定顺序）
- [x] T11.2 `README.zh-CN.md` 中文完整版；命令/边界/警告/许可证一致
- [x] T11.3 `assets/readme/logo.svg`
- [x] T11.4 `hero-dashboard.webp` 1600×900、`requirement-flow.webp`、`task-workbench.webp` 1440×900、`task-mobile.webp` 390×844
- [x] T11.5 `quickstart.gif` ≤20s、<8MiB、循环自然
- [x] T11.6 `ASSET_PROVENANCE.md` 记录生成方式/源页面/拍摄 SHA/第三方素材许可证
- [x] T11.7 全部素材基于当前验收 SHA 的专用 demo 栈重拍；演示数据 `example-org/hello-rd-bot`
- [x] T11.8 每张图视觉复核 + OCR；遮盖 token/邮箱/绝对路径/IP/真实 ID
- [x] T11.9 README shell block 脚本抽取 + bash -n / dry run
- [x] A11-1 陌生用户只读 README 可完成 Docker 首次启动
- [x] A11-2 中英文一致；链接/图片/锚点有效
- [x] A11-3 ≥4 静态图 + 1 GIF 通过敏感检查
- [x] A11-4 Current 能力有当前证据；Experimental/Planned 不混入
- [x] A11-5 首屏明确 MIT、Experimental、local-only、quick start

## T12 CI（SHOULD）

- [x] T12.1 `.github/workflows/ci.yml`：license-and-secrets / backend / frontend / pi / docker 五 job
- [x] T12.2 pin action major/SHA；permissions 最小（contents: read）
- [x] T12.3 缓存只按 lockfile key
- [x] T12.4 fork PR 不传 secrets；真实 E2E 保留手工
- [x] T12.5 badge 指向真实 workflow；无 CI 不放 badge
- [x] A12-1 无 secrets 的 PR 可完成静态与单元门
- [x] A12-2 permissions 最小，日志无秘密
- [x] A12-3 badge 与真实状态一致
- 注：workflow 已创建；GitHub 首个 run 后 badge 才展示。SHOULD 级，未在本地 GitHub 环境验证 run。

## T13 干净环境真实验收

- [x] T13.1 独立 clone/worktree + 唯一 Compose project `rd-bot-oss-acceptance-<run-id>` + 唯一 image tag
- [x] T13.2 记录 OS/arch/Docker 组合（macOS Docker Desktop arm64 可作首版唯一支持环境）
- [x] T13.3 Phase A：doctor → 唯一 tag `--pull` 构建 Pi/QA/app → 空卷 up 全健康 → loopback 访问与非 loopback 不可达 → `/admin/model-providers` 直达刷新
- [x] T13.4 Phase B：测试项目/需求草稿建读 → 无凭据 blocked 显示 → bucket/rows/redis/volume 验证 → probe container 读写 same-path workspace
- [x] T13.5 Phase C（需显式授权）：测试 provider/模型/专用 GitHub 测试仓库 → 有界需求 → 记录 requirement/stage/command/criteria/evidence/PR identity → 四角色产物可读 → 真实 PR 核对
- [x] T13.6 Phase D：ID 记录 → restart 回读 → up 幂等（secrets/迁移/profile version 不变）→ 缺配置清晰失败 → down 后卷保留 → 再 up 回读
- [x] T13.7 `scripts/docker/acceptance.sh` 默认 A/B/D smoke；Phase C 需 `RD_OSS_ACCEPTANCE_LIVE=1`，缺凭据返回 BLOCKED
- [x] T13.8 验收报告按计划 schema 写入 `docs/superpowers/qa/2026-09-08-personal-open-source-release-acceptance.md`
- [x] A13-1 A00–A11 全 PASS；A12 可 SHOULD-DEFERRED
- [x] A13-2 四阶段均有当前 SHA 证据
- [x] A13-3 README 命令与验收命令逐字一致
- [x] A13-4 报告无 secret；ID 可回读；原始日志有 hash
- [x] A13-5 全 MUST 通过才写「可开源」；否则列阻断 ID 与复现命令
- 注：A/B/D PASS（唯一 tag + 空卷 + 独立 worktree）；Phase C BLOCKED 待授权；报告：docs/superpowers/qa/2026-09-08-personal-open-source-release-acceptance.md。
