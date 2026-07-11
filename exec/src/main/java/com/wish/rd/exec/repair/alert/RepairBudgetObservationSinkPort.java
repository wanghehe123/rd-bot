package com.wish.rd.exec.repair.alert;

import java.math.BigDecimal;

/** Receives every CNY cost observation so project thresholds can be lower than the global alert threshold. */
@FunctionalInterface
public interface RepairBudgetObservationSinkPort {
    void observe(
            String repairRecordId,
            String taskId,
            BigDecimal estimatedSpendCny,
            BigDecimal globalThresholdCny,
            long observedAtEpochMillis
    );

    static RepairBudgetObservationSinkPort noop() {
        return (repairRecordId, taskId, estimatedSpendCny, globalThresholdCny, observedAtEpochMillis) -> { };
    }
}
