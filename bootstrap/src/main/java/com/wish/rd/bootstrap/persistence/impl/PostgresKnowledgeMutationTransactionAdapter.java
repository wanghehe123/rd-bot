package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.knowledge.DuplicateSourceIdentityException;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.projection.InventorySupersedeConflictException;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * PostgreSQL mutation 事务：文档、revision、chunk/vector、binding、outbox 同一提交。
 * 事务内不调用 OpenViking、模型或执行器。
 *
 * <p>不能是 {@code final}：{@code @Transactional} 依赖 CGLIB 子类代理，final 类会让
 * 应用在 {@code rd.knowledge.store=postgres} 下直接启动失败（单测不加载 Spring 上下文，
 * 只有真机启动才暴露）。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresKnowledgeMutationTransactionAdapter implements KnowledgeMutationTransactionPort {

    private static final String ACTIVE_IDENTITY_INDEX = "uk_knowledge_documents_active_identity";

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
        KnowledgeDocument saved;
        try {
            saved = bundle.document() == null
                    ? null
                    : documentStore.save(bundle.document(), bundle.rawContent());
        } catch (DataIntegrityViolationException exception) {
            throw translateIdentityConflict(exception);
        }
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

    @Override
    @Transactional
    public KnowledgeProjectionBackfillCommitResult commitBackfill(KnowledgeProjectionBackfillBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
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

    @Override
    @Transactional
    public void commitSupersede(KnowledgeProjectionSupersedeBundle bundle) {
        Objects.requireNonNull(bundle, "bundle must not be null");
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

    /**
     * 把 active-only 身份唯一索引的冲突翻译成领域异常。
     *
     * <p>两个请求同时为同一个外部来源建首份文档时，双方都扫不到既有行，都走新建，
     * 输家在提交时撞索引。不翻译的话调用方只能看到 500，既不知道发生了什么，也不知道
     * 重试就能成功——而此时赢家已经把那份文档建好了。
     */
    private RuntimeException translateIdentityConflict(DataIntegrityViolationException exception) {
        String detail = String.valueOf(exception.getMostSpecificCause().getMessage());
        if (detail.contains(ACTIVE_IDENTITY_INDEX)) {
            return new DuplicateSourceIdentityException(
                    "another writer just created the active document for this source identity");
        }
        return exception;
    }
}
