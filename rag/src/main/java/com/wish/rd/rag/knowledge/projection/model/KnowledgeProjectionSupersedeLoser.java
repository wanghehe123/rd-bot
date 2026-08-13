package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 一次取代事务里的被取代文档：窄 CAS 标 superseded，可选 binding ABSENT 与删除操作。
 */
public record KnowledgeProjectionSupersedeLoser(
        String documentId,
        long expectedRowVersion,
        KnowledgeExternalIndexBinding absentBinding,
        KnowledgeExternalIndexOperation deleteOperation
) {

    public KnowledgeProjectionSupersedeLoser {
        Objects.requireNonNull(documentId, "documentId must not be null");
        expectedRowVersion = Math.max(0L, expectedRowVersion);
    }
}
