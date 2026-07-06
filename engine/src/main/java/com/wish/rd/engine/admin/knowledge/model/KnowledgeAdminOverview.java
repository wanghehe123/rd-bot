package com.wish.rd.engine.admin.knowledge.model;

public record KnowledgeAdminOverview(
        long knowledgeBaseCount,
        long documentCount,
        long indexedDocumentCount,
        long chunkCount,
        long enabledChunkCount,
        long vectorChunkCount
) {
}
