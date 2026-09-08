## Purpose

让只安装 Docker 的用户在一台机器上从空环境构建、启动、配置、执行、重启并停止完整的 RD-Bot 栈，
且数据保留与迁移幂等行为稳定可预期。

## ADDED Requirements

### Requirement: 应用镜像可从源码可复现构建

系统 SHALL 提供根 `Dockerfile`，在无宿主 JDK/Maven/Node/psql 的机器上仅用 Docker 完成前端构建、后端打包与
运行时镜像组装。构建 MUST 使用固定 tag 的基础镜像（不得 `latest`），MUST 在前端 stage 执行 contract tests、
typecheck 与生产 build，MUST 以非 root 用户运行，MUST NOT 把本地 env 文件、凭据或个人路径带入镜像层。

#### Scenario: 空环境首次构建

- **WHEN** 在只安装 Docker Engine 与 Compose v2 的新 clone 上执行应用镜像构建
- **THEN** 构建成功产出含当前提交前后端产物的镜像，宿主无需任何开发工具链

#### Scenario: 镜像不含本地敏感内容

- **WHEN** 检查镜像 history 与 OCI config
- **THEN** 不存在 env 文件内容、token、个人绝对路径或维护者私有地址

### Requirement: Pi/QA 镜像支持空机首次构建

QA 镜像的默认构建路径 SHALL NOT 依赖目标镜像自身的既有 tag 或私有 registry。默认路径 MUST 从已构建的
Pi 基础镜像安装浏览器依赖；镜像缓存只能是显式 build arg，且无缓存时构建仍成功。QA 镜像构建 MUST 执行
真实的浏览器 headless 启动检查，MUST NOT 以目录存在性代替启动。

#### Scenario: 全新标签空机构建

- **WHEN** 本机不存在任何 `rd-bot/pi-agent*` 旧镜像，先构建 Pi 镜像再以它为基础构建 QA 镜像
- **THEN** 两次构建都成功，且 QA 镜像内 Chromium 能实际 headless 启动并退出 0

#### Scenario: Agent 镜像权限最小化

- **WHEN** 检查 Pi 与 QA 镜像内容和默认用户
- **THEN** 镜像内没有 Docker socket、上游模型/Git 凭据，Agent 以非 root 用户运行且 `/work` 挂载点可写

### Requirement: 单一 Compose 项目承载完整栈

一个 Compose 项目 SHALL 管理 PostgreSQL/pgvector、Redis、MinIO、bucket init、幂等迁移、RD-Bot 应用以及
build-only 的 Pi/QA 镜像构建 profile。基础设施端口 MUST NOT 默认向宿主发布；应用 MUST 只在
`127.0.0.1:${RD_BOT_PORT:-18080}` 发布。不同 `COMPOSE_PROJECT_NAME` 的隔离栈 MUST NOT 因
`container_name` 或固定网络名冲突。

#### Scenario: 空环境首次启动

- **WHEN** 用生成的 runtime.env 在空卷上执行完整栈启动
- **THEN** postgres/redis/minio/bucket-init/migrations 全部成功后 rd-bot 才开始服务，宿主可经
  `http://127.0.0.1:${RD_BOT_PORT}/admin` 访问管理台

#### Scenario: 缺配置启动给出可执行反馈

- **WHEN** runtime.env 中 provider/GitHub 凭据留空时启动
- **THEN** 管理台仍可启动，界面明确显示未配置状态与后续步骤，系统不创建任何 mock 成功任务

### Requirement: 数据库迁移幂等且失败关闭

迁移 SHALL 由容器内脚本按 `pN_` 数字序执行，并把文件名与 checksum 写入 ledger 表。已应用且 checksum
一致的迁移 MUST 跳过；checksum 不一致 MUST 失败并提示人工处理；迁移文件与 ledger 写入 MUST 在同一事务。
应用 MUST NOT 在迁移或 bucket init 失败时静默启动。

#### Scenario: 迁移只执行一次

- **WHEN** 完整栈在已成功迁移的数据库上再次执行迁移
- **THEN** 全部迁移显示跳过，数据库 schema 版本与种子数据不发生变化

### Requirement: 普通启停不破坏数据与秘密

普通 `restart` 与重复 `up` SHALL NOT 重置已生成的秘密、删除数据卷或重复有副作用的迁移。普通 `down`
MUST 保留卷与工作区；清空数据 MUST 是要求显式 `--yes` 的单独命令，且只清理本 Compose project 的资源。

#### Scenario: 普通重启保留数据

- **WHEN** 记录项目/任务/artifact 的 ID 后执行 restart，再回读
- **THEN** 同一批 ID 仍可读取，秘密文件内容不变，迁移不重复执行

#### Scenario: 停止不删卷

- **WHEN** 执行普通 down 后检查卷与工作区
- **THEN** 数据卷与 `.rd-bot-data` 工作区仍存在，重新 up 后数据可回读

### Requirement: 容器化后端可继续创建 Agent 容器

后端容器 SHALL 经只读写的 `/var/run/docker.sock`（宿主 GID 经 `group_add` 注入，非 privileged）访问宿主
daemon，并通过「宿主与容器相同绝对路径」的 workspace bind 使 Agent 容器能读写任务工作区。Docker socket
MUST NOT 挂载给除 rd-bot 外的任何 service。

#### Scenario: 后端创建 project 作用域探针容器

- **WHEN** 运行中的后端经宿主 daemon 创建一个挂载 same-path workspace 的测试容器
- **THEN** 该容器可读写工作区文件，容器清理后宿主侧文件哈希可回读

#### Scenario: Agent 容器网络隔离

- **WHEN** Pi task 容器与 relay sidecar 启动
- **THEN** task 容器只在 task-local internal network 无外联，仅 relay sidecar 经固定 egress network 回连后端
  credential relay，Agent 容器内无 Docker socket

### Requirement: 单一可诊断操作入口

仓库 SHALL 提供 `scripts/rd-bot.sh` 作为 README 唯一操作入口，支持 doctor/up/status/logs/restart/down 与
要求显式确认的 purge。脚本 MUST 从自身位置解析仓库根（任意 cwd 行为一致），首次运行生成 `0600` 权限的
runtime.env 且不覆盖已存在文件，构建顺序固定 Pi → QA → app，任一步失败 MUST 非零退出并给出下一条诊断命令。

#### Scenario: 缺依赖时的诊断

- **WHEN** daemon 未运行、端口被占用、socket 无权限或镜像构建失败时执行 up
- **THEN** 脚本非零退出，输出针对该场景的可执行诊断，而不是静默继续或留下半启动状态
