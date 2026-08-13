package com.wish.rd.rag.knowledge.projection.model;

/**
 * 远端调用结果分类。取值与 WP-0 冻结协议 §5 的观测表一一对应，Adapter 只允许
 * 按已观测的 HTTP 码/错误码翻译，不得按供应商文档补字段。
 */
public enum ExternalIndexFailureClass {

    /** 调用成功且信封合法。 */
    NONE,

    /** 连接建立前失败，远端一定没收到请求，可安全退避重试。 */
    RETRYABLE_NOT_SENT,

    /** 明确可重试的远端拒绝，例如 429。 */
    RETRYABLE,

    /** 同 URI 被其他任务占用（409 path_busy），等原任务结束再试。 */
    RETRYABLE_BUSY,

    /** 请求可能已送达但拿不到可信结果，必须先查询远端，禁止立即重放。 */
    UNKNOWN_REMOTE_RESULT,

    /** 401/403 一类配置问题，停止自动重试。 */
    CONFIGURATION_BLOCKED,

    /** 400/非法 URI/不支持格式，需要修数据，重试无意义。 */
    CONTRACT_OR_DATA_ERROR,

    /** HTTP 2xx 但信封不满足合同，例如 root_uri 与请求 to 不符。 */
    MALFORMED_SUCCESS,

    /** URI 上的 ownership marker 不属于本系统，禁止覆盖或删除。 */
    FOREIGN,

    /** 远端明确终态失败，不再重试。 */
    TERMINAL;

    public boolean success() {
        return this == NONE;
    }

    /**
     * 是否允许在退避后重新提交同一次远端写入。
     *
     * <p>{@link #UNKNOWN_REMOTE_RESULT} 不在此列：它必须先查询远端，
     * 由 Poller 判定后才能决定重放还是收敛。
     *
     * @return 可安全重放时为 true
     */
    public boolean safeToResubmit() {
        return this == RETRYABLE_NOT_SENT || this == RETRYABLE || this == RETRYABLE_BUSY;
    }
}
