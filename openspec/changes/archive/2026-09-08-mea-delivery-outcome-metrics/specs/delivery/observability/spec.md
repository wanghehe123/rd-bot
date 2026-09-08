## MODIFIED Requirements

### Requirement: 系统必须提供吞吐与结果指标
系统 SHALL 按窗口提供任务进入量、终态完成量、成功率、QA 通过率、PR 创建率、人工介入率、重试率和失败类别分布，并 SHALL 公开每个比率的分子、分母与样本数。

交付成功率分子 MUST 只包含当前状态为 `COMPLETED` 的任务，以及当前状态为 `MERGED` 且 `statusEvents` 中存在 `COMPLETED` 的任务。系统 MUST NOT 因存在 pull request URL、当前状态为 `COMMITTED`、或当前状态为 `MERGED` 但历史只有 `COMMITTED` 而计入成功。成功/失败分类 MUST 使用 status events 中的历史完成事实，MUST NOT 仅凭当前枚举猜测 `MERGED`，也 MUST NOT 把 `paused` 当作成功、失败或终态。

`COMMITTED`、`WAITING_USER_INPUT` 与 `WAITING_APPROVAL` MUST 视为在途：不是成功、不是失败、不是观测终态。`runningCount` MUST 计入这些状态，即使观测行的 `terminalAt` 非空。`WAITING_USER_INPUT` MUST NOT 进入成功、失败或终态计数。

当前 `MERGED` 且 status events 不含 `COMPLETED` 的任务 MUST 计入终态（发布已结束），MUST NOT 计入成功或失败，因此 MUST NOT 进入成功率 judged 分母。

#### Scenario: 只用可判定终态计算交付成功率
- **WHEN** 窗口中同时存在运行中、成功和失败任务
- **THEN** 交付成功率只以可判定的成功与失败终态为分母，运行中任务单独展示

#### Scenario: COMMITTED 有 PR 但从未 COMPLETED
- **WHEN** 任务当前状态为 `COMMITTED`、带有 pull request URL、status events 不含 `COMPLETED`，且 `terminalAt` 非空
- **THEN** 该任务不计入成功分子，不计入终态，计入 `runningCount`

#### Scenario: MERGED 且历史含 COMPLETED
- **WHEN** 任务当前状态为 `MERGED` 且 status events 包含 `COMPLETED`
- **THEN** 该任务计入成功分子与终态

#### Scenario: MERGED 仅有 COMMITTED 历史
- **WHEN** 任务当前状态为 `MERGED` 且 status events 仅有 `COMMITTED` 而无 `COMPLETED`
- **THEN** 该任务计入终态，但不计入成功或失败

#### Scenario: WAITING_USER_INPUT 带 terminalAt
- **WHEN** 任务当前状态为 `WAITING_USER_INPUT` 且 `terminalAt` 非空
- **THEN** 该任务不计入成功、失败或终态，计入 `runningCount`
