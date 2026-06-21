package com.wish.rd.engine.admin.knowledge;

public record KnowledgeAdminOverview(
        long knowledgeBaseCount,
        long documentCount,
        long indexedDocumentCount,
        long chunkCount,
        long enabledChunkCount,
        long vectorChunkCount
) {
}
