# OpenViking Projection Admin Specification

## Purpose

定义 RD-Bot 对 OpenViking 知识投影的可观测、可操作和安全边界，让 PostgreSQL 账本保持业务真值，让外部索引成为可重建且可核验的投影，并让管理端按同一份状态契约工作。

## Requirements

### Requirement: 投影状态必须区分本地期望与远端观测

系统 SHALL 将知识文档的 desired state/version/checksum 与 observed state/version/checksum 分开呈现，并以投影状态、操作状态和最近错误共同表达收敛进度。HTTP 请求成功或远端异步任务完成本身不得被当作 `IN_SYNC` 的充分条件。

#### Scenario: 查看知识库投影总览

- **WHEN** 操作者请求某知识库的 OpenViking 投影总览
- **THEN** 系统返回远端 ready 状态、binding 各投影状态计数、outbox 各操作状态计数、未收敛数量和最老未收敛年龄

#### Scenario: 查看文档映射及操作时间线

- **WHEN** 操作者打开某知识库中的文档投影详情
- **THEN** 系统返回该文档的 desired/observed 版本与 checksum、投影状态、远端 URI/任务标识、最近错误，以及按时间可追溯的操作记录

### Requirement: 管理 API 必须提供受限且可分页的投影视图

系统 SHALL 为知识库投影提供统一 `{data: ...}` 响应封套，并支持总览、文档分页/状态过滤、文档详情、远端树、健康、死信和墓碑查询。远端树查询只能读取指定知识库的 owned root 及其子路径。

#### Scenario: 查询投影管理数据

- **WHEN** 客户端请求 `/admin/knowledge-base/{knowledgeBaseId}/openviking/` 下的任一读接口
- **THEN** 系统返回 `data` 字段包裹的可序列化视图；分页接口返回 `records`、`total`、`page` 和 `size`，并使用安全的错误码/消息字段

#### Scenario: 请求越界远端 URI

- **WHEN** 客户端请求的 `uri` 不在该知识库的 owned root 下
- **THEN** 系统返回 HTTP 400，且不向外部索引发出列树请求

#### Scenario: 查看已删除文档

- **WHEN** 操作者查询知识库墓碑列表
- **THEN** 系统返回保留的文档身份、来源名、删除时间、投影状态和远端 URI，并且该列表不把墓碑伪装成普通活动文档

### Requirement: 管理操作必须沿用账本收敛原语

系统 SHALL 通过投影管理能力操作本地账本和观测状态，不允许管理控制器绕过领域边界直接发外部 REST。重试、核验、重建、死信重入队和对账必须保持幂等、行版本约束及异步收敛语义。任何已经越过远端发送边界的操作都不得重新进入远端写提交路径。

#### Scenario: 重试可恢复的卡住操作

- **WHEN** 操作者对处于 `RETRY_WAIT` 或 `NEEDS_HUMAN` 的文档执行 retry
- **THEN** 系统按当前行版本恢复同一操作而不创建新版本；`RETRY_WAIT` 保留其提交预算，未发送的 `NEEDS_HUMAN` 恢复为待提交，已带远端操作标识的 `NEEDS_HUMAN` 只能转入 `UNKNOWN_REMOTE_RESULT` 供只读收敛，且不会重发远端写请求

#### Scenario: 没有可恢复操作时重试

- **WHEN** 文档没有处于 `RETRY_WAIT` 或 `NEEDS_HUMAN` 的非终态操作
- **THEN** 系统返回 `NOTHING_TO_RETRY`，而不是创建无关的新版本

#### Scenario: 只读核验远端

- **WHEN** 操作者执行 verify 且投影已启用
- **THEN** 系统只读核验远端资源，并通过版本条件更新把观测结果写为 `IN_SYNC` 或 `DRIFTED`；不得创建 outbox 写操作

#### Scenario: 重建当前期望版本

- **WHEN** 操作者执行 rebuild
- **THEN** 系统为当前 desired version 入队一次 `REBUILD_DOCUMENT`；重复请求被已有幂等记录吸收，并返回“已重建”或“已在队列”结果

#### Scenario: 死信按行版本重新进入收敛路径

- **WHEN** 操作者提交死信 event id 和匹配的 `expectedRowVersion`
- **THEN** 未越过远端发送边界的死信恢复为 `PENDING` 并可被提交；已经带远端操作标识的死信只能转为 `UNKNOWN_REMOTE_RESULT` 并保留提交预算，行版本过期或知识库归属不匹配时返回冲突或参数错误，且不改变错误行

#### Scenario: 手工触发对账

- **WHEN** 操作者对已启用的知识库执行 reconcile
- **THEN** 系统返回本轮发现计数及发现明细；对账只通过观测写入收敛，不覆盖 desired state

### Requirement: 投影关闭和错误响应必须安全可解释

系统 SHALL 默认关闭真实 OpenViking 投影；投影关闭时账本读接口仍可用，但依赖远端的 verify 和 reconcile 必须明确返回冲突。参数错误、版本冲突和远端不可用分别映射为 HTTP 400、409 和 502，错误响应不得泄露密钥、原始远端响应、堆栈或内部文件路径。

#### Scenario: 未配置外部投影时读取账本

- **WHEN** 投影开关关闭且客户端请求总览、文档或健康信息
- **THEN** 系统仍返回本地账本数据，并把 ready 表达为未就绪

#### Scenario: 关闭投影时执行远端操作

- **WHEN** 投影开关关闭且客户端请求 verify 或 reconcile
- **THEN** 系统返回 HTTP 409 和明确的“投影已关闭”语义，不伪造远端失败发现

#### Scenario: 外部错误进入管理端

- **WHEN** 请求触发参数错误、CAS 冲突或远端不可用
- **THEN** 系统只返回脱敏后的 message/error code；响应体不包含 API key、`X-API-Key` 值、异常栈或原始 OpenViking DTO

### Requirement: 管理前端必须消费同一投影契约

系统 SHALL 通过 `/admin/knowledge/{kbId}/openviking` 页面和对应数据服务展示健康、状态徽标、版本差异、远端树、死信、墓碑和操作时间线。前端必须解包后端 `data` 封套，并在操作成功后重新读取服务端账本，而不是只依据本地 toast 更新状态。

#### Scenario: 浏览投影管理页

- **WHEN** 操作者从知识文档页进入某知识库的 OpenViking 页面
- **THEN** 页面显示健康卡、投影状态汇总、可分页文档映射、远端树、死信和墓碑，并使用稳定的页面路由而不是 API 路由

#### Scenario: 操作后刷新权威状态

- **WHEN** 操作者执行 retry、verify、rebuild 或死信 requeue
- **THEN** 前端显示服务端返回的操作结果，并重新拉取相关总览/文档/详情数据后再更新页面状态

#### Scenario: 收敛期间轮询

- **WHEN** 总览、文档或操作记录中仍存在未终态的投影工作
- **THEN** 前端以 5 秒间隔继续轮询；连续两轮没有未收敛工作后停止轮询
