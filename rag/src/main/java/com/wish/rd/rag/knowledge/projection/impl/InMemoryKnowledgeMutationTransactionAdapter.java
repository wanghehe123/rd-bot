package com.wish.rd.rag.knowledge.projection.impl;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.projection.InventorySupersedeConflictException;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeLoser;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.vector.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存 mutation 事务：任一步失败则恢复提交前快照。不调用执行器或远端。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryKnowledgeMutationTransactionAdapter implements KnowledgeMutationTransactionPort {

    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeDocumentRevisionStore revisionStore;
    private final KnowledgeChunkStore chunkStore;
    private final VectorStore vectorStore;
    private final KnowledgeExternalIndexBindingStore bindingStore;
    private final KnowledgeExternalIndexOutboxStore outboxStore;
    private final KnowledgeBaseStore baseStore;

    public InMemoryKnowledgeMutationTransactionAdapter(
            KnowledgeDocumentStore documentStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeChunkStore chunkStore,
            VectorStore vectorStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore
    ) {
        this(documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore, null);
    }

    public InMemoryKnowledgeMutationTransactionAdapter(
            KnowledgeDocumentStore documentStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeChunkStore chunkStore,
            VectorStore vectorStore,
            KnowledgeExternalIndexBindingStore bindingStore,
            KnowledgeExternalIndexOutboxStore outboxStore,
            KnowledgeBaseStore baseStore
    ) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.revisionStore = Objects.requireNonNull(revisionStore, "revisionStore must not be null");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.bindingStore = Objects.requireNonNull(bindingStore, "bindingStore must not be null");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore must not be null");
        this.baseStore = baseStore;
    }

    @Override
    public KnowledgeDocument commit(KnowledgeDocumentMutationBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Snapshot snapshot = Snapshot.capture(
                documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
        try {
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
                if (baseStore == null) {
                    throw new IllegalStateException("knowledge base store is required for base mutations");
                }
                baseStore.save(bundle.knowledgeBase());
            }
            return saved;
        } catch (RuntimeException exception) {
            snapshot.restore(documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
            throw exception;
        }
    }

    @Override
    public KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Snapshot snapshot = Snapshot.capture(
                documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
        try {
            return applyBackfill(bundle);
        } catch (RuntimeException exception) {
            snapshot.restore(documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
            throw exception;
        }
    }

    @Override
    public void commitSupersede(KnowledgeProjectionSupersedeBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Snapshot snapshot = Snapshot.capture(
                documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
        try {
            applySupersede(bundle);
        } catch (RuntimeException exception) {
            snapshot.restore(documentStore, revisionStore, chunkStore, vectorStore, bindingStore, outboxStore);
            throw exception;
        }
    }

    private KnowledgeProjectionBackfillCommitResult applyBackfill(KnowledgeProjectionBackfillBundle bundle) {
        if (bindingStore.findByProviderAndDocumentId(
                KnowledgeExternalIndexBinding.OPENVIKING, bundle.documentId()).isPresent()) {
            return KnowledgeProjectionBackfillCommitResult.ALREADY_BOUND;
        }
        KnowledgeDocument current = documentStore.findById(bundle.documentId()).orElse(null);
        if (current == null) {
            return KnowledgeProjectionBackfillCommitResult.CONCURRENT_MODIFICATION;
        }
        if (current.sourceIdentityKey().isBlank()) {
            boolean cas = documentStore.updateIdentityIfUnchanged(
                    bundle.documentId(),
                    bundle.expectedRowVersion(),
                    bundle.sourceIdentityKey(),
                    bundle.currentRevisionId(),
                    bundle.nowEpochMillis());
            if (!cas) {
                return KnowledgeProjectionBackfillCommitResult.CONCURRENT_MODIFICATION;
            }
        } else if (current.rowVersion() != bundle.expectedRowVersion()) {
            return KnowledgeProjectionBackfillCommitResult.CONCURRENT_MODIFICATION;
        }
        if (bundle.revision() != null
                && revisionStore.findByDocumentIdAndChecksum(
                        bundle.revision().documentId(), bundle.revision().checksum()).isEmpty()) {
            revisionStore.save(bundle.revision());
        }
        if (bundle.binding() != null) {
            bindingStore.insertIfAbsent(bundle.binding());
        }
        if (bundle.outbox() != null) {
            outboxStore.enqueue(bundle.outbox());
        }
        return KnowledgeProjectionBackfillCommitResult.APPLIED;
    }

    private void applySupersede(KnowledgeProjectionSupersedeBundle bundle) {
        for (KnowledgeProjectionSupersedeLoser loser : bundle.losers()) {
            boolean cas = documentStore.markSupersededIfVersionMatches(
                    loser.documentId(),
                    loser.expectedRowVersion(),
                    bundle.survivorDocumentId(),
                    bundle.nowEpochMillis());
            if (!cas) {
                throw new InventorySupersedeConflictException(
                        "row_version mismatch while superseding " + loser.documentId());
            }
            if (loser.absentBinding() != null) {
                bindingStore.save(loser.absentBinding());
            }
            if (loser.deleteOperation() != null) {
                outboxStore.enqueue(loser.deleteOperation());
            }
        }
    }

    private record Snapshot(
            List<KnowledgeDocument> documents,
            Map<String, String> rawContents,
            List<KnowledgeDocumentRevision> revisions,
            List<KnowledgeChunk> chunks,
            List<RetrievedChunk> vectors,
            List<KnowledgeExternalIndexBinding> bindings,
            List<KnowledgeExternalIndexOperation> outbox
    ) {

        static Snapshot capture(
                KnowledgeDocumentStore documentStore,
                KnowledgeDocumentRevisionStore revisionStore,
                KnowledgeChunkStore chunkStore,
                VectorStore vectorStore,
                KnowledgeExternalIndexBindingStore bindingStore,
                KnowledgeExternalIndexOutboxStore outboxStore
        ) {
            List<KnowledgeDocument> documents = documentStore.listAll();
            LinkedHashMap<String, String> rawContents = new LinkedHashMap<>();
            for (KnowledgeDocument document : documents) {
                rawContents.put(document.id(), documentStore.rawContent(document.id()));
            }
            return new Snapshot(
                    List.copyOf(documents),
                    Map.copyOf(rawContents),
                    List.copyOf(revisionStore.listAll()),
                    List.copyOf(chunkStore.listAll()),
                    List.copyOf(vectorStore.allChunks()),
                    List.copyOf(bindingStore.listAll()),
                    List.copyOf(outboxStore.listAll())
            );
        }

        void restore(
                KnowledgeDocumentStore documentStore,
                KnowledgeDocumentRevisionStore revisionStore,
                KnowledgeChunkStore chunkStore,
                VectorStore vectorStore,
                KnowledgeExternalIndexBindingStore bindingStore,
                KnowledgeExternalIndexOutboxStore outboxStore
        ) {
            for (KnowledgeDocument document : new ArrayList<>(documentStore.listAll())) {
                documentStore.delete(document.id());
            }
            documents.forEach(document -> documentStore.save(document, rawContents.get(document.id())));

            for (KnowledgeDocumentRevision revision : new ArrayList<>(revisionStore.listAll())) {
                revisionStore.delete(revision.id());
            }
            revisions.forEach(revisionStore::save);

            for (KnowledgeChunk chunk : new ArrayList<>(chunkStore.listAll())) {
                chunkStore.delete(chunk.id());
            }
            chunkStore.saveAll(chunks);

            vectorStore.removeChunks(vectorStore.allChunks().stream().map(RetrievedChunk::chunkId).toList());
            vectorStore.index(vectors);

            for (KnowledgeExternalIndexBinding binding : new ArrayList<>(bindingStore.listAll())) {
                bindingStore.delete(binding.provider(), binding.documentId());
            }
            bindings.forEach(bindingStore::save);

            for (KnowledgeExternalIndexOperation operation : new ArrayList<>(outboxStore.listAll())) {
                outboxStore.delete(operation.eventId());
            }
            outbox.forEach(outboxStore::enqueue);
        }
    }
}
