package com.wish.rd.engine.admin.knowledge;

import com.wish.rd.rag.knowledge.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.KnowledgeWorkspace;
import org.springframework.stereotype.Service;

/**
 * 知识管理业务编排引擎。
 *
 * <p>封装 {@link KnowledgeWorkspace} 的概览统计能力，聚合知识库、文档（含已索引）、
 * 分块（含启用）、向量条目等计数，供 {@code KnowledgeAdminController} 调用。
 */
@Service
public final class KnowledgeAdminEngine {

    private final KnowledgeWorkspace workspace;

    public KnowledgeAdminEngine(KnowledgeWorkspace workspace) {
        this.workspace = workspace;
    }

    public KnowledgeAdminOverview overview() {
        long documentCount = workspace.listAllDocuments().size();
        long indexedDocumentCount = workspace.listAllDocuments().stream()
                .filter(document -> document.status() == KnowledgeDocumentStatus.INDEXED)
                .count();
        long chunkCount = workspace.listAllChunks().size();
        long enabledChunkCount = workspace.listAllChunks().stream()
                .filter(chunk -> chunk.enabled())
                .count();
        return new KnowledgeAdminOverview(
                workspace.listBases().size(),
                documentCount,
                indexedDocumentCount,
                chunkCount,
                enabledChunkCount,
                workspace.vectorStore().allChunks().size()
        );
    }
}
