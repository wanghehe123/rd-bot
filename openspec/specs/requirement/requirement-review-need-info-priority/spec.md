# 需求评审 NEED_INFO 优先级规范

## Purpose

定义需求评审协议中运行成功与补充材料决定的优先级，确保显式的 NEED_INFO 不会被成功运行状态误判为批准。

## Requirements

### Requirement: 显式 NEED_INFO 决定优先于执行状态

系统 SHALL 在分类需求评审协议结果时，把显式的 `decision=NEED_INFO`（按归一化精确匹配，含大小写与连字符变体）判定为需要操作者输入（ASK_OPERATOR），且该判定 MUST 先于任何「批准」判断执行。执行状态字段（如 `status=SUCCESS/OK`）描述的是运行结果而非评审批准，MUST NOT 掩盖显式缺输入请求。

#### Scenario: 执行成功但评审显式要求补充材料

- **WHEN** 评审结果为 `status=SUCCESS`、`decision=NEED_INFO`，且 `missingInformation` 非空
- **THEN** 系统判为 ASK_OPERATOR，`asksOperator` 返回 true，任务停下等待操作者补充，而不是 PROCEED

#### Scenario: 归一化变体的 NEED_INFO 决定

- **WHEN** `decision` 以 `need-info` 等大小写或连字符变体给出，同时 status 为 OK
- **THEN** 归一化后仍命中 NEED_INFO 优先分支，判为 ASK_OPERATOR

#### Scenario: 显式批准携带建议性缺口仍放行

- **WHEN** `decision=APPROVED`（或等价批准值）且 `missingInformation` 非空
- **THEN** 系统判为 PROCEED，建议性缺口不阻断（false-ASK 修复方向保持不变）

#### Scenario: 无批准决定但列出缺失信息

- **WHEN** `decision` 不是批准值，status/feasibility 也非批准，而 `missingInformation` 非空或 status/feasibility 为 NEED_INFO
- **THEN** 系统判为 ASK_OPERATOR

#### Scenario: 协议字段缺失与硬失败不受影响

- **WHEN** status/decision/feasibility 全空，或任一字段为 UNSAFE/REJECTED/FAILED 等硬失败值
- **THEN** 系统分别判为 FAIL_CLOSED；硬失败判断仍先于 NEED_INFO 与批准判断
