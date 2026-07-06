package com.wish.rd.rag.runtime.model;

/**
 * RD 任务类型。
 *
 * <p>任务类型用于把 Bug 修复、需求交付和智能问答放在同一个管理台与审计流里，
 * 但底层工作流仍按类型分派到不同执行链路。
 */
public enum RdTaskType {
    BUG_FIX,
    REQUIREMENT,
    QNA;

    /**
     * 将外部输入归一化为任务类型。
     *
     * @param value 外部任务类型字符串
     * @param fallback 空值或非法值时使用的默认类型
     * @return 任务类型
     */
    public static RdTaskType parse(String value, RdTaskType fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? BUG_FIX : fallback;
        }
        try {
            return RdTaskType.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback == null ? BUG_FIX : fallback;
        }
    }
}
