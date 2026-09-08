## 1. 协议修复与回归

- [x] 1.1 在 `RequirementReviewProtocol.disposition()` 中把 `needInfo(decision)` 提升到 `isApproved` 分支之前单独提前返回 `ASK_OPERATOR`；hardFail 最先、全空 FAIL_CLOSED、尾部默认 PROCEED 不动。验证：`./mvnw -q -pl engine -am test -Dtest='*RequirementReview*'` → RequirementReviewProtocolTest 8/8。
- [x] 1.2 同步修正类 javadoc 与行内注释：execution status 的 SUCCESS/OK 描述运行而非批准。验证：`sed -n '46,56p' .../RequirementReviewProtocol.java` 注释与实现一致。
- [x] 1.3 新增回归用例：SUCCESS+NEED_INFO+missingInformation → ASK；`need-info` 归一化大小写仍先于批准命中；无批准 + missingInformation → ASK；NEED_INFO + 空 missingInformation → ASK（RULE.md：NEED_INFO 本身即合法 ASK 输入）。验证：同 1.1 命令。
- [x] 1.4 调用方回归：`./mvnw -pl engine -am -Dtest=ManagerPolicyTest,RequirementReviewProtocolTest,RequirementDeliveryStageExecutionTest,RequirementDeliveryEngineTest,RequirementStageExecutionPlanCodecTest test` → 107/107，BUILD SUCCESS。

## 2. OpenSpec 校验

- [x] 2.1 运行 `OPENSPEC_NO_UPDATE_CHECK=1 openspec validate requirement-review-need-info-priority --strict`，确认 proposal/specs/tasks 齐全且通过（本机 CLI 无 `--change` 选项，用位置参数等价执行）。
