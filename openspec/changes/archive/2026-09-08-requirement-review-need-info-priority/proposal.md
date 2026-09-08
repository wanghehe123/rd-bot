## Why

评审核验发现：候选结果 `status=SUCCESS + decision=NEED_INFO + missingInformation=必需材料` 被 `RequirementReviewProtocol.disposition()` 判为 `PROCEED`、`asksOperator=false`——真实的缺输入请求被「执行状态 SUCCESS」掩盖，操作者永远不会被问到，任务带着缺口继续跑。根因是 `isApproved()` 把执行状态 `SUCCESS/OK` 也当作批准，且批准分支先于 NEED_INFO 判断执行；而该类 javadoc 声明的意图是「只有显式批准才放行建议性 missingInformation」。上一提交 a65bcaea 修复 false-ASK（APPROVED + 建议性缺口被误拦）时把放行条件扩大到了 status 字段，引入了本缺陷。

## What Changes

- `engine/src/main/java/com/wish/rd/engine/requirement/RequirementReviewProtocol.java`：把「显式 `decision=NEED_INFO`（normalized 精确匹配）→ `ASK_OPERATOR`」提升到任何批准判断之前；执行状态 `SUCCESS/OK` 不再覆盖显式缺输入决定。
- 保持既有语义不变：hardFail 最先 FAIL_CLOSED；`decision=APPROVED` + 建议性 `missingInformation` 仍 PROCEED；无批准决定时的 `missingInformation` / status/feasibility 上的 NEED_INFO 仍 ASK_OPERATOR；三字段全空仍 FAIL_CLOSED。
- 同步修正类 javadoc 与行内注释（明确「execution status 描述运行而非评审批准」），并在 `RequirementReviewProtocolTest` 补 4 个回归用例。
- 本 change 不修改调用方契约：`ASK_OPERATOR` 在全部 6 处调用点均按「合法停下转 WAITING_USER_INPUT」处理（与 RULE.md「NEED_INFO 不得打成 FAILED_NEEDS_HUMAN」一致）。

## Capabilities

### New Capabilities

- `requirement/requirement-review-need-info-priority`：需求评审协议对「显式 NEED_INFO 决定优先于执行状态」的分类合同。

### Modified Capabilities

- （无）主 specs 中此前没有覆盖该分类行为的条文，本 change 以 ADDED 形式建档。

## Impact

- 运行时：`engine` 模块 `RequirementReviewProtocol` 分类顺序变更（行为修复）。
- 测试：`RequirementReviewProtocolTest` 8/8；调用方定向回归集（ManagerPolicyTest 等 5 类）107/107。
- 明确不改动：`decision` 缺失 + `status=SUCCESS` + 非空 `missingInformation` 仍判 PROCEED——该形态属 mea-demo-scope 已验收的 false-ASK 修复方向，如需收紧另立 change。
