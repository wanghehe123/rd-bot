package com.wish.rd.exec.repair.model;

/**
 * 模型健康状态快照，用于执行元数据、运维视图和单元测试断言。
 *
 * @param modelId              模型或供应商 ID
 * @param state                当前熔断状态
 * @param consecutiveFailures  当前连续失败次数
 * @param openUntilEpochMillis OPEN 状态截止时间
 * @param halfOpenInFlight     半开探测是否在途
 */
public record ModelHealthSnapshot(
        String modelId,
        ModelHealthState state,
        int consecutiveFailures,
        long openUntilEpochMillis,
        boolean halfOpenInFlight
) {

    public ModelHealthSnapshot {
        modelId = modelId == null ? "" : modelId.strip();
        state = state == null ? ModelHealthState.CLOSED : state;
        consecutiveFailures = Math.max(0, consecutiveFailures);
        openUntilEpochMillis = Math.max(0L, openUntilEpochMillis);
    }
}
