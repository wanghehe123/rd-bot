package com.wish.rd.exec.repair.model;

/**
 * 模型熔断策略，控制失败阈值和 OPEN 状态保持时间。
 *
 * @param enabled            是否启用熔断
 * @param failureThreshold   连续失败阈值
 * @param openDurationMillis OPEN 状态保持毫秒数
 */
public record ModelCircuitBreakerPolicy(
        boolean enabled,
        int failureThreshold,
        long openDurationMillis
) {

    /**
     * 创建模型熔断策略。
     */
    public ModelCircuitBreakerPolicy {
        if (enabled) {
            failureThreshold = Math.max(1, failureThreshold);
            openDurationMillis = Math.max(1L, openDurationMillis);
        }
    }

    /**
     * 返回默认启用策略。
     *
     * @return 默认模型熔断策略
     */
    public static ModelCircuitBreakerPolicy defaults() {
        return new ModelCircuitBreakerPolicy(true, 3, 60_000L);
    }

    /**
     * 返回禁用策略，供旧构造器和单测保持兼容。
     *
     * @return 禁用模型熔断策略
     */
    public static ModelCircuitBreakerPolicy disabled() {
        return new ModelCircuitBreakerPolicy(false, 0, 0L);
    }
}
