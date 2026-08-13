package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.vector.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * PostgreSQL mutation 事务：文档、revision、chunk/vector、binding、outbox 同一提交。
 * 事务内不调用 OpenViking、模型或执行器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresKnowledgeMutationTransactionAdapter implements KnowledgeMutationTransactionPort {

    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeDocumentRevisionStore revisionStore;
    private final KnowledgeChunkStore chunkStore;
    private final VectorStore vectorStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeBaseStore baseStore;

    public PostgresKnowledgeMutationTransactionAdapter(
            KnowledgeDocumentStore documentStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeChunkStore chunkStore,
            VectorStore vectorStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeBaseStore baseStore
    ) {
        this.documentStore = Objects.requireNonNull(documentStore);
        this.revisionStore = Objects.requireNonNull(revisionStore);
        this.chunkStore = Objects.requireNonNull(chunkStore);
        this.vectorStore = Objects.requireNonNull(vectorStore);
        this.bindingStore = Objects.requireNonNull(bindingStore);
        this.outboxStore = Objects.requireNonNull(outboxStore);
        this.baseStore = Objects.requireNonNull(baseStore);
    }

    @Override
    @Transactional
    public KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        KnowledgeDocument saved = bundle.document() == null
                ? null
                : documentStore.save(bundle.document(), bundle.rawContent());
        if (bundle.revision() != null) {
            revisionStore.save(bundle.revision());
        }
        if (!bundle.removedChunkIds().isEmpty()) {
            vectorStore.removeChunks(bundle.removedChunkIds());
            if (bundle.removeStoredChunks()) {
                bundle.removedChunkIds().forEach(chunkStore::delete);
            }
        }
        chunkStore.saveAll(bundle.chunks());
        vectorStore.index(bundle.vectors());
        if (bundle.binding() != null) {
            bindingStore.save(bundle.binding());
        }
        if (bundle.outbox() != null) {
            outboxStore.enqueue(bundle.outbox());
        }
        if (bundle.knowledgeBase() != null) {
            baseStore.save(bundle.knowledgeBase());
        }
        return saved;
    }
}
