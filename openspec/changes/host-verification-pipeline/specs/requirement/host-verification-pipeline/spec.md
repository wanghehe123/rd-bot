## Purpose

在需求交付的编码完成之后、浏览器 QA 启动之前，由宿主对候选补丁执行可审计的编译构建与静态检查，代码类失败只打回编码且次数有界，环境类失败交给人工。

## ADDED Requirements

### Requirement: Coding 成功后必须经过宿主验证才能派发 QA

系统 MUST 在需求交付任务的 `CODING_AGENT` 最新 attempt 达到成功终态之后、派发 `QA_AGENT` 之前，创建并执行一轮宿主验证。系统 MUST NOT 把该验证建模为第五个交付角色。验证未成功结束时，系统 MUST NOT 派发新的 `QA_AGENT` attempt。

#### Scenario: 编码成功后先验证再 QA

- **WHEN** 需求任务的 `CODING_AGENT` 当前 attempt 成功结束，且本轮尚无对应的宿主验证终态
- **THEN** 系统创建一轮新的宿主验证，并在该验证成功之前不派发 `QA_AGENT`

#### Scenario: 验证未通过时 QA 不得开工

- **WHEN** 本轮宿主验证处于进行中、可重试失败或需要人工
- **THEN** 系统不得创建或启动新的 `QA_AGENT` attempt

### Requirement: 验证步骤顺序与独立结果

每一轮宿主验证 MUST 按顺序包含 BUILD 与 STATIC 两个步骤，并各自记录独立状态、命令、退出码、耗时和日志证据。BUILD 未成功时系统 MUST NOT 执行 STATIC。docs-only 候选补丁 MUST 跳过 BUILD 与 STATIC，并允许随后按既有 docs-only 规则进入无需浏览器的 QA。

#### Scenario: BUILD 失败不跑 STATIC

- **WHEN** BUILD 步骤命令退出码非 0，且失败被分类为代码缺陷
- **THEN** STATIC 标记为未执行，整轮验证失败，系统不进入 `QA_AGENT`

#### Scenario: BUILD 通过后执行 STATIC

- **WHEN** BUILD 全部命令退出码为 0
- **THEN** 系统继续执行 STATIC，并把两步结果都写入本轮验证记录

#### Scenario: docs-only 跳过构建与静态检查

- **WHEN** 宿主根据已应用候选补丁的真实 git 变更集判定为 docs-only
- **THEN** BUILD 与 STATIC 均跳过，验证记录标明 docs-only，系统可进入无需浏览器证据的 QA

### Requirement: 命令来源与安全执行

构建命令与静态命令 MUST 只来自任务 QA 配置、项目 QA 配置、仓库配置或安全自动探测，优先级为任务覆盖大于项目配置大于仓库配置大于自动探测。系统 MUST 拒绝含凭据字面量、换行或空字节的命令。系统 MUST NOT 把 Coding 角色自报的 `testCommands` / `testStatus` 当作验证通过的证据。自动探测 MUST NOT 把 `npm run dev` 或等价开发服务器当作 BUILD 命令。无法安全探测到任何 BUILD 命令时，系统 MUST 将验证标为需要人工，而不是猜测命令或视为通过。

#### Scenario: 任务覆盖优先于项目配置

- **WHEN** 任务 QA 配置写有 `buildCommands`，项目配置写有另一组 `buildCommands`
- **THEN** 本轮 BUILD 只执行任务配置中的命令

#### Scenario: 禁止把开发服务器当成构建

- **WHEN** 仓库被识别为 Vite 项目且没有显式 `buildCommands`
- **THEN** 自动探测的 BUILD 使用生产构建命令，不得使用 `npm run dev`

#### Scenario: Coding 自报通过不能放行

- **WHEN** Coding 结果里 `testStatus` 为 PASSED，但宿主 BUILD 命令退出码非 0
- **THEN** 本轮验证失败，系统不得因 Coding 自报而派发 QA

### Requirement: 失败分类与廉价返工预算

代码、单测、typecheck 或静态检查由候选补丁引入的失败 MUST 分类为可打回编码的产品缺陷。依赖安装失败、镜像或权限问题、密钥/鉴权、需求歧义和 flaky MUST NOT 自动打回编码，任务 MUST 进入需要人工的终态。廉价返工 MUST 最多自动进行 2 次，每次只新建 `CODING_AGENT` attempt，MUST NOT 预创建 `QA_AGENT` attempt。廉价返工次数与既有 QA 浏览器返工（最多 1 次）分账，但两者 MUST 遵守同一角色 attempt 硬上限 3。达到任一上限后 MUST 停止自动返工并交给人工。系统 MUST NOT 改写已经终态的旧验证记录或旧角色 attempt。

#### Scenario: 编译失败只打回 Coding

- **WHEN** BUILD 因候选补丁编译错误失败，且本任务廉价返工次数小于 2，且 Coding 最新 `attemptNo` 小于 3
- **THEN** 系统将失败的验证记录留在终态，新建一个 `CODING_AGENT` attempt，并把失败命令、退出码和日志摘要注入新的编码上下文；此时不得新建 `QA_AGENT` attempt

#### Scenario: 环境失败不打回 Coding

- **WHEN** BUILD 因依赖源不可达或容器权限失败
- **THEN** 验证分类为环境或基础设施失败，任务进入需要人工，不创建新的 Coding attempt

#### Scenario: 第二次廉价返工后仍失败则人工

- **WHEN** 同一任务已经完成 2 次廉价验证返工，新的 BUILD 再次因代码缺陷失败
- **THEN** 系统不再自动创建 Coding attempt，任务进入需要人工

#### Scenario: 角色 attempt 总帽优先

- **WHEN** Coding 最新 `attemptNo` 已为 3，且验证再次因代码缺陷失败
- **THEN** 即使廉价返工计数未满 2，系统也不得再开新的 Coding attempt

### Requirement: 验证证据可审计

每一轮宿主验证 MUST 持久化 run 标识、关联的 Coding `stageRunId`、步骤状态、命令原文、退出码、耗时、失败分类和日志文件的字节数与 SHA-256。日志 MUST 落在 `verify-evidence/` 约定路径下，并由宿主按退出码判定，不得只保存模型摘要。任务删除时 MUST 按既有证据保留策略清理这些对象。管理端 MUST 能按任务列出验证轮次、步骤和证据内容，且内容 URL 只接受本任务私有证据。

#### Scenario: 列出验证轮次与步骤

- **WHEN** 调用方读取某需求任务的宿主验证
- **THEN** 响应包含每轮验证的状态、廉价返工序号、BUILD/STATIC 各自状态和证据引用

#### Scenario: 证据内容必须属于本任务

- **WHEN** 调用方用任务 A 的路径请求任务 B 的验证日志
- **THEN** 系统拒绝该请求，不返回文件内容

### Requirement: 与既有 QA 浏览器协议共存

宿主验证通过后，`QA_AGENT` MUST 继续遵守现有浏览器证据引用、生产模式启动和 docs-only 例外。QA 产品缺陷或回归的自动返工 MUST 仍最多 1 次，且仍同时创建新的 Coding 与 QA attempt。QA 的 `regressionCommands` MUST 不得替代宿主 BUILD/STATIC。

#### Scenario: 验证通过后 QA 仍要真实验收

- **WHEN** 本轮宿主验证成功（含合法的 docs-only 跳过）
- **THEN** 系统按既有 QA 协议派发 `QA_AGENT`，不因 BUILD 已绿而跳过 CURRENT/REGRESSION

#### Scenario: QA 返工预算不被廉价门占用

- **WHEN** 某任务已经用掉 2 次廉价验证返工，随后 QA 因产品缺陷失败且尚未用过 QA 返工
- **THEN** 系统仍可按既有规则进行最多 1 次 QA 返工，但 Coding 若已达 attempt 上限 3 则改为人工
