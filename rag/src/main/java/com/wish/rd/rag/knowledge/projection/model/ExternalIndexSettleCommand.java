package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一次 Outbox CAS 写入的意图。只描述可变的运行态字段，身份列（provider、documentId、
 * syncVersion、operationType、idempotencyKey）永远不可被调用方改写。
 *
 * <p>字段合并规则：空串与非正数表示"保持原值"，因此后续 settle 不会意外抹掉
 * 之前持久化的远端关联。要清除错误必须显式用 {@code clearError}。
 *
 * @param nextStatus         目标状态
 * @param leaseDisposition   租约处置
 * @param remoteTaskId       远端后台任务 ID，空串表示保持原值
 * @param remoteOperationId  客户端生成的发送意图标记，空串表示保持原值
 * @param nextVisibleAtEpochMillis 下次可见时间，非正数表示保持原值
 * @param publishedAtEpochMillis   首次真实发送时刻，非正数表示保持原值
 * @param errorCode          错误码，空串表示保持原值
 * @param errorMessage       已脱敏的错误说明，空串表示保持原值
 * @param clearError         是否清空错误码与说明
 * @param clearSendMarker    是否清除发送意图标记，仅在远端确定未执行本次写入时允许
 * @param refundAttempt      是否退还本次 claim 消耗的提交预算，仅在本地放弃、根本没发出请求时允许
 */
public record ExternalIndexSettleCommand(
        ExternalKnowledgeOperationStatus nextStatus,
        LeaseDisposition leaseDisposition,
        String remoteTaskId,
        String remoteOperationId,
        long nextVisibleAtEpochMillis,
        long publishedAtEpochMillis,
        String errorCode,
        String errorMessage,
        boolean clearError,
        boolean clearSendMarker,
        boolean refundAttempt
) {

    public ExternalIndexSettleCommand {
        Objects.requireNonNull(nextStatus, "nextStatus must not be null");
        Objects.requireNonNull(leaseDisposition, "leaseDisposition must not be null");
        remoteTaskId = remoteTaskId == null ? "" : remoteTaskId;
        remoteOperationId = remoteOperationId == null ? "" : remoteOperationId;
        nextVisibleAtEpochMillis = Math.max(0L, nextVisibleAtEpochMillis);
        publishedAtEpochMillis = Math.max(0L, publishedAtEpochMillis);
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        if (clearError && (!errorCode.isBlank() || !errorMessage.isBlank())) {
            throw new IllegalArgumentException("clearError must not be combined with a new error");
        }
        if (nextStatus.terminal() && leaseDisposition == LeaseDisposition.KEEP) {
            throw new IllegalArgumentException("a terminal status must release the lease");
        }
        if (refundAttempt && !clearSendMarker) {
            throw new IllegalArgumentException("only a claim that never reached the remote may refund its budget");
        }
    }

    /**
     * 记录"即将发出远端写入"的持久意图。必须在真正发请求之前提交，
     * 崩溃后的行才不会被重新盲发。
     *
     * @param remoteOperationId 客户端生成的关联 ID
     * @param sentAtEpochMillis 发送时刻，用作未知结果的墙钟截止起点
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand aboutToSend(String remoteOperationId, long sentAtEpochMillis) {
        if (remoteOperationId == null || remoteOperationId.isBlank()) {
            throw new IllegalArgumentException("remoteOperationId must not be blank");
        }
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.SUBMITTED, LeaseDisposition.KEEP,
                "", remoteOperationId, 0L, sentAtEpochMillis, "", "", true, false, false);
    }

    /**
     * 远端已受理，交给 Poller 继续判定。
     *
     * @param remoteTaskId             远端任务 ID
     * @param nextVisibleAtEpochMillis 下次轮询时间
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand waitingRemote(String remoteTaskId, long nextVisibleAtEpochMillis) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.WAITING_REMOTE, LeaseDisposition.RELEASE,
                remoteTaskId, "", nextVisibleAtEpochMillis, 0L, "", "", true, false, false);
    }

    /**
     * 远端任务还没有给出结论，继续等待。与 {@link #waitingRemote} 的区别是保留
     * 本次观测到的错误：一次查询失败对运维是有用信息，不该被静默清掉。
     *
     * @param nextVisibleAtEpochMillis 下次轮询时间
     * @param errorCode                本次观测到的错误码，空串表示无新错误
     * @param errorMessage             已脱敏的说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand stillWaiting(
            long nextVisibleAtEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.WAITING_REMOTE, LeaseDisposition.RELEASE,
                "", "", nextVisibleAtEpochMillis, 0L, errorCode, errorMessage, false, false, false);
    }

    /**
     * 进入版本核验。核验在同一轮 poll 内继续，因此保留租约。
     *
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand verifying() {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.VERIFYING, LeaseDisposition.KEEP,
                "", "", 0L, 0L, "", "", false, false, false);
    }

    /**
     * 远端可能已收到请求但结果不可知，必须靠查询收敛，禁止重放。
     *
     * @param nextVisibleAtEpochMillis 下次查询时间
     * @param errorCode                错误码
     * @param errorMessage             已脱敏的说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand unknownRemote(
            long nextVisibleAtEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT, LeaseDisposition.RELEASE,
                "", "", nextVisibleAtEpochMillis, 0L, errorCode, errorMessage, false, false, false);
    }

    /**
     * 远端确定没有执行本次写入（连接未建立、429/409 被直接拒绝），按退避交还给 Worker。
     *
     * <p>这是唯一允许清除发送意图标记的路径：只有确知未执行，重新提交才不是盲目重放。
     *
     * @param nextVisibleAtEpochMillis 退避到期时间
     * @param errorCode                错误码
     * @param errorMessage             已脱敏的说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand retryWait(
            long nextVisibleAtEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.RETRY_WAIT, LeaseDisposition.RELEASE,
                "", "", nextVisibleAtEpochMillis, 0L, errorCode, errorMessage, false, true, false);
    }

    /**
     * 本地放弃这次派发（版本被更高版本抢先、同文档另一版本仍在途），请求从未发出。
     *
     * <p>因此必须退还 claim 消耗的提交预算：否则一次正常但缓慢的远端任务，
     * 会让后一版本仅靠等待就耗尽预算进死信。
     *
     * @param nextVisibleAtEpochMillis 重新可见时间
     * @param errorCode                放弃原因码
     * @param errorMessage             说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand deferredLocally(
            long nextVisibleAtEpochMillis,
            String errorCode,
            String errorMessage
    ) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.RETRY_WAIT, LeaseDisposition.RELEASE,
                "", "", nextVisibleAtEpochMillis, 0L, errorCode, errorMessage, false, true, true);
    }

    /**
     * 挂起等待人工处理。仍然释放租约，避免留下永久幽灵 owner。
     *
     * @param errorCode    错误码
     * @param errorMessage 已脱敏的说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand needsHuman(String errorCode, String errorMessage) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.NEEDS_HUMAN, LeaseDisposition.RELEASE,
                "", "", 0L, 0L, errorCode, errorMessage, false, false, false);
    }

    public static ExternalIndexSettleCommand succeeded() {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.SUCCEEDED, LeaseDisposition.RELEASE,
                "", "", 0L, 0L, "", "", true, false, false);
    }

    /**
     * 该版本已被更高版本取代且尚未发往远端。
     *
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand superseded() {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.SUPERSEDED, LeaseDisposition.RELEASE,
                "", "", 0L, 0L, "", "", true, false, false);
    }

    /**
     * 终态失败，进入死信。
     *
     * @param errorCode    错误码
     * @param errorMessage 已脱敏的说明
     * @return settle 意图
     */
    public static ExternalIndexSettleCommand deadLetter(String errorCode, String errorMessage) {
        return new ExternalIndexSettleCommand(
                ExternalKnowledgeOperationStatus.DEAD_LETTER, LeaseDisposition.RELEASE,
                "", "", 0L, 0L, errorCode, errorMessage, false, false, false);
    }
}
