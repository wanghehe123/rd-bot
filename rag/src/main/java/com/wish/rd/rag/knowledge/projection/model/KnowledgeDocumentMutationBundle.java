package com.wish.rd.rag.knowledge.projection.model;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;

import java.util.List;

/**
 * 一次知识 mutation 在事务内提交的完整包。解析/分块在事务外完成。
 */
public record KnowledgeDocumentMutationBundle(
        KnowledgeDocument document,
        String rawContent,
        KnowledgeDocumentRevision revision,
        List<KnowledgeChunk> chunks,
        List<RetrievedChunk> vectors,
        List<String> removedChunkIds,
        boolean removeStoredChunks,
        KnowledgeExternalIndexBinding binding,
        KnowledgeExternalIndexOperation outbox,
        KnowledgeBase knowledgeBase
) {

    public KnowledgeDocumentMutationBundle {
        if (document == null && knowledgeBase == null) {
            throw new IllegalArgumentException("document or knowledgeBase must be present");
        }
        rawContent = rawContent == null ? "" : rawContent;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        vectors = vectors == null ? List.of() : List.copyOf(vectors);
        removedChunkIds = removedChunkIds == null ? List.of() : List.copyOf(removedChunkIds);
    }

    public KnowledgeDocumentMutationBundle(
            KnowledgeDocument document,
            String rawContent,
            KnowledgeDocumentRevision revision,
            List<KnowledgeChunk> chunks,
            List<RetrievedChunk> vectors,
            List<String> removedChunkIds,
            KnowledgeExternalIndexBinding binding,
            KnowledgeExternalIndexOperation outbox
    ) {
        this(document, rawContent, revision, chunks, vectors, removedChunkIds, true, binding, outbox, null);
    }
}
