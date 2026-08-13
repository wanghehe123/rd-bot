package com.wish.rd.rag.knowledge.projection.model;

import java.util.Objects;

/**
 * 本地孤儿漂移：绑定期望仍为 PRESENT，但文档已不可见。不计入分类求和。
 */
public record InventoryDriftEntry(
        String knowledgeBaseId,
        String documentId,
        String remoteUri,
        ExternalKnowledgeDesiredState desiredState
) {

    public InventoryDriftEntry {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        remoteUri = remoteUri == null ? "" : remoteUri;
        desiredState = desiredState == null ? ExternalKnowledgeDesiredState.PRESENT : desiredState;
    }
}
