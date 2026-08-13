package com.wish.rd.rag.knowledge.projection;

import com.wish.rd.rag.knowledge.projection.model.ExternalIndexSettleCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;

import java.util.List;
import java.util.Optional;

/**
 * 外部索引 Outbox。claim/settle 使用 CAS，不在 mutation commit 内调用远端。
 *
 * <p>提交路径与查询路径的所有权是分开的：{@link #claimBatch} 只服务尚未越过发送边界的行，
 * {@link #claimPollBatch} 服务已经发出去、只能靠查询收敛的行。两者都用"租约是否存活"
 * 判定可接手，而不是比对 owner，这样崩溃实例的行可恢复、存活实例的行不会被抢。
 */
public interface KnowledgeExternalIndexOutboxStore {

    KnowledgeExternalIndexOperation enqueue(KnowledgeExternalIndexOperation operation);

    /**
     * 领取待提交的行。会消耗一次提交预算。
     *
     * <p>已持久化发送意图（{@code remote_operation_id} 非空）的行永远不在此列，
     * 否则崩溃实例的请求会被盲目重放。
     *
     * @param leaseOwner            领取者标识
     * @param nowEpochMillis        当前时间
     * @param leaseUntilEpochMillis 租约到期时间
     * @param batchSize             批量上限
     * @return 已领取的行
     */
    List<KnowledgeExternalIndexOperation> claimBatch(String leaseOwner, long nowEpochMillis, long leaseUntilEpochMillis, int batchSize);

    /**
     * 领取需要查询远端才能收敛的行。不改状态、不消耗提交预算，
     * 也不按提交预算过滤：远端任务还在跑时，本地预算耗尽不该让它变成不可见。
     *
     * @param leaseOwner            轮询者标识
     * @param nowEpochMillis        当前时间
     * @param leaseUntilEpochMillis 本轮短租约到期时间
     * @param batchSize             批量上限
     * @return 已领取的行
     */
    List<KnowledgeExternalIndexOperation> claimPollBatch(String leaseOwner, long nowEpochMillis, long leaseUntilEpochMillis, int batchSize);

    /**
     * CAS 写入。谓词必须同时匹配 eventId、期望状态、租约持有者、期望 rowVersion，
     * 且租约未过期；过期 owner 不得写入任何状态。
     *
     * @param eventId             行 ID
     * @param expectedStatus      期望当前状态
     * @param leaseOwner          期望租约持有者
     * @param expectedRowVersion  期望 rowVersion
     * @param command             写入意图
     * @param nowEpochMillis      当前时间
     * @return CAS 成功时返回新行
     */
    Optional<KnowledgeExternalIndexOperation> settle(
            String eventId,
            ExternalKnowledgeOperationStatus expectedStatus,
            String leaseOwner,
            long expectedRowVersion,
            ExternalIndexSettleCommand command,
            long nowEpochMillis
    );

    Optional<KnowledgeExternalIndexOperation> findById(String eventId);

    List<KnowledgeExternalIndexOperation> listByDocumentId(String documentId);

    List<KnowledgeExternalIndexOperation> listAll();

    void delete(String eventId);
}
