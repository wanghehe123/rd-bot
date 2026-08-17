## Purpose

为每个项目提供独立的 Agent 执行策略控制台，使操作者能用一份策略一次配置四个交付角色的执行器、供应商和运行时镜像，并把该配置投影为调度仍能解析的角色默认绑定。

## ADDED Requirements

### Requirement: 项目列表必须进入独立策略页而不是弹窗
系统 SHALL 从项目管理列表为每个项目提供「Agent 执行策略」入口，导航到 `/admin/projects/{projectId}/agent-strategy`。系统 SHALL NOT 在项目列表上保留「运行镜像」入口，也 SHALL NOT 用小窗口完成四角色策略编辑。刷新该路径时 SHALL 返回管理端 SPA。

#### Scenario: 从项目列表打开策略页
- **WHEN** 操作者在项目管理列表点击某项目的「Agent 执行策略」
- **THEN** 浏览器进入该项目的独立策略页，而不是弹出对话框

#### Scenario: 项目列表不再出现运行镜像
- **WHEN** 操作者查看项目管理列表的操作列
- **THEN** 不存在「运行镜像」按钮或等价弹窗入口

#### Scenario: 直连刷新策略页
- **WHEN** 操作者刷新 `/admin/projects/{projectId}/agent-strategy`
- **THEN** 服务返回管理端 SPA，而不是将该路径当作 JSON API

### Requirement: 一份策略必须一次覆盖四个交付角色
系统 SHALL 将项目策略建模为命名聚合：每个策略包含且仅包含 `REQUIREMENT_REVIEWER`、`SOLUTION_ARCHITECT`、`CODING_AGENT`、`QA_AGENT` 四个角色槽位。每个槽位 SHALL 包含执行器（`PI`、`CLAUDE_CODE` 或 `MODEL_ONLY`）、Provider Profile、可选模型覆盖和镜像模式。保存时缺任一角色、执行器或 Provider SHALL 拒绝，并指出缺失的角色，而不是返回无法定位的泛化错误。

#### Scenario: 一次保存四个角色
- **WHEN** 操作者在策略页为四个角色分别选择执行器和已启用的 Provider 后保存
- **THEN** 系统持久化该策略，且四个角色都出现在策略详情中

#### Scenario: 缺少角色或供应商时拒绝保存
- **WHEN** 保存请求缺少某个交付角色或其 Provider
- **THEN** 系统返回 HTTP 400，并说明哪个角色未配齐

### Requirement: 本地默认镜像必须无需上传即可保存
系统 SHALL 为 Pi 与 Claude Code 提供「本地默认」镜像模式，该模式不要求选择 Dockerfile 即可保存。Pi 本地默认 SHALL 展示 `rd-bot/pi-agent:local`，QA 角色 SHALL 展示将使用 `rd-bot/pi-agent-qa:local`。Claude Code 本地默认 SHALL 展示全局 Docker 镜像。`MODEL_ONLY` SHALL 不要求镜像。新建策略的执行器默认 SHALL 为 `PI`。

#### Scenario: 不选文件即可保存本地默认
- **WHEN** 四个角色都选择本地默认镜像且未选择 Dockerfile
- **THEN** 保存成功，且各槽位镜像模式为本地默认

#### Scenario: 仅模型不显示镜像要求
- **WHEN** 某角色执行器为 `MODEL_ONLY`
- **THEN** 该角色不要求上传或选择运行时镜像

### Requirement: 自定义镜像必须按执行器区分处理
系统 SHALL 允许角色槽位选择上传 Dockerfile。Claude Code 自定义镜像 SHALL 走现有受限 Dockerfile 构建与验证合同，验证通过后成为该角色的项目运行镜像。Pi 自定义镜像 SHALL 持久化上传元数据，但本轮 SHALL NOT 改变实际 Pi 容器使用的全局镜像，并 SHALL 向操作者说明执行仍用本地 Pi 镜像。回到本地默认时，Claude Code SHALL 移除该角色的自定义运行镜像。

#### Scenario: Claude 上传受限 Dockerfile
- **WHEN** 某角色执行器为 Claude Code 且操作者上传通过合同验证的 Dockerfile
- **THEN** 该槽位变为自定义镜像，且后续未启用 agent-runtime 的 Claude 执行可解析到该已验证镜像

#### Scenario: Pi 上传只落库
- **WHEN** 某角色执行器为 Pi 且操作者上传 Dockerfile
- **THEN** 系统保存该上传记录，页面说明执行仍使用本地 Pi 镜像，且不把该文件当作当前 Pi 容器镜像

### Requirement: 保存策略必须投影为现有角色默认绑定
系统 SHALL 在保存策略时为四个角色写入可被现有解析顺序使用的执行 Profile，投影 Profile ID 为 `{projectId}:{strategyId}:{role}`，对同一项目、策略与角色保持稳定。将策略设为项目默认时，系统 SHALL 同时绑定四个角色的项目默认。解析顺序 SHALL 仍为任务覆盖 → 项目角色默认 → 兼容默认。系统 SHALL NOT 改变 snapshot 冻结语义。不同项目 SHALL 可以使用相同的 `strategyId` 而互不覆盖。

#### Scenario: 设为项目默认后按角色解析
- **WHEN** 操作者保存策略并将其设为项目默认
- **THEN** 对该项目四个角色的后续解析命中该策略对应槽位的执行器与 Provider

#### Scenario: 投影 ID 保持稳定
- **WHEN** 操作者再次保存同一策略
- **THEN** 四个角色的投影 Profile ID 与首次保存相同，且格式为 `{projectId}:{strategyId}:{role}`

#### Scenario: 不同项目可使用相同策略 ID
- **WHEN** 两个项目各自保存 strategyId 为 `default` 的策略
- **THEN** 两边互不覆盖，后续按角色解析分别命中各自项目的投影 Profile

### Requirement: 无策略行时必须合成只读当前默认
当项目尚无策略聚合行时，系统 SHALL 根据现有按角色默认绑定合成只读「当前默认」供页面展示。首次成功保存 SHALL 写入策略表，而不是要求手工迁移。

#### Scenario: 展示遗留角色绑定
- **WHEN** 项目只有按角色的执行 Profile 绑定而没有策略聚合
- **THEN** 策略页展示合成的当前默认，且标明尚未保存为策略

### Requirement: 写操作必须使用单一操作令牌
策略的创建、更新、设为默认和镜像上传/删除 SHALL 使用请求头 `X-RD-Agent-Runtime-Token`。令牌未配置时写操作 SHALL 返回 HTTP 503；令牌错误 SHALL 返回 HTTP 403。读操作 SHALL 不要求该令牌。系统 SHALL NOT 在策略页要求第二套运行镜像令牌。

#### Scenario: 错误令牌不能保存
- **WHEN** 操作者使用错误令牌保存策略
- **THEN** 系统返回 HTTP 403 且不写入策略

#### Scenario: 未配置令牌时写操作不可用
- **WHEN** 后端未配置 Agent 运行时操作令牌
- **THEN** 保存返回 HTTP 503

### Requirement: 页面必须说明实际执行开关
策略页 SHALL 标明：仅当 `rd.executor.agent-runtime.enabled` 为真时，已保存的 Pi 槽位才会被执行器路由使用；该开关关闭时保存不会改变实际执行路径。页面 SHALL NOT 在本能力中自动打开该开关。

#### Scenario: 开关关闭时给出横幅
- **WHEN** 操作者打开策略页且 agent-runtime 未启用
- **THEN** 页面说明当前保存 Pi 策略不会切换实际执行器
