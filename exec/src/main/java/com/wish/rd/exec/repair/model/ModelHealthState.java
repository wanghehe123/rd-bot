package com.wish.rd.exec.repair.model;

/**
 * 模型熔断三态，模型健康状态机（CLOSED/OPEN/HALF_OPEN）。
 */
public enum ModelHealthState {

    /** 正常放行。 */
    CLOSED,

    /** 熔断打开，暂不允许调用。 */
    OPEN,

    /** 半开探测，只允许一个探测调用在途。 */
    HALF_OPEN
}
