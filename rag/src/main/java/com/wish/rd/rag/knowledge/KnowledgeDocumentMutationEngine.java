package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.TaskIngestionEngine;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.IngestionTaskResult;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeBaseLifecycle;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;
import com.wish.rd.rag.knowledge.projection.ExternalIndexIdempotencyKeys;
import com.wish.rd.rag.knowledge.projection.InventorySupersedeConflictException;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeMutationTransactionPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillOutcome;
import com.wish.rd.rag.knowledge.projection.model.InventoryBackfillStatus;
import com.wish.rd.rag.knowledge.projection.model.InventorySupersedeOutcome;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeDocumentMutationBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionBackfillCommitResult;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeBundle;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeProjectionSupersedeLoser;
import com.wish.rd.rag.knowledge.projection.model.SupersedeTarget;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * 唯一的知识 mutation 编排器：事务外解析分块，事务内提交 bundle，提交后再唤醒 Worker。
 */
@Component
public final class KnowledgeDocumentMutationEngine implements KnowledgeDocumentMutationPort {

    private static final long TOMBSTONE_GRACE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final String PROVIDER = KnowledgeExternalIndexBinding.OPENVIKING;

    private final SnowflakeIdGenerator idGenerator;
    private final KnowledgeBaseStore baseStore;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeDocumentRevisionStore revisionStore;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeMutationTransactionPort transactionPort;
    private final KnowledgeProjectionWakePort wakePort;
    private final KnowledgeExternalIndexBindingStore bindingStore;

    public KnowledgeDocumentMutationEngine(
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeMutationTransactionPort transactionPort,
            @Autowired(required = false) KnowledgeProjectionWakePort wakePort
    ) {
        this(idGenerator, baseStore, documentStore, revisionStore, chunkStore, transactionPort, wakePort, null);
    }

    @Autowired
    public KnowledgeDocumentMutationEngine(
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeMutationTransactionPort transactionPort,
            @Autowired(required = false) KnowledgeProjectionWakePort wakePort,
            @Autowired(required = false) KnowledgeExternalIndexBindingStore bindingStore
    ) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore must not be null");
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.revisionStore = Objects.requireNonNull(revisionStore, "revisionStore must not be null");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore must not be null");
        this.transactionPort = Objects.requireNonNull(transactionPort, "transactionPort must not be null");
        this.wakePort = wakePort == null ? KnowledgeProjectionWakePort.noop() : wakePort;
        this.bindingStore = bindingStore;
    }

    @Override
    public KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command) {
        return writeDocument(command, KnowledgeDocumentSource.local());
    }

    @Override
    public KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command, KnowledgeDocumentSource source) {
        return writeDocument(PipelineDefinition.defaultDocumentPipeline(), command, source);
    }

    @Override
    public KnowledgeDocument writeDocument(PipelineDefinition pipeline, IngestionTaskCommand command) {
        return writeDocument(pipeline, toWriteCommand(command), KnowledgeDocumentSource.local());
    }

    public KnowledgeDocument writeDocument(
            PipelineDefinition pipeline,
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source
    ) {
        requireActiveBase(command.knowledgeBaseId());
        rejectDuplicateSourceIdentity(command.knowledgeBaseId(), source);
        return indexDocument(pipeline, command, source, null, true);
    }

    /**
     * 这条路径永远新建 documentId，所以对已存在的活动来源身份必须显式拒绝。
     *
     * <p>WP-6 的 active-only 唯一索引会在库层拦住同样的写入，但那是
     * {@code DataIntegrityViolationException}，在 HTTP 层表现为 500，并且不会告诉
     * 调用方"应该走更新路径"。预检把它变成可理解的冲突。
     */
    private void rejectDuplicateSourceIdentity(String knowledgeBaseId, KnowledgeDocumentSource source) {
        String identity = SourceIdentityKeys.from(source);
        if (identity.isBlank()) {
            return;
        }
        boolean taken = documentStore.listByKnowledgeBaseId(knowledgeBaseId).stream()
                .filter(KnowledgeDocument::visible)
                .anyMatch(document -> identity.equals(document.sourceIdentityKey())
                        || matchesLegacySource(document, source));
        if (taken) {
            throw new DuplicateSourceIdentityException(
                    "knowledge base " + knowledgeBaseId + " already has an active document for this source identity; "
                            + "use the update path instead of creating a second one");
        }
    }

    @Override
    public KnowledgeDocument writeDocumentIfChanged(
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source
    ) {
        requireActiveBase(command.knowledgeBaseId());
        Optional<KnowledgeDocument> existing = findActiveBySource(command.knowledgeBaseId(), source);
        if (existing.isPresent()) {
            return syncExisting(command, source, existing.get());
        }
        try {
            // 上面的扫描已经证明没有活动同身份文档，直接建新的，不再走 writeDocument 的重复预检。
            return indexDocument(PipelineDefinition.defaultDocumentPipeline(), command, source, null, true);
        } catch (DuplicateSourceIdentityException conflict) {
            // 扫描与提交之间有人抢先建好了同身份文档。唯一索引只保证不出现第二份，而调用方
            // 的意图（让这个来源的内容变成最新）此刻更新赢家就能满足，不该以错误结束。重扫
            // 一次仍找不到，说明冲突另有来源，交回调用方。
            return findActiveBySource(command.knowledgeBaseId(), source)
                    .map(winner -> syncExisting(command, source, winner))
                    .orElseThrow(() -> conflict);
        }
    }

    /** 按来源身份找当前活动文档；本地匿名上传没有身份，永远返回空。 */
    private Optional<KnowledgeDocument> findActiveBySource(String knowledgeBaseId, KnowledgeDocumentSource source) {
        String identity = SourceIdentityKeys.from(source);
        if (identity.isBlank()) {
            return Optional.empty();
        }
        return documentStore.listByKnowledgeBaseId(knowledgeBaseId).stream()
                .filter(KnowledgeDocument::visible)
                .filter(document -> identity.equals(document.sourceIdentityKey())
                        || matchesLegacySource(document, source))
                .findFirst();
    }

    /** 把既有文档同步到来源当前状态：内容变了重新摄取，只有 revision 变了就记一次同步。 */
    private KnowledgeDocument syncExisting(
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source,
            KnowledgeDocument current
    ) {
        String digest = checksum(command.content());
        if (!digest.equals(current.checksum())) {
            return indexDocument(PipelineDefinition.defaultDocumentPipeline(), command, source, current, true);
        }
        if (source.revisionId().isBlank() || source.revisionId().equals(current.revisionId())) {
            return current;
        }
        KnowledgeDocument touched = current.withSyncState(
                source.revisionId(),
                current.checksum(),
                current.rawPreview(),
                source.lastSyncedAtEpochMillis() > 0L
                        ? source.lastSyncedAtEpochMillis()
                        : System.currentTimeMillis(),
                source.nextRefreshAtEpochMillis()
        );
        return commitAndWake(new KnowledgeDocumentMutationBundle(
                touched,
                documentStore.rawContent(current.id()),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                null
        ));
    }

    @Override
    public KnowledgeDocument rechunkDocument(String documentId, ChunkingMode mode, int chunkSize, int overlapSize) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        String rawContent = documentStore.rawContent(documentId);
        KnowledgeDocumentSource source = new KnowledgeDocumentSource(
                document.sourceType(),
                document.sourceToken(),
                document.sourceUrl(),
                document.revisionId(),
                document.lastSyncedAtEpochMillis(),
                document.nextRefreshAtEpochMillis()
        );
        WriteKnowledgeDocumentCommand command = new WriteKnowledgeDocumentCommand(
                document.knowledgeBaseId(),
                document.sourceName(),
                document.knowledgeType(),
                document.mimeType(),
                rawContent.getBytes(StandardCharsets.UTF_8),
                mode == null ? ChunkingMode.STRUCTURE_AWARE : mode,
                chunkSize,
                overlapSize
        );
        return indexDocument(PipelineDefinition.defaultDocumentPipeline(), command, source, document, false);
    }

    @Override
    public void deleteDocument(String documentId) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        long now = System.currentTimeMillis();
        KnowledgeDocument tombstone = document
                .withAvailability(document.enabled(), document.syncVersion() + 1L)
                .withSoftDeleted(now, now + TOMBSTONE_GRACE_MILLIS);
        List<String> chunkIds = chunkStore.listByDocumentId(document.id()).stream()
                .map(KnowledgeChunk::id)
                .toList();
        String operationId = idGenerator.nextIdString();
        commitAndWake(new KnowledgeDocumentMutationBundle(
                tombstone,
                documentStore.rawContent(document.id()),
                null,
                List.of(),
                List.of(),
                chunkIds,
                false,
                projectionBinding(tombstone, ExternalKnowledgeDesiredState.ABSENT, now, operationId),
                projectionOperation(tombstone, ExternalKnowledgeOperationType.DELETE_DOCUMENT, now, operationId),
                null
        ));
    }

    @Override
    public void deleteBase(String knowledgeBaseId) {
        KnowledgeBase base = requireActiveBase(knowledgeBaseId);
        documentStore.listByKnowledgeBaseId(base.id()).stream()
                .filter(KnowledgeDocument::visible)
                .map(KnowledgeDocument::id)
                .toList()
                .forEach(this::deleteDocument);
        long now = System.currentTimeMillis();
        KnowledgeBase deleting = base.withDeleting(now, now + TOMBSTONE_GRACE_MILLIS);
        KnowledgeExternalIndexOperation kbDelete = knowledgeBaseDeleteOperation(deleting, now);
        Optional<KnowledgeDocument> placeholder = documentStore.listByKnowledgeBaseId(base.id()).stream().findFirst();
        commitAndWake(new KnowledgeDocumentMutationBundle(
                placeholder.orElse(null),
                placeholder.map(document -> documentStore.rawContent(document.id())).orElse(""),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                kbDelete,
                deleting
        ));
    }

    @Override
    public KnowledgeChunk createChunk(String documentId, String chunkId, int index, String content) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        String actualChunkId = chunkId == null || chunkId.isBlank() ? idGenerator.nextIdString() : chunkId.strip();
        if (chunkStore.findById(actualChunkId).isPresent()) {
            throw new IllegalArgumentException("knowledge chunk already exists: " + actualChunkId);
        }
        int chunkIndex = Math.max(0, index);
        KnowledgeChunk chunk = new KnowledgeChunk(
                actualChunkId,
                document.id(),
                document.knowledgeBaseId(),
                chunkIndex,
                content == null ? "" : content,
                document.knowledgeType(),
                document.sourceName(),
                true,
                Map.of(
                        "documentId", document.id(),
                        "chunkIndex", String.valueOf(chunkIndex),
                        "manual", "true",
                        KnowledgeChunk.PROJECTION_MODE_KEY, KnowledgeChunk.LOCAL_ONLY_OVERRIDE
                )
        );
        KnowledgeDocument updated = document.withChunkCount(chunkStore.listByDocumentId(document.id()).size() + 1)
                .withLocalOnlyOverride(true);
        commitAndWake(new KnowledgeDocumentMutationBundle(
                updated,
                documentStore.rawContent(document.id()),
                null,
                List.of(chunk),
                List.of(toRetrievedChunk(chunk)),
                List.of(),
                false,
                null,
                null,
                null
        ));
        return chunk;
    }

    @Override
    public KnowledgeChunk updateChunk(String documentId, String chunkId, String content) {
        requireVisibleDocument(documentId);
        KnowledgeChunk existing = chunkStore.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge chunk not found: " + chunkId));
        if (!existing.documentId().equals(documentId)) {
            throw new IllegalArgumentException("knowledge chunk not found in document: " + chunkId);
        }
        KnowledgeChunk updated = existing.withContent(content == null ? "" : content).withLocalOnlyOverride();
        KnowledgeDocument document = requireVisibleDocument(documentId).withLocalOnlyOverride(true);
        commitAndWake(new KnowledgeDocumentMutationBundle(
                document,
                documentStore.rawContent(documentId),
                null,
                List.of(updated),
                List.of(toRetrievedChunk(updated)),
                List.of(chunkId),
                false,
                null,
                null,
                null
        ));
        return updated;
    }

    @Override
    public KnowledgeDocument setDocumentEnabled(String documentId, boolean enabled) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        if (document.enabled() == enabled) {
            return document;
        }
        long now = System.currentTimeMillis();
        KnowledgeDocument updated = document.withAvailability(enabled, document.syncVersion() + 1L);
        String operationId = idGenerator.nextIdString();
        ExternalKnowledgeDesiredState desired = enabled
                ? ExternalKnowledgeDesiredState.PRESENT
                : ExternalKnowledgeDesiredState.ABSENT;
        ExternalKnowledgeOperationType operationType = enabled
                ? ExternalKnowledgeOperationType.UPSERT_DOCUMENT
                : ExternalKnowledgeOperationType.DELETE_DOCUMENT;
        return commitAndWake(new KnowledgeDocumentMutationBundle(
                updated,
                documentStore.rawContent(document.id()),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                projectionBinding(updated, desired, now, operationId),
                projectionOperation(updated, operationType, now, operationId),
                null
        ));
    }

    @Override
    public KnowledgeDocument updateDocument(String documentId, String sourceName, String knowledgeType) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        String updatedSourceName = blank(sourceName) ? document.sourceName() : sourceName.strip();
        String updatedKnowledgeType = blank(knowledgeType) ? document.knowledgeType() : knowledgeType.strip();
        if (updatedSourceName.equals(document.sourceName()) && updatedKnowledgeType.equals(document.knowledgeType())) {
            return document;
        }
        KnowledgeDocument updated = document.withDocumentFields(updatedSourceName, updatedKnowledgeType);
        List<KnowledgeChunk> chunks = chunkStore.listByDocumentId(document.id()).stream()
                .map(chunk -> chunk.withDocumentFields(updatedKnowledgeType, updatedSourceName))
                .toList();
        List<RetrievedChunk> vectors = chunks.stream().map(this::toRetrievedChunk).toList();
        List<String> chunkIds = chunks.stream().map(KnowledgeChunk::id).toList();
        return commitAndWake(new KnowledgeDocumentMutationBundle(
                updated,
                documentStore.rawContent(document.id()),
                null,
                chunks,
                vectors,
                chunkIds,
                false,
                null,
                null,
                null
        ));
    }

    @Override
    public boolean deleteChunk(String documentId, String chunkId) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        Optional<KnowledgeChunk> existing = chunkStore.findById(chunkId);
        if (existing.isEmpty()) {
            return false;
        }
        if (!existing.get().documentId().equals(documentId)) {
            throw new IllegalArgumentException("knowledge chunk not found in document: " + chunkId);
        }
        int newChunkCount = Math.max(0, chunkStore.listByDocumentId(documentId).size() - 1);
        commitAndWake(new KnowledgeDocumentMutationBundle(
                document.withChunkCount(newChunkCount).withLocalOnlyOverride(true),
                documentStore.rawContent(documentId),
                null,
                List.of(),
                List.of(),
                List.of(chunkId),
                true,
                null,
                null,
                null
        ));
        return true;
    }

    @Override
    public KnowledgeChunk setChunkEnabled(String chunkId, boolean enabled) {
        KnowledgeChunk existing = chunkStore.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge chunk not found: " + chunkId));
        KnowledgeDocument document = requireVisibleDocument(existing.documentId());
        if (existing.enabled() == enabled) {
            return existing;
        }
        KnowledgeChunk updated = existing.withEnabled(enabled);
        commitAndWake(new KnowledgeDocumentMutationBundle(
                document,
                documentStore.rawContent(document.id()),
                null,
                List.of(updated),
                List.of(),
                List.of(),
                false,
                null,
                null,
                null
        ));
        return updated;
    }

    @Override
    public int batchSetChunksEnabled(String documentId, List<String> chunkIds, boolean enabled) {
        KnowledgeDocument document = requireVisibleDocument(documentId);
        List<KnowledgeChunk> current = chunkStore.listByDocumentId(documentId);
        List<String> targets = chunkIds == null || chunkIds.isEmpty()
                ? current.stream().map(KnowledgeChunk::id).toList()
                : List.copyOf(chunkIds);
        ArrayList<KnowledgeChunk> updated = new ArrayList<>();
        for (String chunkId : targets) {
            KnowledgeChunk existing = current.stream()
                    .filter(chunk -> chunk.id().equals(chunkId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("knowledge chunk not found in document: " + chunkId));
            if (existing.enabled() != enabled) {
                updated.add(existing.withEnabled(enabled));
            }
        }
        if (!updated.isEmpty()) {
            commitAndWake(new KnowledgeDocumentMutationBundle(
                    document,
                    documentStore.rawContent(documentId),
                    null,
                    updated,
                    List.of(),
                    List.of(),
                    false,
                    null,
                    null,
                    null
            ));
        }
        return targets.size();
    }

    /**
     * 存量回填：补身份、修订、绑定与 UPSERT，不重切块、不递增 {@code sync_version}。
     */
    public InventoryBackfillOutcome backfillProjection(String documentId, long nowEpochMillis) {
        if (documentId == null || documentId.isBlank()) {
            return InventoryBackfillOutcome.skipped(
                    "", InventoryBackfillStatus.SKIPPED_NOT_ELIGIBLE, "document id is blank");
        }
        KnowledgeDocument document = documentStore.findById(documentId).orElse(null);
        if (document == null) {
            return InventoryBackfillOutcome.skipped(
                    documentId, InventoryBackfillStatus.SKIPPED_NOT_ELIGIBLE, "document not found");
        }
        String ineligible = ineligibleReason(document);
        if (ineligible != null) {
            return InventoryBackfillOutcome.skipped(
                    documentId, InventoryBackfillStatus.SKIPPED_NOT_ELIGIBLE, ineligible);
        }
        String identity = document.sourceIdentityKey().isBlank()
                ? SourceIdentityKeys.from(document.sourceType(), document.sourceToken(), document.sourceUrl())
                : document.sourceIdentityKey();
        KnowledgeDocumentRevision revision = revisionStore.findByDocumentIdAndChecksum(
                        document.id(), document.checksum())
                .orElseGet(() -> new KnowledgeDocumentRevision(
                        idGenerator.nextIdString(),
                        document.id(),
                        document.syncVersion(),
                        document.revisionId(),
                        document.checksum(),
                        document.mimeType(),
                        documentStore.rawContent(document.id()),
                        "rd-bot-default",
                        "1",
                        nowEpochMillis
                ));
        String operationId = idGenerator.nextIdString();
        KnowledgeExternalIndexBinding binding = backfillBinding(document, operationId, nowEpochMillis);
        KnowledgeExternalIndexOperation outbox = projectionOperation(
                document.withIdentityRevision(identity, revision.id()).withRowVersion(document.rowVersion()),
                ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                nowEpochMillis,
                operationId
        );
        KnowledgeProjectionBackfillCommitResult result = transactionPort.commitBackfill(
                new KnowledgeProjectionBackfillBundle(
                        document.id(),
                        document.rowVersion(),
                        identity,
                        revision.id(),
                        revision,
                        binding,
                        outbox,
                        nowEpochMillis
                )
        );
        return switch (result) {
            case APPLIED -> {
                wakeQuietly();
                yield InventoryBackfillOutcome.applied(document.id());
            }
            case ALREADY_BOUND -> InventoryBackfillOutcome.skipped(
                    document.id(),
                    InventoryBackfillStatus.SKIPPED_ALREADY_BOUND,
                    "document already has an external index binding");
            case CONCURRENT_MODIFICATION -> InventoryBackfillOutcome.skipped(
                    document.id(),
                    InventoryBackfillStatus.SKIPPED_CONCURRENT_MODIFICATION,
                    "document row_version changed during backfill");
        };
    }

    /**
     * 操作员确认的重复收敛。任一 loser CAS 失败则整笔不提交。
     */
    public InventorySupersedeOutcome supersedeDuplicate(
            String survivorId,
            List<SupersedeTarget> losers,
            long nowEpochMillis
    ) {
        if (survivorId == null || survivorId.isBlank()) {
            return InventorySupersedeOutcome.conflict("survivor id is blank");
        }
        if (losers == null || losers.isEmpty()) {
            return InventorySupersedeOutcome.conflict("losers must not be empty");
        }
        KnowledgeDocument survivor = documentStore.findById(survivorId).orElse(null);
        if (survivor == null || !survivor.visible()) {
            return InventorySupersedeOutcome.conflict("survivor is not a visible document");
        }
        ArrayList<KnowledgeProjectionSupersedeLoser> bundleLosers = new ArrayList<>();
        for (SupersedeTarget target : losers) {
            if (target == null || target.documentId().isBlank()) {
                return InventorySupersedeOutcome.conflict("loser id is blank");
            }
            if (survivorId.equals(target.documentId())) {
                return InventorySupersedeOutcome.conflict("survivor cannot supersede itself");
            }
            KnowledgeDocument loser = documentStore.findById(target.documentId()).orElse(null);
            if (loser == null) {
                return InventorySupersedeOutcome.conflict("loser not found: " + target.documentId());
            }
            if (!survivor.knowledgeBaseId().equals(loser.knowledgeBaseId())) {
                return InventorySupersedeOutcome.conflict("loser is not in the survivor knowledge base");
            }
            KnowledgeExternalIndexBinding absent = null;
            KnowledgeExternalIndexOperation deleteOperation = null;
            if (bindingStore != null) {
                Optional<KnowledgeExternalIndexBinding> existing = bindingStore.findByProviderAndDocumentId(
                        PROVIDER, loser.id());
                if (existing.isPresent()) {
                    String operationId = idGenerator.nextIdString();
                    absent = projectionBinding(loser, ExternalKnowledgeDesiredState.ABSENT, nowEpochMillis, operationId);
                    deleteOperation = projectionOperation(
                            loser, ExternalKnowledgeOperationType.DELETE_DOCUMENT, nowEpochMillis, operationId);
                }
            }
            bundleLosers.add(new KnowledgeProjectionSupersedeLoser(
                    loser.id(), target.expectedRowVersion(), absent, deleteOperation));
        }
        try {
            transactionPort.commitSupersede(new KnowledgeProjectionSupersedeBundle(
                    survivorId, bundleLosers, nowEpochMillis));
            wakeQuietly();
            return InventorySupersedeOutcome.applied();
        } catch (InventorySupersedeConflictException exception) {
            return InventorySupersedeOutcome.conflict(exception.getMessage());
        }
    }

    private String ineligibleReason(KnowledgeDocument document) {
        if (!document.visible()) {
            return "document is not visible";
        }
        KnowledgeBase base = baseStore.findById(document.knowledgeBaseId()).orElse(null);
        if (base == null || base.lifecycleStatus() != KnowledgeBaseLifecycle.ACTIVE) {
            return "knowledge base is not active";
        }
        if (document.localOnlyOverride()) {
            return "document is marked local-only override";
        }
        if (document.chunkCount() <= 0 || document.checksum().isBlank()) {
            return "document has no indexable content";
        }
        return null;
    }

    private KnowledgeExternalIndexBinding backfillBinding(
            KnowledgeDocument document,
            String operationId,
            long now
    ) {
        String remoteUri = OpenVikingProjectionUris.documentRootUri(document.knowledgeBaseId(), document.id());
        return new KnowledgeExternalIndexBinding(
                PROVIDER,
                document.id(),
                document.knowledgeBaseId(),
                remoteUri,
                OpenVikingProjectionUris.ownershipMarker(document.knowledgeBaseId(), document.id()),
                ExternalKnowledgeDesiredState.PRESENT,
                document.syncVersion(),
                document.checksum(),
                ExternalKnowledgeObservedState.UNKNOWN,
                0L,
                "",
                ExternalKnowledgeProjectionStatus.PENDING,
                operationId,
                "",
                "",
                0L,
                0L,
                "",
                "",
                0L,
                now,
                now
        );
    }

    private void wakeQuietly() {
        try {
            wakePort.wake();
        } catch (RejectedExecutionException ignored) {
            // PENDING outbox is already durable.
        }
    }

    private KnowledgeDocument indexDocument(
            PipelineDefinition pipeline,
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source,
            KnowledgeDocument previous,
            boolean sourceMutation
    ) {
        String documentId = previous == null ? idGenerator.nextIdString() : previous.id();
        List<String> removedChunkIds = previous == null
                ? List.of()
                : chunkStore.listByDocumentId(previous.id()).stream().map(KnowledgeChunk::id).toList();
        IngestionTaskResult ingestionResult = TaskIngestionEngine.inMemory(new InMemoryVectorStore()).execute(
                pipeline,
                new IngestionTaskCommand(
                        "task-inline-" + documentId,
                        command.sourceName(),
                        command.knowledgeBaseId(),
                        command.knowledgeType(),
                        command.mimeType(),
                        command.content(),
                        command.chunkingMode(),
                        command.chunkSize(),
                        command.overlapSize()
                )
        );
        ArrayList<KnowledgeChunk> persistedChunks = new ArrayList<>();
        ArrayList<RetrievedChunk> indexedChunks = new ArrayList<>();
        List<RetrievedChunk> temporaryChunks = ingestionResult.chunks();
        for (int i = 0; i < temporaryChunks.size(); i++) {
            RetrievedChunk retrievedChunk = temporaryChunks.get(i);
            int chunkIndex = chunkIndex(retrievedChunk, i);
            KnowledgeChunk chunk = new KnowledgeChunk(
                    idGenerator.nextIdString(),
                    documentId,
                    command.knowledgeBaseId(),
                    chunkIndex,
                    retrievedChunk.content(),
                    retrievedChunk.knowledgeType(),
                    retrievedChunk.sourceName(),
                    true,
                    withDocumentMetadata(retrievedChunk.metadata(), documentId)
            );
            persistedChunks.add(chunk);
            indexedChunks.add(toRetrievedChunk(chunk));
        }
        String rawContent = new String(command.content(), StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        String digest = checksum(command.content());
        String identity = previous != null && !previous.sourceIdentityKey().isBlank()
                ? previous.sourceIdentityKey()
                : SourceIdentityKeys.from(source);
        long syncVersion = previous == null ? 1L : previous.syncVersion();
        String currentRevisionId = previous == null ? "" : previous.currentRevisionId();
        KnowledgeDocumentRevision pendingRevision = null;
        if (sourceMutation) {
            long revisionSyncVersion = previous == null ? 1L : previous.syncVersion() + 1L;
            syncVersion = revisionSyncVersion;
            pendingRevision = revisionStore.findByDocumentIdAndChecksum(documentId, digest)
                    .orElseGet(() -> new KnowledgeDocumentRevision(
                            idGenerator.nextIdString(),
                            documentId,
                            revisionSyncVersion,
                            source.revisionId(),
                            digest,
                            command.mimeType(),
                            rawContent,
                            "rd-bot-default",
                            "1",
                            now
                    ));
            currentRevisionId = pendingRevision.id();
        }
        KnowledgeDocument document;
        if (previous == null) {
            document = new KnowledgeDocument(
                    documentId,
                    command.knowledgeBaseId(),
                    command.sourceName(),
                    command.knowledgeType(),
                    command.mimeType(),
                    KnowledgeDocumentStatus.INDEXED,
                    true,
                    persistedChunks.size(),
                    ingestionResult.nodeLogs(),
                    now,
                    source.sourceType(),
                    source.sourceToken(),
                    source.sourceUrl(),
                    source.revisionId(),
                    digest,
                    preview(rawContent),
                    source.lastSyncedAtEpochMillis() > 0L ? source.lastSyncedAtEpochMillis() : now,
                    source.nextRefreshAtEpochMillis(),
                    syncVersion,
                    currentRevisionId,
                    identity,
                    0L,
                    0L,
                    "",
                    0L,
                    false
            );
        } else {
            document = previous.withIndexedSnapshot(
                    command.sourceName(),
                    command.knowledgeType(),
                    command.mimeType(),
                    KnowledgeDocumentStatus.INDEXED,
                    persistedChunks.size(),
                    ingestionResult.nodeLogs(),
                    source.revisionId().isBlank() ? previous.revisionId() : source.revisionId(),
                    digest,
                    preview(rawContent),
                    source.lastSyncedAtEpochMillis() > 0L ? source.lastSyncedAtEpochMillis() : now,
                    source.nextRefreshAtEpochMillis(),
                    syncVersion,
                    currentRevisionId,
                    identity,
                    false
            );
        }
        String operationId = sourceMutation ? idGenerator.nextIdString() : "";
        KnowledgeExternalIndexBinding binding = sourceMutation
                ? projectionBinding(document, ExternalKnowledgeDesiredState.PRESENT, now, operationId)
                : null;
        KnowledgeExternalIndexOperation outbox = sourceMutation
                ? projectionOperation(document, ExternalKnowledgeOperationType.UPSERT_DOCUMENT, now, operationId)
                : null;
        return commitAndWake(new KnowledgeDocumentMutationBundle(
                document,
                rawContent,
                pendingRevision,
                persistedChunks,
                indexedChunks,
                removedChunkIds,
                true,
                binding,
                outbox,
                null
        ));
    }

    private KnowledgeExternalIndexOperation knowledgeBaseDeleteOperation(KnowledgeBase deleting, long now) {
        return new KnowledgeExternalIndexOperation(
                idGenerator.nextIdString(),
                ExternalIndexIdempotencyKeys.knowledgeBase(
                        PROVIDER, deleting.id(), deleting.syncVersion(), ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE),
                PROVIDER,
                ExternalKnowledgeOperationType.DELETE_KNOWLEDGE_BASE,
                deleting.id(),
                "",
                deleting.syncVersion(),
                "",
                OpenVikingProjectionUris.knowledgeBaseRoot(deleting.id()),
                "",
                "",
                ExternalKnowledgeOperationStatus.PENDING,
                "",
                "",
                "",
                0L,
                0,
                8,
                now,
                0L,
                "",
                "",
                0L,
                now,
                now
        );
    }

    private KnowledgeDocument commitAndWake(KnowledgeDocumentMutationBundle bundle) {
        KnowledgeDocument saved = transactionPort.commit(bundle);
        try {
            wakePort.wake();
        } catch (RejectedExecutionException ignored) {
            // PENDING outbox is already durable.
        }
        return saved;
    }

    private KnowledgeExternalIndexBinding projectionBinding(
            KnowledgeDocument document,
            ExternalKnowledgeDesiredState desiredState,
            long now,
            String operationId
    ) {
        String remoteUri = OpenVikingProjectionUris.documentRootUri(document.knowledgeBaseId(), document.id());
        ExternalKnowledgeProjectionStatus projectionStatus = desiredState == ExternalKnowledgeDesiredState.ABSENT
                ? ExternalKnowledgeProjectionStatus.DELETING
                : ExternalKnowledgeProjectionStatus.PENDING;
        return new KnowledgeExternalIndexBinding(
                PROVIDER,
                document.id(),
                document.knowledgeBaseId(),
                remoteUri,
                OpenVikingProjectionUris.ownershipMarker(document.knowledgeBaseId(), document.id()),
                desiredState,
                document.syncVersion(),
                document.checksum(),
                ExternalKnowledgeObservedState.UNKNOWN,
                0L,
                "",
                projectionStatus,
                operationId,
                "",
                "",
                0L,
                0L,
                "",
                "",
                0L,
                now,
                now
        );
    }

    private KnowledgeExternalIndexOperation projectionOperation(
            KnowledgeDocument document,
            ExternalKnowledgeOperationType operationType,
            long now,
            String eventId
    ) {
        return new KnowledgeExternalIndexOperation(
                eventId,
                ExternalIndexIdempotencyKeys.document(PROVIDER, document.id(), document.syncVersion(), operationType),
                PROVIDER,
                operationType,
                document.knowledgeBaseId(),
                document.id(),
                document.syncVersion(),
                document.checksum(),
                OpenVikingProjectionUris.documentRootUri(document.knowledgeBaseId(), document.id()),
                document.currentRevisionId(),
                "",
                ExternalKnowledgeOperationStatus.PENDING,
                "",
                "",
                "",
                0L,
                0,
                8,
                now,
                0L,
                "",
                "",
                0L,
                now,
                now
        );
    }

    private KnowledgeBase requireActiveBase(String knowledgeBaseId) {
        KnowledgeBase base = baseStore.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId));
        if (!base.visible()) {
            throw new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId);
        }
        return base;
    }

    private KnowledgeDocument requireVisibleDocument(String documentId) {
        KnowledgeDocument document = documentStore.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found: " + documentId));
        if (!document.visible()) {
            throw new IllegalArgumentException("knowledge document not found: " + documentId);
        }
        return document;
    }

    private boolean matchesLegacySource(KnowledgeDocument existing, KnowledgeDocumentSource source) {
        if (!existing.sourceType().equals(source.sourceType())) {
            return false;
        }
        if (!source.sourceToken().isBlank() && source.sourceToken().equals(existing.sourceToken())) {
            return true;
        }
        return !source.sourceUrl().isBlank() && source.sourceUrl().equals(existing.sourceUrl());
    }

    private WriteKnowledgeDocumentCommand toWriteCommand(IngestionTaskCommand command) {
        return new WriteKnowledgeDocumentCommand(
                command.knowledgeBaseId(),
                command.sourceName(),
                command.knowledgeType(),
                command.mimeType(),
                command.content(),
                command.chunkingMode(),
                command.chunkSize(),
                command.overlapSize()
        );
    }

    private RetrievedChunk toRetrievedChunk(KnowledgeChunk chunk) {
        return new RetrievedChunk(
                chunk.id(),
                chunk.content(),
                chunk.knowledgeBaseId(),
                chunk.knowledgeType(),
                chunk.sourceName(),
                1.0d,
                chunk.metadata()
        );
    }

    private int chunkIndex(RetrievedChunk chunk, int fallback) {
        String rawIndex = chunk.metadata().get("chunkIndex");
        if (rawIndex == null || rawIndex.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(rawIndex);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, String> withDocumentMetadata(Map<String, String> metadata, String documentId) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>(metadata);
        copied.put("documentId", documentId);
        return copied;
    }

    private String checksum(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content == null ? new byte[0] : content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    private String preview(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        String stripped = rawContent.strip();
        return stripped.length() <= 512 ? stripped : stripped.substring(0, 512);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
