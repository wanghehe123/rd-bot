## Context

See `proposal.md` for motivation and the two spec deltas under `specs/`. 本 design 固化四个关键决定，
使 Docker、默认配置、迁移与文档可以由不同执行者独立实施而不互相矛盾。

### Source classification

| Source | Classification | How it is used |
| --- | --- | --- |
| `RULE.md` | current repository rule | 模块边界、PostgreSQL 真值、Pi/QA 协议与镜像重建、测试门槛约束。 |
| `docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md` | `PLAN_OR_DECISION` | 本 change 的任务来源；其中所有验收标准在实现前都不是事实。 |
| `docs/qa/open-source-readiness-2026-09-08.md` | `EVIDENCE_OR_SUPERSEDED`（历史审查证据） | 只作整改输入；基线数字不因整改改写，后续结果写新验收报告。 |
| `docs/openspec/historical-spec-provenance-audit.md` | current provenance index | 证明本 change 不迁入任何 `PLAN_OR_DECISION`/`IMPLEMENTED_CLAIM` 历史资料为主 spec。 |
| 当前代码与测试（见下） | current verified baseline | 确定整改起点与实现缝隙。 |

### Verified current anchors (at `687af408568462dce78c08d91063780da20ec75f`)

- 许可证：根 `LICENSE` 为 Apache-2.0 全文；`README.md:7` 使用 Apache-2.0 badge；`README.md:312` 链接 Apache License 2.0。
- 安全默认：`bootstrap/src/main/resources/application.yaml:313` `FEISHU_IM_ENABLED` 默认 `true`；
  `AgentRuntimeMutationAccessPolicy` 已存在 fail-closed 行为，需保持。
- 个人部署入口：`deploy/cloud-server/start-backend.sh`、`start-cpa-tunnel.sh`、`application-local.server.yaml` 存在，
  含个人服务器假设与固定 token 默认值的整改需求。
- Pi/QA 镜像：`bootstrap/src/main/resources/executor/pi/Dockerfile`、`Dockerfile.qa` 存在；
  `Dockerfile.qa` 的 `BROWSER_CACHE_IMAGE` 默认引用 `rd-bot/pi-agent-qa:local`（自引用，空机不可构建）。
- Compose：根 `docker-compose.yml` 只含 PostgreSQL、Redis、MinIO 与 bucket init；无根 `Dockerfile`。
- 管理台 SPA：`AdminFrontendController` 及 `AdminFrontendControllerTest` 为 fallback 行为锚点。
- 回归基线：全量 Maven 11 failure + 8 error（B01），以当前 HEAD 重跑结果为准。

### Baseline commands run for this change (2026-09-08)

```bash
git rev-parse HEAD            # 687af408568462dce78c08d91063780da20ec75f
git status --short            # 仅 docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md 未跟踪
uname -m                      # arm64（macOS, darwin 25.6.0）
docker compose version        # Docker Compose version v2.40.3-desktop.1
docker info                   # T00 时 daemon 未运行；镜像类任务（T04+）启动 Docker Desktop 后重录
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict   # 21 passed, 0 failed
```

## Decisions

### D1. 前端随 Spring Boot 镜像发布

管理台 SPA 在应用镜像的 `java-build` stage 由 `frontend-build` stage 产出并复制到
`bootstrap/src/main/resources/static/admin`，随 jar 一起发布。理由：单机自托管用户只安装 Docker；
不引入独立 nginx/CDN 组件；既有 `AdminFrontendController` 静态托管链路不变。
后果：镜像构建必须包含 Node 22 构建段；前端 contract tests 在镜像构建期执行作为门禁。

### D2. 宿主 loopback 端口

原生进程默认 `server.address=127.0.0.1`；Docker overlay 在容器内监听 `0.0.0.0`，但 Compose 只发布
`127.0.0.1:${RD_BOT_PORT:-18080}:18080`。两层边界都必须有测试覆盖（配置测试 + resolved Compose 断言）。
理由：管理面无鉴权是 Experimental 定位的已知边界，网络层必须兜底。不引入默认登录/RBAC 作为替代。

### D3. Docker socket + same-path workspace bind

`/var/run/docker.sock` 只读写挂给 `rd-bot` service，经 `DOCKER_GID` `group_add` 注入访问权，禁止 privileged。
共享工作区由 launcher 解析绝对路径 `RD_BOT_WORKSPACE_ROOT`，Compose 将同一绝对路径 mount 到后端容器内
相同路径，并设置 `RD_EXECUTOR_DOCKER_WORKSPACE_ROOT` 为同值——宿主 Docker daemon 才能把源路径挂进
Pi/QA Agent 容器（bind mount 以 daemon 所在宿主为准）。Agent 容器不获得 socket、宿主密钥目录或应用 env。

### D4. 固定 egress network + credential relay 回连

Compose 定义可覆盖的稳定 egress network（默认 `rd-bot-egress`，隔离验收栈用 run-id 唯一名），
`rd-bot` 在其上以 alias `rd-bot` 暴露 credential relay。Pi task 容器保持 task-local internal network 无外联，
仅 hardened relay sidecar 加入 egress network 回连
`RD_EXECUTOR_PI_CREDENTIAL_RELAY_URL=http://rd-bot:18080/internal/pi/credential-relay/proxy`。
既有 `DockerPiAgentExecutor` 网络计划不变，只把 overlay 值注入为配置。

### D5. 迁移幂等与数据保留（对齐既有约束）

`deploy/docker/migrate.sh` 在容器内按 `pN_` 数字序应用 SQL，写入 `rd_schema_migrations` checksum ledger，
同一事务提交迁移与 ledger；checksum 变化失败并提示人工处理。普通 `down` 不删卷；清空数据必须是
`purge --yes` 单独命令。这与 RULE.md「不得把容器已启动当成成功」「不删除用户数据」约束一致。

## Non-goals

- 不实现 RBAC、多用户、公网支持、webhook 签名、高可用、任意崩溃窗口恢复。
- 不恢复 memory store、CLAUDE_CODE/MODEL_ONLY、飞书 HTTP webhook 或 OpenViking 为首发支持面。
- 不改写 `docs/qa/open-source-readiness-2026-09-08.md` 的历史数字；新证据写独立验收报告。
- 不把本计划的 PLAN 条目写成已实现事实；每个能力以对应任务完成时的代码与测试为准。

## Verification plan

```bash
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate prepare-personal-open-source-release --strict
OPENSPEC_NO_UPDATE_CHECK=1 openspec validate --all --strict
```

行为验收随 T01–T13 各任务的 Verification/Acceptance 执行；最终结论由 T13 干净环境验收报告产生。
