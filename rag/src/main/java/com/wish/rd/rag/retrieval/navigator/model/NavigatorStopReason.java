package com.wish.rd.rag.retrieval.navigator.model;

/**
 * 三层导航循环的终态停止原因。每次退出必须恰好带一个取值，禁止「循环自然结束」而无原因。
 *
 * <p>L0 候选、L1/L2 展开是每轮签发上限，耗尽时不再发该层请求，但不是循环退出原因；
 * 循环退出的预算类原因只有轮数、远端次数、Token、墙钟四类。
 */
public enum NavigatorStopReason {

    /** 证据门判定已有材料足够回答查询。 */
    EVIDENCE_SUFFICIENT,

    /** 本轮没有新增已准入文档，且此前已有证据。 */
    LOW_INFORMATION_GAIN,

    /** 本轮 L0 命中 URI 与此前完全重复。 */
    DUPLICATE_CANDIDATES,

    /** 已跑完配置的最大轮数，证据仍不充分。 */
    ROUND_BUDGET_EXHAUSTED,

    /** 下一发远端请求会超过总次数上限。 */
    REMOTE_CALL_BUDGET_EXHAUSTED,

    /** 已累计的上下文 Token 达到上限，下一发请求不再发出。 */
    TOKEN_BUDGET_EXHAUSTED,

    /** 墙钟已达上限，下一发请求不再发出。 */
    TIME_BUDGET_EXHAUSTED,

    /** 远端未就绪，或 L0 调用本身失败。 */
    REMOTE_UNAVAILABLE,

    /** 查询为空，或改写后得不到可检索的下一问。 */
    NEEDS_USER_INPUT,

    /** 远端有命中但 allowlist 一条未过，或全程没有任何命中。 */
    NOTHING_ADMITTED
}
