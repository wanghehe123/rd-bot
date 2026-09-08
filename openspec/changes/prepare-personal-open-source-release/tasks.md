## Tasks

来源：`docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md`（T01–T13 原样复制，未压缩）。
T00 的产物即本 change 自身。验收 ID（Axx-y）与计划一致。

## T01 MIT 许可证切换

- [ ] T01.1 读取当前 `LICENSE`；若仍是 Apache-2.0 则换为 OSI 标准 MIT 全文，署名 `Copyright (c) 2026 wanghehe123`
- [ ] T01.2 根 POM 增加 `<licenses>`（MIT License / https://opensource.org/licenses/MIT / repo）
- [ ] T01.3 两个 npm package 增加 `"license": "MIT"`，保留 `"private": true`
- [ ] T01.4 README 中英文 badge、License 小节统一 MIT
- [ ] T01.5 新建 `THIRD_PARTY_NOTICES.md`
- [ ] T01.6 搜索全部 `Apache-2.0|Apache License` 残留并逐项判断，只更新项目声明
- [ ] A01-1 根 LICENSE 为完整 MIT 标准文本
- [ ] A01-2 README/POM/npm metadata 全部 MIT，无项目级 Apache 残留
- [ ] A01-3 第三方 notice 保留真实第三方边界

## T02 公共卫生与敏感材料清理

- [ ] T02.1 `git ls-files` 建立实际发布清单
- [ ] T02.2 对 HEAD 与全部本地 refs 运行专用 secret scanner，记录工具与规则集
- [ ] T02.3 图片/GIF/视频/PDF 人工检查或 OCR
- [ ] T02.4 清点 `.playwright-cli/*.yml`、个人简历与历史验收材料；私有内容删除，fixture 用 `example-*`
- [ ] T02.5 `deploy/cloud-server/start-backend.sh`、`start-cpa-tunnel.sh` 移出推荐入口或放入 unsupported 区并去个人化
- [ ] T02.6 `.gitignore` 覆盖运行时 env/工作区/缓存/临时日志/生成截图源/本地数据目录，保留 `*.example` 与 `assets/readme/**`
- [ ] T02.7 `SECURITY.md` 写明支持边界、私密报告方式、loopback 限制、Docker socket 风险、飞书未支持
- [ ] T02.8 `CONTRIBUTING.md` 给出最小开发环境、分支/测试要求、OpenSpec 入口
- [ ] A02-1 当前树与历史扫描无已确认真实凭据；疑似项逐条有处置
- [ ] A02-2 公开图片/录屏无敏感数据且有来源记录
- [ ] A02-3 推荐部署入口不改全局 Git、不跳过 SSH 验证、不连个人服务器
- [ ] A02-4 本地运行文件被忽略，模板与 README 素材可跟踪

## T03 默认安全边界

- [ ] T03.1 默认配置增加 `server.address: ${SERVER_ADDRESS:127.0.0.1}`
- [ ] T03.2 Docker overlay 容器内 `0.0.0.0` + Compose 仅映射 `127.0.0.1:${RD_BOT_PORT:-18080}:18080`，两层都有测试
- [ ] T03.3 `FEISHU_IM_ENABLED`、local listener、write-back 默认全部 false；Docker 模板显式再关闭
- [ ] T03.4 删除全部公开固定 mutation/upload token 默认值；空 token 保持 fail closed
- [ ] T03.5 OpenViking、project-memory worker/reconcile/projection 保持默认关闭
- [ ] T03.6 不新增默认登录；user/password 模块不在 README 宣传为安全认证
- [ ] T03.7 启动日志输出一次安全范围摘要（无凭据内容）
- [ ] A03-1 原生默认 127.0.0.1；容器内全接口但宿主仅 loopback
- [ ] A03-2 无配置时飞书入口/listener/write-back 不启动
- [ ] A03-3 无 token 的 mutation 仍被拒绝；仓库无公共固定 token
- [ ] A03-4 启动日志无凭据泄露

## T04 Pi/QA 镜像首次构建链

- [ ] T04.1 先写 policy test：`Dockerfile.qa` 默认 stage 不得引用目标镜像自身或私有 registry
- [ ] T04.2 删除默认 `BROWSER_CACHE_IMAGE=rd-bot/pi-agent-qa:local` 自引用；默认从 Pi base 安装 Chromium，缓存仅显式 build arg
- [ ] T04.3 Pi/QA 标签由同一 `RD_BOT_IMAGE_TAG` 生成；Compose 先构建 Pi 再传给 QA 的 `PI_BASE_IMAGE`
- [ ] T04.4 浏览器依赖跟随当前 Pi lockfile；镜像构建执行真实 Playwright headless 启动检查
- [ ] T04.5 保持 `/work/*` 权限与非 root Agent 用户；无 Docker socket / 上游秘密进镜像
- [ ] T04.6 更新 Pi README：首次构建、可选缓存、网络失败提示、两镜像重建规则
- [ ] A04-1 全新标签无旧镜像时两次 build 成功
- [ ] A04-2 QA 镜像内 Chromium 实际 headless 启动退出 0
- [ ] A04-3 Pi 111 项测试与规定 Java focused tests 通过
- [ ] A04-4 Agent 镜像无 Docker socket 与上游秘密

## T05 应用镜像

- [ ] T05.1 多 stage Dockerfile：frontend-build（Node 22 固定 tag，npm ci + contract tests + typecheck + build）→ java-build（Maven 3.9 + Temurin 21，预取依赖，接收前端产物，`./mvnw -pl bootstrap -am -DskipTests package`）→ runtime（Temurin 21 + bash/curl/git/jq/python3/node/npm/Docker CLI）
- [ ] T05.2 非 root 用户；socket 访问经 Compose `group_add`；镜像内不跑 daemon
- [ ] T05.3 `ENTRYPOINT java -jar` 单前台进程，SIGTERM 正确转发
- [ ] T05.4 静态 policy test：多 stage、Java 21、Node 22、非 root、无 `COPY .env`、无硬编码秘密
- [ ] T05.5 `.dockerignore` 排除 .git/target/node_modules/worktree/.rd-bot-data/qa-runs/env/IDE，保留 wrapper/源码/lockfile/assets
- [ ] T05.6 base image 固定 tag（无 latest）；OCI labels（source/revision/license=MIT/title/description）
- [ ] T05.7 healthcheck 请求容器内 `http://127.0.0.1:18080/admin` 或实测稳定只读入口
- [ ] A05-1 无宿主构建工具参与时 `docker build --no-cache` 成功
- [ ] A05-2 镜像含当前验收 SHA 的前后端；`/admin` 返回新 bundle
- [ ] A05-3 默认用户非 root；容器内无 daemon
- [ ] A05-4 history 与 OCI config 无秘密/个人路径

## T06 Compose 完整栈

- [ ] T06.1 删除 `container_name`
- [ ] T06.2 基础设施默认仅内网；调试端口进 `debug-ports` profile 且 loopback
- [ ] T06.3 volumes：PostgreSQL/Redis/MinIO/RD-Bot artifacts/logs/cache；workspace 绝对 bind path
- [ ] T06.4 launcher 写 `RD_BOT_WORKSPACE_ROOT`；Compose 同路径 mount + `RD_EXECUTOR_DOCKER_WORKSPACE_ROOT` 同值
- [ ] T06.5 socket 只挂 `rd-bot`，`DOCKER_GID` group_add；禁止 privileged
- [ ] T06.6 egress network `RD_BOT_EGRESS_NETWORK`（默认 `rd-bot-egress`，可覆盖唯一名）；`rd-bot` alias
- [ ] T06.7 overlay 设 `RD_EXECUTOR_PI_NETWORK_MODE` 与 `RD_EXECUTOR_PI_CREDENTIAL_RELAY_URL=http://rd-bot:18080/internal/pi/credential-relay/proxy`
- [ ] T06.8 overlay 经 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/application-docker.yaml` 只读挂载，启动证据证明生效
- [ ] T06.9 数据源/Redis/MinIO 全部 service DNS
- [ ] T06.10 `migrate.sh`：numeric `pN_` 序、checksum ledger、同事务、变化即失败
- [ ] T06.11 防重复执行（至少 p8_zz_default_qa_v2 不再重跑）
- [ ] T06.12 `rd-bot` depends_on health/success；迁移/bucket 失败不得静默启动
- [ ] T06.13 `runtime.env.example` 无真实值；秘密由 launcher 生成
- [ ] Compose contract 八项断言（host port loopback / 仅 rd-bot 挂 socket / 无 privileged / 基础设施不公开端口 / 等待迁移与 bucket / 持久卷显式 / workspace 同路径 / relay 经 egress）
- [ ] A06-1 `docker compose config` 通过且八项 contract 通过
- [ ] A06-2 迁移空库成功；第二次全跳过
- [ ] A06-3 后端可经 socket 建/查/清测试容器并读写 same-path workspace
- [ ] A06-4 Pi task 容器无 egress；relay sidecar 可达 `rd-bot`；Agent 无 socket

## T07 操作脚本

- [ ] T07.1 `scripts/rd-bot.sh`：doctor/up/status/logs/restart/down/purge --yes 命令契约
- [ ] T07.2 `set -euo pipefail`；从脚本位置解析仓库根；任意 cwd 一致
- [ ] T07.3 doctor 只读检查（daemon/Compose/socket/端口/磁盘/CPU/内存/可写性/arch）
- [ ] T07.4 首次生成 `runtime.env` 权限 0600；已存在不覆盖；系统安全随机
- [ ] T07.5 自动探测 socket GID；失败给明确错误，不用 `sudo chmod 666`
- [ ] T07.6 构建顺序 Pi → QA → app；失败立即非零并给诊断命令
- [ ] T07.7 `up` 用稳定 project name `rd-bot`；等待 health 后打印管理地址
- [ ] T07.8 凭据缺失仍可启动管理台并提示「运行 Agent 前还需配置」；不造 mock 成功
- [ ] T07.9 `down` 只 `docker compose down`；`purge` 需子命令 + `--yes`；禁宽泛 prune
- [ ] T07.10 行为测试用临时 env/假 docker 可执行文件验证参数与禁令
- [ ] A07-1 只装 Docker 的新 clone 单命令启动
- [ ] A07-2 重复 up/restart 不重置 secret、不删卷、不重复副作用迁移
- [ ] A07-3 各失败场景非零 + 可执行诊断
- [ ] A07-4 down 保留数据；仅 purge --yes 清理

## T08 管理台 onboarding 与 SPA 刷新

- [ ] T08.1 先加失败测试：`/admin/model-providers`、`/admin/projects/{id}/memories` 直 GET 返回 SPA index
- [ ] T08.2 Spring controller 准确 fallback；API/静态 asset/evidence 路由不被吞
- [ ] T08.3 首页 checklist：配置 provider → 项目 → 授权仓库 → 提交需求，每步真实链接
- [ ] T08.4 provider key 只走安全凭据接口；不进 localStorage/URL/console/截图
- [ ] T08.5 未配置时创建任务前给明确错误与修复链接；不自动切 mock
- [ ] T08.6 project memory 页面标 Experimental/unsupported；直刷不 404
- [ ] A08-1 五个核心路由直达+刷新正确
- [ ] A08-2 API 404 仍是 404
- [ ] A08-3 无凭据显示未配置，无 mock 成功
- [ ] A08-4 凭据不进浏览器持久存储/URL/console/network body

## T09 回归修复与核心测试集

- [ ] T09.1 当前 HEAD 重跑 `./mvnw -fae test` 生成新失败清单；与审查不同以新日志为准
- [ ] T09.2 W1：memory/postgres wiring 分开测试；未配置 operator 时 authorizer deny；context 可启动
- [ ] T09.3 W2：`.mjs` 中 CommonJS fixture 改 `.cjs` 或 ESM；断言 verifier 真实启动/检查/回收
- [ ] T09.4 W3–W6：先证明生产行为符合当前 spec，再改旧断言；生产行为不符则修生产代码
- [ ] T09.5 W7：包隔离逐类判断；allowlist 逐项带理由；禁止 wildcard；加防新增测试
- [ ] T09.6 W8：memory 路径 fail-closed；Docker/PostgreSQL context 启动测试通过
- [ ] T09.7 定义 `scripts/test-open-source-core.sh`（首发支持路径 focused suite）
- [ ] T09.8 最终正常 `./mvnw test`；禁止 failure.ignore 作为证据
- [ ] A09-1 core suite 退出 0 且无 ignore 参数
- [ ] A09-2 全量 Maven 0 failure/0 error；real-smoke 可显式 skipped
- [ ] A09-3 memory 不被 README 宣传；fail-closed 有测试
- [ ] A09-4 不靠删断言/wildcard/mock 变绿

## T10 依赖升级与漏洞分流

- [ ] T10.1 重新生成 Maven/frontend/Pi/image 扫描报告（记录时间、库版本、scope）
- [ ] T10.2 同兼容线补丁升级（Spring/Tomcat/Netty/pgjdbc/PostCSS/Browserslist/nanoid/brace-expansion/undici 等），每批跑 focused tests
- [ ] T10.3 跨 major/0.x 升级（React Router、Pi packages）单独列 breaking changes 再处理
- [ ] T10.4 remaining critical/high 建表：advisory/修复版本/路径/runtime 打包/攻击前置/可达性/决定/证据
- [ ] T10.5 runtime 可达且影响代码执行/穿越/走私/凭据/泄露的 critical/high 未修复则阻断发布
- [ ] T10.6 仅测试依赖/未启用模块可延期，但带路径与配置锚点
- [ ] T10.7 Compose/base image 固定 tag；扫描 app/Pi/QA/postgres/redis/minio
- [ ] T10.8 SBOM（SHOULD）；生成则随 release artifact 发布
- [ ] A10-1 runtime 可触达 critical/high 为 0 或发布被阻断
- [ ] A10-2 剩余项逐条有适用性判断与复查条件
- [ ] A10-3 升级后 core/full、frontend、Pi、Docker smoke 通过
- [ ] A10-4 发布镜像固定 tag；扫描报告对应 digest

## T11 README 与素材

- [ ] T11.1 重写 `README.md`（英文主入口，17 节固定顺序）
- [ ] T11.2 `README.zh-CN.md` 中文完整版；命令/边界/警告/许可证一致
- [ ] T11.3 `assets/readme/logo.svg`
- [ ] T11.4 `hero-dashboard.webp` 1600×900、`requirement-flow.webp`、`task-workbench.webp` 1440×900、`task-mobile.webp` 390×844
- [ ] T11.5 `quickstart.gif` ≤20s、<8MiB、循环自然
- [ ] T11.6 `ASSET_PROVENANCE.md` 记录生成方式/源页面/拍摄 SHA/第三方素材许可证
- [ ] T11.7 全部素材基于当前验收 SHA 的专用 demo 栈重拍；演示数据 `example-org/hello-rd-bot`
- [ ] T11.8 每张图视觉复核 + OCR；遮盖 token/邮箱/绝对路径/IP/真实 ID
- [ ] T11.9 README shell block 脚本抽取 + bash -n / dry run
- [ ] A11-1 陌生用户只读 README 可完成 Docker 首次启动
- [ ] A11-2 中英文一致；链接/图片/锚点有效
- [ ] A11-3 ≥4 静态图 + 1 GIF 通过敏感检查
- [ ] A11-4 Current 能力有当前证据；Experimental/Planned 不混入
- [ ] A11-5 首屏明确 MIT、Experimental、local-only、quick start

## T12 CI（SHOULD）

- [ ] T12.1 `.github/workflows/ci.yml`：license-and-secrets / backend / frontend / pi / docker 五 job
- [ ] T12.2 pin action major/SHA；permissions 最小（contents: read）
- [ ] T12.3 缓存只按 lockfile key
- [ ] T12.4 fork PR 不传 secrets；真实 E2E 保留手工
- [ ] T12.5 badge 指向真实 workflow；无 CI 不放 badge
- [ ] A12-1 无 secrets 的 PR 可完成静态与单元门
- [ ] A12-2 permissions 最小，日志无秘密
- [ ] A12-3 badge 与真实状态一致

## T13 干净环境真实验收

- [ ] T13.1 独立 clone/worktree + 唯一 Compose project `rd-bot-oss-acceptance-<run-id>` + 唯一 image tag
- [ ] T13.2 记录 OS/arch/Docker 组合（macOS Docker Desktop arm64 可作首版唯一支持环境）
- [ ] T13.3 Phase A：doctor → 唯一 tag `--pull` 构建 Pi/QA/app → 空卷 up 全健康 → loopback 访问与非 loopback 不可达 → `/admin/model-providers` 直达刷新
- [ ] T13.4 Phase B：测试项目/需求草稿建读 → 无凭据 blocked 显示 → bucket/rows/redis/volume 验证 → probe container 读写 same-path workspace
- [ ] T13.5 Phase C（需显式授权）：测试 provider/模型/专用 GitHub 测试仓库 → 有界需求 → 记录 requirement/stage/command/criteria/evidence/PR identity → 四角色产物可读 → 真实 PR 核对
- [ ] T13.6 Phase D：ID 记录 → restart 回读 → up 幂等（secrets/迁移/profile version 不变）→ 缺配置清晰失败 → down 后卷保留 → 再 up 回读
- [ ] T13.7 `scripts/docker/acceptance.sh` 默认 A/B/D smoke；Phase C 需 `RD_OSS_ACCEPTANCE_LIVE=1`，缺凭据返回 BLOCKED
- [ ] T13.8 验收报告按计划 schema 写入 `docs/superpowers/qa/2026-09-08-personal-open-source-release-acceptance.md`
- [ ] A13-1 A00–A11 全 PASS；A12 可 SHOULD-DEFERRED
- [ ] A13-2 四阶段均有当前 SHA 证据
- [ ] A13-3 README 命令与验收命令逐字一致
- [ ] A13-4 报告无 secret；ID 可回读；原始日志有 hash
- [ ] A13-5 全 MUST 通过才写「可开源」；否则列阻断 ID 与复现命令
