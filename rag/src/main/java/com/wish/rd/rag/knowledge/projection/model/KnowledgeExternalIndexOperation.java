package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 外部索引 Outbox 行。lease 过期后旧 owner 不得 settle。
 */
public record KnowledgeExternalIndexOperation(
        String eventId,
        String idempotencyKey,
        String provider,
        ExternalKnowledgeOperationType operationType,
        String knowledgeBaseId,
        String documentId,
        long syncVersion,
        String checksum,
        String remoteUri,
        String revisionId,
        String payloadRef,
        ExternalKnowledgeOperationStatus status,
        String remoteTaskId,
        String remoteOperationId,
        String leaseOwner,
        long leaseUntilEpochMillis,
        int attemptCount,
        int maxAttempts,
        long nextVisibleAtEpochMillis,
        long publishedAtEpochMillis,
        String lastErrorCode,
        String lastErrorMessage,
        long rowVersion,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    public KnowledgeExternalIndexOperation {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        provider = provider == null || provider.isBlank() ? KnowledgeExternalIndexBinding.OPENVIKING : provider.strip().toUpperCase();
        Objects.requireNonNull(operationType, "operationType must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        documentId = documentId == null ? "" : documentId;
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        checksum = checksum == null ? "" : checksum;
        remoteUri = remoteUri == null ? "" : remoteUri;
        revisionId = revisionId == null ? "" : revisionId;
        payloadRef = payloadRef == null ? "" : payloadRef;
        status = status == null ? ExternalKnowledgeOperationStatus.PENDING : status;
        remoteTaskId = remoteTaskId == null ? "" : remoteTaskId;
        remoteOperationId = remoteOperationId == null ? "" : remoteOperationId;
        leaseOwner = leaseOwner == null ? "" : leaseOwner;
        leaseUntilEpochMillis = Math.max(0L, leaseUntilEpochMillis);
        attemptCount = Math.max(0, attemptCount);
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        nextVisibleAtEpochMillis = Math.max(0L, nextVisibleAtEpochMillis);
        publishedAtEpochMillis = Math.max(0L, publishedAtEpochMillis);
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
        rowVersion = Math.max(0L, rowVersion);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(0L, updatedAtEpochMillis);
    }

    public boolean leaseActive(long nowEpochMillis) {
        return !leaseOwner.isBlank() && leaseUntilEpochMillis > nowEpochMillis;
    }

    public KnowledgeExternalIndexOperation claimed(String owner, long leaseUntil, long nowEpochMillis) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                ExternalKnowledgeOperationStatus.CLAIMED,
                remoteTaskId,
                remoteOperationId,
                owner,
                leaseUntil,
                attemptCount + 1,
                maxAttempts,
                nextVisibleAtEpochMillis,
                publishedAtEpochMillis,
                "",
                "",
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    /**
     * 该行是否已经越过发送边界。越过之后禁止再走提交路径，只能靠查询收敛。
     *
     * @return 已持久化发送意图时为 true
     */
    public boolean crossedSendBoundary() {
        return !remoteOperationId.isBlank();
    }

    /**
     * 按 settle 意图产出新行。租约由 {@link LeaseDisposition} 显式决定，
     * 不从目标状态是否终态推断；空串与非正数字段保持原值。
     *
     * @param command        settle 意图
     * @param owner          当前租约持有者
     * @param nowEpochMillis 当前时间
     * @return 新行
     */
    public KnowledgeExternalIndexOperation settled(
            ExternalIndexSettleCommand command,
            String owner,
            long nowEpochMillis
    ) {
        boolean release = command.leaseDisposition() == LeaseDisposition.RELEASE;
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                command.nextStatus(),
                merge(remoteTaskId, command.remoteTaskId()),
                command.clearSendMarker() ? "" : merge(remoteOperationId, command.remoteOperationId()),
                release ? "" : owner,
                release ? 0L : leaseUntilEpochMillis,
                command.refundAttempt() ? Math.max(0, attemptCount - 1) : attemptCount,
                maxAttempts,
                merge(nextVisibleAtEpochMillis, command.nextVisibleAtEpochMillis()),
                merge(publishedAtEpochMillis, command.publishedAtEpochMillis()),
                command.clearError() ? "" : merge(lastErrorCode, command.errorCode()),
                command.clearError() ? "" : merge(lastErrorMessage, command.errorMessage()),
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    /**
     * 把死信重新放回收敛路径。未发出的行回到提交侧并清零预算；
     * 已越过发送边界的行只交给 Poller，attempt 不清零。
     *
     * <p>删除类操作（{@code DELETE_DOCUMENT} / {@code DELETE_KNOWLEDGE_BASE}）豁免
     * 重发禁令：远端 rm 幂等（真机合同冒烟钉住：重复删除受理且 count=0），重发不是
     * 盲重放。被 412 之类协议拒绝后卡死的删除若不豁免，将永远无法完成——Poller 只
     * 确认缺席，而缺席永远不会自己发生。豁免按操作类型路由，刻意不看
     * {@code lastErrorCode}：错误码只是最后一次 settle 的见证，跨多轮租约不可靠。
     * 回到提交侧时必须同时清空两个发送标记，否则 {@code claimBatch} 的
     * {@code remote_operation_id = ''} 护栏会让行停在 PENDING 却永远不被领取。
     *
     * @param nowEpochMillis 当前时间
     * @return 新行
     */
    public KnowledgeExternalIndexOperation requeued(long nowEpochMillis) {
        boolean replayable = !crossedSendBoundary() || idempotentReplayableType();
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                replayable
                        ? ExternalKnowledgeOperationStatus.PENDING
                        : ExternalKnowledgeOperationStatus.UNKNOWN_REMOTE_RESULT,
                replayable ? "" : remoteTaskId,
                replayable ? "" : remoteOperationId,
                "",
                0L,
                replayable ? 0 : attemptCount,
                maxAttempts,
                nowEpochMillis,
                publishedAtEpochMillis,
                lastErrorCode,
                lastErrorMessage,
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    /**
     * 是否属于可安全重发的幂等操作类型。目前只有删除：远端 rm 的幂等性
     * 由真机合同冒烟验证。UPSERT 不在此列——它已有 REBUILD 的全新 key 逃生门。
     *
     * @return 删除类操作为 true
     */
    public boolean idempotentReplayableType() {
        return operationType == ExternalKnowledgeOperationType.DELETE_DOCUMENT
                || operationType == ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE;
    }

    /**
     * 为一次轮询迭代加短租约。不改状态、不消耗提交预算，
     * 但推进 {@code rowVersion} 让任何在途的旧写入 CAS 失败。
     *
     * @param owner          轮询者标识
     * @param leaseUntil     本轮租约到期时间
     * @param nowEpochMillis 当前时间
     * @return 新行
     */
    public KnowledgeExternalIndexOperation pollClaimed(String owner, long leaseUntil, long nowEpochMillis) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                idempotencyKey,
                provider,
                operationType,
                knowledgeBaseId,
                documentId,
                syncVersion,
                checksum,
                remoteUri,
                revisionId,
                payloadRef,
                status,
                remoteTaskId,
                remoteOperationId,
                owner,
                leaseUntil,
                attemptCount,
                maxAttempts,
                nextVisibleAtEpochMillis,
                publishedAtEpochMillis,
                lastErrorCode,
                lastErrorMessage,
                rowVersion + 1L,
                createdAtEpochMillis,
                nowEpochMillis
        );
    }

    private static String merge(String current, String requested) {
        return requested == null || requested.isBlank() ? current : requested;
    }

    private static long merge(long current, long requested) {
        return requested <= 0L ? current : requested;
    }
}
