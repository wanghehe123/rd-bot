package com.wish.rd.rag.project.alert.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 项目级任务告警配置快照。
 *
 * @param projectId             项目 ID
 * @param enabled               是否启用
 * @param recipients            飞书接收目标
 * @param eventTypes            订阅事件
 * @param budgetThresholdCny    人民币预算阈值
 * @param failureThreshold      可重试失败通知阈值
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 */
public record RdProjectAlertConfig(
        String projectId,
        boolean enabled,
        List<RdAlertRecipient> recipients,
        Set<RdProjectAlertEventType> eventTypes,
        BigDecimal budgetThresholdCny,
        int failureThreshold,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public RdProjectAlertConfig {
        projectId = projectId == null ? "" : projectId.strip();
        if (projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
        budgetThresholdCny = budgetThresholdCny == null ? BigDecimal.ZERO : budgetThresholdCny;
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
    }
}
