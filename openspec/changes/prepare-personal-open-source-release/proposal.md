## Why

RD-Bot 要以「个人维护、单机自托管实验项目」的定位按 MIT 首次公开发布。2026-09-08 的开源就绪审查
（`docs/qa/open-source-readiness-2026-09-08.md`）在提交 `687af408568462dce78c08d91063780da20ec75f`
上确认了 13 项基线事实：许可证仍展示 Apache-2.0、根 Compose 只有基础设施、Pi/QA 镜像空机无法首次构建、
管理面无鉴权且未强制 loopback、飞书 webhook 默认启用、存在固定 mutation token 默认值、
全量 Maven 有 11 failure + 8 error、依赖漏洞未分流、README 缺少可复现的 Docker 快速开始与产品素材。
本 change 把整改计划
（`docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md`）固化为可验证的行为合同，
避免 Docker、默认配置、迁移与文档由不同执行者各自解释。

## What Changes

- **发布合同**：首发只承诺 Experimental / Developer Preview 级别的单机 Docker + PostgreSQL store + Pi runtime
  路径；memory store、CLAUDE_CODE/MODEL_ONLY、飞书 HTTP webhook、OpenViking、公网、多用户、RBAC 不属于首发支持面。
- **许可证**：根 LICENSE、Maven POM、npm metadata、README 与第三方声明全部切换为 MIT，保留第三方许可边界。
- **公共卫生**：清理个人痕迹、固定 token、危险部署入口与敏感素材；补齐 SECURITY.md 与 CONTRIBUTING.md。
- **默认安全边界**：原生默认监听 `127.0.0.1`，飞书入口与 write-back 默认关闭，删除公共固定 mutation token
  默认值（fail closed 保持不变），Agent 容器不得获得 Docker socket 或上游凭据。
- **Docker 自托管链路**：新增可复现的应用镜像（前端随 Spring Boot 镜像发布）、修复 Pi/QA 镜像首次构建链、
  扩展 Compose 到完整栈（PostgreSQL/pgvector、Redis、MinIO、幂等迁移、应用、build-only Pi/QA profile）、
  提供单一幂等操作入口 `scripts/rd-bot.sh`（up/status/logs/restart/down/doctor/purge --yes）。
- **管理台与 README**：核心 SPA 页面可直达可刷新，无凭据时给真实 onboarding 反馈；重写中英文 README，
  配 4 张静态图与 1 个短 GIF，全部脱敏并记录来源。
- **质量门**：修复/分流当前 11 failure + 8 error，建立首发核心测试集；依赖按可触达性分流，runtime 可达的
  critical/high 未处理则阻断发布。
- **最终验收**：在干净环境（独立 clone、空卷、唯一镜像 tag）完成 Docker 真实验收，产出可被独立 Reviewer
  复核的证据包与「可开源/不可开源」结论。

## Capabilities

### New Capabilities

- `docker-self-hosting`: 单机 Docker 自托管的镜像构建、Compose 编排、迁移幂等、持久化、Agent 容器
  网络与宿主操作脚本的行为合同。
- `local-experimental-security`: 个人实验版默认安全边界——loopback 监听、外部入口默认关闭、
  mutation token fail closed、Agent 容器权限最小化的行为合同。

### Modified Capabilities

无。`requirement/delivery-platform`、`knowledge/openviking-projection-admin`、`delivery/observability`
等主 spec 的行为合同不变；本 change 只增加部署与安全默认值能力。

## Impact

- 新增文件：根 `Dockerfile`、`.dockerignore`、`deploy/docker/*`（runtime.env.example、application-docker.yaml、
  migrate.sh、README.md）、`scripts/rd-bot.sh`、`scripts/docker/**`、`THIRD_PARTY_NOTICES.md`、
  `assets/readme/**`、`.github/workflows/ci.yml`（SHOULD）。
- 修改文件：`LICENSE`、`README.md`、`README.zh-CN.md`、`docker-compose.yml`、`.gitignore`、
  `bootstrap/src/main/resources/application.yaml`、`bootstrap/src/main/resources/executor/pi/Dockerfile.qa`、
  根/模块 POM、两个 `package.json`、`AdminFrontendController` 及相关测试。
- 不改变：`RequirementDeliveryEngine` 交付链、任务状态机、Pi bridge 协议、QA evidence 合同、
  OpenViking 投影协议。凡触及上述链路的整改以既有 RULE.md 约束为准。
- 行为基线：`687af408568462dce78c08d91063780da20ec75f`（审查基线即实施起点）。
- 整改计划：`docs/superpowers/plans/2026-09-08-personal-open-source-release-plan.md`（PLAN，不是已实现事实）。
- 来源审查：`docs/qa/open-source-readiness-2026-09-08.md`（历史证据，只作整改输入，数字不在本 change 中改写）。
