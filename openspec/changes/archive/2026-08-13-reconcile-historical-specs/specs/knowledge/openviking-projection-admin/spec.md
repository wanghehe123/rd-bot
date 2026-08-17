## MODIFIED Requirements

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
