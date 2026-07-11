package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.feishu.im.FeishuImRepairAlertSink;
import com.wish.rd.exec.repair.alert.RepairBudgetObservationSinkPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Bridges executor budget observations to the project-aware Feishu sink. */
@Component
@ConditionalOnProperty(prefix = "rd.feishu.im", name = "enabled", havingValue = "true")
public final class FeishuBudgetObservationAdapter implements RepairBudgetObservationSinkPort {
    private final FeishuImRepairAlertSink delegate;

    public FeishuBudgetObservationAdapter(FeishuImRepairAlertSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void observe(
            String repairRecordId,
            String taskId,
            BigDecimal estimatedSpendCny,
            BigDecimal globalThresholdCny,
            long observedAtEpochMillis
    ) {
        delegate.observe(repairRecordId, taskId, estimatedSpendCny, globalThresholdCny, observedAtEpochMillis);
    }
}
