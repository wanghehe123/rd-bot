# 评审 NEED_INFO 优先级设计

## 背景

需求评审协议同时包含运行状态和业务决定。运行成功只能说明 Agent 正常返回，不能替代评审是否需要操作者补充材料。原有分类先把 `SUCCESS`/`OK` 当作批准，因而掩盖了显式 `decision=NEED_INFO`。

## 决策顺序

`RequirementReviewProtocol.disposition` 在不改变输入 DTO、状态机或重试路径的前提下按以下顺序分类：

1. `REJECTED`、`UNSAFE`、`FAILED` 等硬失败先失败关闭；
2. `decision` 归一化后为 `NEED_INFO`，直接返回 `ASK_OPERATOR`；
3. 显式批准，或没有 NEED_INFO 决定的成功运行状态，返回 `PROCEED`；
4. 其余 `status`/`feasibility=NEED_INFO` 或非空缺失材料返回 `ASK_OPERATOR`；
5. 字段全部缺失或不能识别时失败关闭。

这样保留“批准结果中的建议性缺口不阻断”的既有 false-ASK 修复，同时把明确的补料请求放在执行状态之前。

## 验证

`RequirementReviewProtocolTest` 覆盖成功运行但显式 NEED_INFO、连字符/大小写变体、批准带建议性缺口、非批准缺失信息和硬失败。调用方的 engine 回归确保返回的 disposition 继续按既有等待输入路径处理。
