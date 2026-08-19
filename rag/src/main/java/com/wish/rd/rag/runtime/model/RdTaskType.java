package com.wish.rd.rag.runtime.model;

/**
 * RD 任务类型。
 *
 * <p>{@link #REQUIREMENT} 是唯一有执行链路的类型。{@link #BUG_FIX} 与 {@link #QNA} 只为读取
 * 历史行保留：工单链已下线，不得再有新的写入方。
 */
public enum RdTaskType {
    BUG_FIX,
    REQUIREMENT,
    QNA;

    /**
     * 将外部输入归一化为任务类型。
     *
     * <p>失败关闭：非法值直接抛错，不静默回落。此前非法值会变成 {@code BUG_FIX}，于是一个拼错的
     * 类型字符串会被当成工单，走上一条没有执行器的链路。
     *
     * @param value 外部任务类型字符串
     * @param fallback 空值时使用的默认类型；为 null 时按 {@link #REQUIREMENT} 处理
     * @return 任务类型
     * @throws IllegalArgumentException 当 value 非空且不是合法类型名
     */
    public static RdTaskType parse(String value, RdTaskType fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? REQUIREMENT : fallback;
        }
        try {
            return RdTaskType.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown rd task type: " + value, exception);
        }
    }
}
