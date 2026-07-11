package com.wish.rd.rag.project.alert.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 更新项目告警配置的命令。
 *
 * @param enabled            是否启用
 * @param recipients         收件人
 * @param eventTypes         事件类型
 * @param budgetThresholdCny 人民币预算阈值
 * @param failureThreshold   可重试失败通知阈值
 */
public record RdProjectAlertConfigCommand(
        boolean enabled,
        List<RdAlertRecipient> recipients,
        Set<RdProjectAlertEventType> eventTypes,
        BigDecimal budgetThresholdCny,
        int failureThreshold
) {

    public RdProjectAlertConfigCommand {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
        budgetThresholdCny = budgetThresholdCny == null ? BigDecimal.ZERO : budgetThresholdCny;
        if (budgetThresholdCny.signum() < 0) {
            throw new IllegalArgumentException("budgetThresholdCny must not be negative");
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
    }
}
