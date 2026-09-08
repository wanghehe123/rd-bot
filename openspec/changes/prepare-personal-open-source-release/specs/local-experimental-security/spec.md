## Purpose

在不引入登录/RBAC 的前提下，把个人实验版的默认安装收敛为本机可用：管理面只在本机可访问，
外部入口默认关闭，运行时修改保持 fail closed，Agent 容器权限最小化。

## ADDED Requirements

### Requirement: 管理面默认只在本机可访问

原生进程 SHALL 默认监听 `127.0.0.1`（`server.address: ${SERVER_ADDRESS:127.0.0.1}`）。容器内 MAY 监听
全部接口，但宿主端口发布 MUST 限定 `127.0.0.1`。两层边界 MUST 分别有配置测试与 resolved Compose 断言。
系统 MUST NOT 以默认账号密码登录作为替代边界，user/password 模块 MUST NOT 在 README 宣传为安全认证。

#### Scenario: 原生默认 loopback

- **WHEN** 以默认配置在宿主直接启动后端
- **THEN** 管理端口只绑定 `127.0.0.1`，非 loopback 接口无法访问

#### Scenario: 容器场景双层边界

- **WHEN** 通过 Compose 启动完整栈并解析 resolved 配置
- **THEN** 容器内监听 `0.0.0.0` 且宿主发布为 `127.0.0.1:${RD_BOT_PORT}:18080`

### Requirement: 未验证外部入口默认关闭

飞书 IM 接入、本地 listener 与 write-back SHALL 默认禁用；Docker overlay MUST 再次显式关闭以对抗主配置
漂移。OpenViking、project-memory worker/reconcile/projection 等非核心功能 SHALL 保持默认关闭。未启用的
入口 MUST NOT 接收或解析任何外部消息。

#### Scenario: 默认配置下飞书不启动

- **WHEN** 用户不提供任何飞书配置即启动应用
- **THEN** 飞书 IM 入口、local listener 与 write-back 均不启动，应用日志说明这些入口处于关闭状态

### Requirement: 运行时修改保持 fail closed

系统 SHALL NOT 提供公开的固定 mutation/upload token 默认值。未配置 token 时运行时修改请求 MUST 被拒绝
（维持既有 `AgentRuntimeMutationAccessPolicy` fail-closed 行为）；首次启动可生成随机 token 到本地
`0600` 权限的 runtime.env。启动日志的安全范围摘要 MUST NOT 包含 token、PAT 或 provider key。

#### Scenario: 空 token 拒绝修改

- **WHEN** 未配置 mutation token 的部署收到带修改语义的运行时请求
- **THEN** 请求被拒绝，且仓库中不存在任何可公开猜测的固定 token 默认值

### Requirement: Agent 容器权限最小化

Pi/QA Agent 容器 SHALL NOT 获得 Docker socket、宿主密钥目录、上游模型/Git 凭据或应用完整 env。模型访问
MUST 只经 credential relay；credential relay 的 sidecar 是唯一加入 egress network 的执行面组件。

#### Scenario: Agent 容器检查

- **WHEN** 检查任一运行中 Pi/QA Agent 容器的挂载、env 与网络
- **THEN** 无 Docker socket 与宿主密钥挂载，env 中无上游凭据，出网仅限 task-local network 与 relay 通道

### Requirement: 公开发布内容无个人凭据与私有入口

推荐部署入口 SHALL NOT 包含维护者个人服务器、固定仓库、固定 token、`StrictHostKeyChecking=no`、全局 Git
改写或 `docker system prune` 类危险命令。发布树与本地 refs 可达历史 MUST 经专用 secret scanner 检查；
疑似项 MUST 逐条记录位置、判断与处置。

#### Scenario: 发布前秘密扫描

- **WHEN** 对当前树与全部本地可达历史运行 secret scanner
- **THEN** 无已确认真实凭据；每个疑似命中都有明确的判断与处置记录
