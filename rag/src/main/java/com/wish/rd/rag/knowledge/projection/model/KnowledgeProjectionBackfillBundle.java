package com.wish.rd.rag.knowledge.projection.model;

import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;

import java.util.Objects;

/**
 * 存量回填在同一事务内提交的窄包：身份 CAS、幂等修订、绑定插入、outbox。
 * 不含分块与向量。
 */
public record KnowledgeProjectionBackfillBundle(
        String documentId,
        long expectedRowVersion,
        String sourceIdentityKey,
        String currentRevisionId,
        KnowledgeDocumentRevision revision,
        KnowledgeExternalIndexBinding binding,
        KnowledgeExternalIndexOperation outbox,
        long nowEpochMillis
) {

    public KnowledgeProjectionBackfillBundle {
        Objects.requireNonNull(documentId, "documentId must not be null");
        expectedRowVersion = Math.max(0L, expectedRowVersion);
        sourceIdentityKey = sourceIdentityKey == null ? "" : sourceIdentityKey;
        currentRevisionId = currentRevisionId == null ? "" : currentRevisionId;
        nowEpochMillis = Math.max(0L, nowEpochMillis);
    }
}
