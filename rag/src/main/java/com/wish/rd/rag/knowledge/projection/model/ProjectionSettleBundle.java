package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一次投影收口：Outbox 的 CAS 写入与绑定观测必须一起提交。
 *
 * <p>拆成两次独立写入会产生裂脑（Outbox 已 SUCCEEDED 但绑定仍 PROCESSING，或者相反），
 * 而 WP-3 没有 Reconciler 来修复它。
 *
 * <p>绑定观测不带期望 rowVersion：租约已经保证同一时刻只有一个 Worker/Poller 在写观测，
 * 真正的并发对手是本地 mutation 事务推进 desired。观测写入只覆盖 observed 列，
 * 由实现读取当前 rowVersion 后 CAS 落库，因此并发的 desired 提升不会被回退，观测也不会丢。
 *
 * @param eventId            Outbox 行 ID
 * @param expectedStatus     期望当前状态
 * @param leaseOwner         期望租约持有者
 * @param expectedRowVersion 期望 Outbox rowVersion
 * @param command            Outbox 写入意图
 * @param observation        绑定观测值，可为 {@code null} 表示本次不更新绑定
 * @param nowEpochMillis     当前时间
 */
public record ProjectionSettleBundle(
        String eventId,
        ExternalKnowledgeOperationStatus expectedStatus,
        String leaseOwner,
        long expectedRowVersion,
        ExternalIndexSettleCommand command,
        KnowledgeExternalIndexBinding observation,
        long nowEpochMillis
) {

    public ProjectionSettleBundle {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(expectedStatus, "expectedStatus must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
    }
}
