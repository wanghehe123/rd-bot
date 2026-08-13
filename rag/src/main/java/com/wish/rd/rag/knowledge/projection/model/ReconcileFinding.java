package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一次对账发现。身份是 (provider, kb, type, uri, documentId)，重复扫描只推进 last_seen。
 *
 * @param id                     行 ID
 * @param provider               外部索引提供者
 * @param knowledgeBaseId        知识库 ID
 * @param findingType            发现种类
 * @param remoteUri              相关远端 URI，孤儿/外来资源用这个定位
 * @param documentId             关联文档；孤儿没有本地文档时为空
 * @param detail                 已脱敏的说明
 * @param status                 处置状态
 * @param firstSeenAtEpochMillis 首次见到
 * @param lastSeenAtEpochMillis  最近一次见到
 */
public record ReconcileFinding(
        String id,
        String provider,
        String knowledgeBaseId,
        ReconcileFindingType findingType,
        String remoteUri,
        String documentId,
        String detail,
        ReconcileFindingStatus status,
        long firstSeenAtEpochMillis,
        long lastSeenAtEpochMillis
) {

    public ReconcileFinding {
        Objects.requireNonNull(id, "id must not be null");
        provider = provider == null || provider.isBlank()
                ? KnowledgeExternalIndexBinding.OPENVIKING
                : provider.strip().toUpperCase();
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(findingType, "findingType must not be null");
        remoteUri = remoteUri == null ? "" : remoteUri;
        documentId = documentId == null ? "" : documentId;
        detail = detail == null ? "" : detail;
        status = status == null ? ReconcileFindingStatus.OPEN : status;
        firstSeenAtEpochMillis = Math.max(0L, firstSeenAtEpochMillis);
        lastSeenAtEpochMillis = Math.max(firstSeenAtEpochMillis, lastSeenAtEpochMillis);
    }

    /**
     * 同一身份再次被扫到：保留首次见到时间，只推进最近见到时间。
     *
     * @param nowEpochMillis 本次扫描时间
     * @return 更新 last_seen 后的发现
     */
    public ReconcileFinding seenAgain(long nowEpochMillis) {
        return new ReconcileFinding(
                id,
                provider,
                knowledgeBaseId,
                findingType,
                remoteUri,
                documentId,
                detail,
                status,
                firstSeenAtEpochMillis,
                Math.max(lastSeenAtEpochMillis, nowEpochMillis)
        );
    }
}
