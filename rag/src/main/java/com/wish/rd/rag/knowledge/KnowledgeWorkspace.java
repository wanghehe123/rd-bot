package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.IngestionTaskResult;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.ingestion.TaskIngestionEngine;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import com.wish.rd.rag.knowledge.model.CreateKnowledgeBaseCommand;
import com.wish.rd.rag.knowledge.model.KnowledgeBase;
import com.wish.rd.rag.knowledge.model.KnowledgeChunk;
import com.wish.rd.rag.knowledge.model.KnowledgeDocument;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentSource;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentStatus;
import com.wish.rd.rag.knowledge.model.WriteKnowledgeDocumentCommand;

/**
 * 知识库工作区 facade：统一管理知识库、文档、分块与向量索引的一致性。
 *
 * <p>对上保持原有 {@code /knowledge-base} 管理接口语义，对下委托 Store 端口完成
 * 内存或 PostgreSQL 持久化。跨实体级联操作仍收敛在此聚合根，避免外部绕过根直接
 * 修改子实体导致向量库、分块计数和文档状态不一致。
 */
@Component
public final class KnowledgeWorkspace {

    private static final long TOMBSTONE_GRACE_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private final VectorStore vectorStore;
    private final SnowflakeIdGenerator idGenerator;
    private final KnowledgeBaseStore baseStore;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentRevisionStore revisionStore;

    public KnowledgeWorkspace(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeDocumentRevisionStore revisionStore
    ) {
        this.vectorStore = vectorStore;
        this.idGenerator = idGenerator;
        this.baseStore = baseStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.revisionStore = revisionStore;
    }

    /**
     * 创建一个空工作区，使用内存 Store 和默认 Snowflake 生成器。
     *
     * @return 内存知识工作区
     */
    public static KnowledgeWorkspace inMemory() {
        return inMemory(SnowflakeIdGenerator.defaultGenerator());
    }

    /**
     * 创建一个可注入 ID 生成器的内存工作区，供单测稳定断言。
     *
     * @param idGenerator ID 生成器
     * @return 内存知识工作区
     */
    public static KnowledgeWorkspace inMemory(SnowflakeIdGenerator idGenerator) {
        return withStores(
                new InMemoryVectorStore(),
                idGenerator,
                new InMemoryKnowledgeBaseStore(),
                new InMemoryKnowledgeDocumentStore(),
                new InMemoryKnowledgeChunkStore(),
                new InMemoryKnowledgeDocumentRevisionStore()
        );
    }

    /**
     * 用指定 Store 组装工作区，供 PostgreSQL 适配层和 Store 边界测试使用。
     *
     * @param vectorStore   向量库端口
     * @param idGenerator   ID 生成器
     * @param baseStore     知识库 Store
     * @param documentStore 文档 Store
     * @param chunkStore    分块 Store
     * @return 知识工作区 facade
     */
    public static KnowledgeWorkspace withStores(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore
    ) {
        return withStores(
                vectorStore,
                idGenerator,
                baseStore,
                documentStore,
                chunkStore,
                new InMemoryKnowledgeDocumentRevisionStore()
        );
    }

    /**
     * 用指定 Store 组装工作区，供 PostgreSQL 适配层和 Store 边界测试使用。
     *
     * @param vectorStore    向量库端口
     * @param idGenerator    ID 生成器
     * @param baseStore      知识库 Store
     * @param documentStore  文档 Store
     * @param chunkStore     分块 Store
     * @param revisionStore  revision Store
     * @return 知识工作区 facade
     */
    public static KnowledgeWorkspace withStores(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeDocumentRevisionStore revisionStore
    ) {
        return new KnowledgeWorkspace(
                vectorStore,
                idGenerator,
                baseStore,
                documentStore,
                chunkStore,
                revisionStore
        );
    }

    /**
     * 创建知识库：分配 Snowflake ID 并记录创建时间。
     *
     * @param command 创建命令（名称、描述）
     * @return 新建知识库
     */
    public synchronized KnowledgeBase createBase(CreateKnowledgeBaseCommand command) {
        KnowledgeBase base = new KnowledgeBase(
                idGenerator.nextIdString(),
                command.name(),
                command.description(),
                true,
                System.currentTimeMillis()
        );
        return baseStore.save(base);
    }

    /**
     * 写入本地文档，使用默认摄取管线。
     *
     * @param command 文档写入命令
     * @return 已索引文档
     */
    public synchronized KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command) {
        return writeDocument(PipelineDefinition.defaultDocumentPipeline(), command, KnowledgeDocumentSource.local());
    }

    /**
     * 写入管线文档，供摄取任务调用。
     *
     * @param pipeline 摄取管线
     * @param command  摄取命令
     * @return 已索引文档
     */
    public synchronized KnowledgeDocument writeDocument(PipelineDefinition pipeline, IngestionTaskCommand command) {
        return writeDocument(pipeline, toWriteCommand(command), KnowledgeDocumentSource.local());
    }

    /**
     * 写入带来源元数据的文档，供 Feishu 导入和刷新使用。
     *
     * @param command 文档写入命令
     * @param source  外部来源元数据
     * @return 已索引文档
     */
    public synchronized KnowledgeDocument writeDocument(
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source
    ) {
        return writeDocument(PipelineDefinition.defaultDocumentPipeline(), command, source);
    }

    /**
     * 同来源、同 revision/checksum 的文档直接返回现有记录，避免重复写入。
     *
     * @param command 文档写入命令
     * @param source  外部来源元数据
     * @return 现有或新建文档
     */
    public synchronized KnowledgeDocument writeDocumentIfChanged(
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source
    ) {
        requireActiveBase(command.knowledgeBaseId());
        String checksum = checksum(command.content());
        String identity = SourceIdentityKeys.from(source);
        if (!identity.isBlank()) {
            Optional<KnowledgeDocument> existing = documentStore.listByKnowledgeBaseId(command.knowledgeBaseId()).stream()
                    .filter(KnowledgeDocument::visible)
                    .filter(document -> identity.equals(document.sourceIdentityKey())
                            || matchesLegacySource(document, source))
                    .findFirst();
            if (existing.isPresent()) {
                KnowledgeDocument current = existing.get();
                if (checksum.equals(current.checksum())) {
                    if (!source.revisionId().isBlank() && !source.revisionId().equals(current.revisionId())) {
                        KnowledgeDocument touched = current.withSyncState(
                                source.revisionId(),
                                current.checksum(),
                                current.rawPreview(),
                                source.lastSyncedAtEpochMillis() > 0L
                                        ? source.lastSyncedAtEpochMillis()
                                        : System.currentTimeMillis(),
                                source.nextRefreshAtEpochMillis()
                        );
                        return documentStore.save(touched, documentStore.rawContent(current.id()));
                    }
                    return current;
                }
                return indexDocument(
                        PipelineDefinition.defaultDocumentPipeline(),
                        command,
                        source,
                        current,
                        true
                );
            }
        }
        return writeDocument(command, source);
    }

    /** 返回全部可见知识库快照。 */
    public synchronized List<KnowledgeBase> listBases() {
        return baseStore.list().stream().filter(KnowledgeBase::visible).toList();
    }

    /** 按 ID 查询知识库，不存在或已删除抛异常。 */
    public synchronized KnowledgeBase getBase(String knowledgeBaseId) {
        return requireActiveBase(knowledgeBaseId);
    }

    /** 按 ID 查看知识库，含 DELETING/DELETED 墓碑，供内部对账。 */
    public synchronized KnowledgeBase inspectBase(String knowledgeBaseId) {
        return baseStore.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId));
    }

    /** 重命名知识库。 */
    public synchronized KnowledgeBase updateBase(String knowledgeBaseId, String name) {
        KnowledgeBase updated = requireActiveBase(knowledgeBaseId).withName(name);
        return baseStore.save(updated);
    }

    /** 知识库进入 DELETING，下属文档软删除；普通列表不再可见。 */
    public synchronized void deleteBase(String knowledgeBaseId) {
        KnowledgeBase base = requireActiveBase(knowledgeBaseId);
        long now = System.currentTimeMillis();
        documentStore.listByKnowledgeBaseId(base.id()).stream()
                .filter(KnowledgeDocument::visible)
                .map(KnowledgeDocument::id)
                .toList()
                .forEach(this::deleteDocument);
        baseStore.save(base.withDeleting(now, now + TOMBSTONE_GRACE_MILLIS));
    }

    /** 按名称/描述模糊搜索知识库，关键词为空时返回全部。 */
    public synchronized List<KnowledgeBase> searchBases(String keyword) {
        String normalizedKeyword = normalize(keyword);
        return listBases().stream()
                .filter(base -> normalizedKeyword.isBlank()
                        || normalize(base.name()).contains(normalizedKeyword)
                        || normalize(base.description()).contains(normalizedKeyword))
                .toList();
    }

    /** 统计指定知识库下的可见文档数。 */
    public synchronized long countDocuments(String knowledgeBaseId) {
        return listDocuments(knowledgeBaseId).size();
    }

    /** 按 ID 查询可见文档，不存在或已删除抛异常。 */
    public synchronized KnowledgeDocument getDocument(String documentId) {
        KnowledgeDocument document = documentStore.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found: " + documentId));
        if (!document.visible()) {
            throw new IllegalArgumentException("knowledge document not found: " + documentId);
        }
        return document;
    }

    /** 列出指定知识库下的可见文档。 */
    public synchronized List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        KnowledgeBase base = inspectBase(knowledgeBaseId);
        if (!base.visible()) {
            return List.of();
        }
        return visibleDocuments(documentStore.listByKnowledgeBaseId(knowledgeBaseId));
    }

    /** 在指定知识库范围内按关键词、状态过滤文档，任一条件为空则忽略该条件。 */
    public synchronized List<KnowledgeDocument> searchDocuments(
            String knowledgeBaseId, String keyword, KnowledgeDocumentStatus status
    ) {
        requireActiveBase(knowledgeBaseId);
        String normalizedKeyword = normalize(keyword);
        String normalizedStatus = status == null ? "" : normalize(status.name());
        return listDocuments(knowledgeBaseId).stream()
                .filter(document -> normalizedKeyword.isBlank() || matchesDocument(document, normalizedKeyword))
                .filter(document -> normalizedStatus.isBlank()
                        || normalize(document.status().name()).equals(normalizedStatus))
                .toList();
    }

    /** 列出全部可见文档。 */
    public synchronized List<KnowledgeDocument> listAllDocuments() {
        return visibleDocuments(documentStore.listAll());
    }

    /** 列出文档的规范正文 revision，按 sync_version 升序。 */
    public synchronized List<KnowledgeDocumentRevision> listRevisions(String documentId) {
        getDocument(documentId);
        return revisionStore.listByDocumentId(documentId);
    }

    /** 按 ID 查询分块，不存在抛异常。 */
    public synchronized KnowledgeChunk getChunk(String chunkId) {
        return chunkStore.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge chunk not found: " + chunkId));
    }

    /** 列出指定文档下的全部分块。 */
    public synchronized List<KnowledgeChunk> listChunks(String documentId) {
        getDocument(documentId);
        return chunkStore.listByDocumentId(documentId);
    }

    /** 列出指定文档下的分块，enabled 为 null 时不过滤启用状态。 */
    public synchronized List<KnowledgeChunk> listChunks(String documentId, Boolean enabled) {
        List<KnowledgeChunk> chunks = listChunks(documentId);
        if (enabled == null) {
            return chunks;
        }
        return chunks.stream().filter(chunk -> chunk.enabled() == enabled).toList();
    }

    /**
     * 对文档重新分块：清除既有分块与向量，以原文重新走默认管线生成新分块。
     *
     * @param documentId 文档 ID
     * @param mode       分块策略；null 时沿用文档已有知识类型对应的默认策略
     * @param chunkSize  块大小；&lt;=0 时用默认值
     * @param overlapSize 重叠大小
     * @return 重新索引后的文档
     */
    public synchronized KnowledgeDocument rechunkDocument(
            String documentId, ChunkingMode mode, int chunkSize, int overlapSize
    ) {
        KnowledgeDocument document = getDocument(documentId);
        String rawContent = documentStore.rawContent(documentId);
        KnowledgeDocumentSource source = new KnowledgeDocumentSource(
                document.sourceType(),
                document.sourceToken(),
                document.sourceUrl(),
                document.revisionId(),
                document.lastSyncedAtEpochMillis(),
                document.nextRefreshAtEpochMillis()
        );
        ChunkingMode resolvedMode = mode == null ? ChunkingMode.STRUCTURE_AWARE : mode;
        WriteKnowledgeDocumentCommand command = new WriteKnowledgeDocumentCommand(
                document.knowledgeBaseId(),
                document.sourceName(),
                document.knowledgeType(),
                document.mimeType(),
                rawContent.getBytes(StandardCharsets.UTF_8),
                resolvedMode,
                chunkSize,
                overlapSize
        );
        return indexDocument(PipelineDefinition.defaultDocumentPipeline(), command, source, document, false);
    }

    /** 列出全部可见文档的分块。 */
    public synchronized List<KnowledgeChunk> listAllChunks() {
        return chunkStore.listAll().stream()
                .filter(chunk -> documentStore.findById(chunk.documentId())
                        .filter(KnowledgeDocument::visible)
                        .isPresent())
                .toList();
    }

    /** 预览文档原始文本内容。 */
    public synchronized String previewDocument(String documentId) {
        getDocument(documentId);
        return documentStore.rawContent(documentId);
    }

    /** 查看文档的摄取节点日志。 */
    public synchronized List<IngestionNodeLog> listDocumentLogs(String documentId) {
        return getDocument(documentId).nodeLogs();
    }

    /** 在指定知识库范围内按名称/原文模糊搜索文档。 */
    public synchronized List<KnowledgeDocument> searchDocuments(String knowledgeBaseId, String keyword) {
        String normalizedKeyword = normalize(keyword);
        return listDocuments(knowledgeBaseId).stream()
                .filter(document -> matchesDocument(document, normalizedKeyword))
                .toList();
    }

    /** 切换单个分块启用/禁用状态。 */
    public synchronized KnowledgeChunk setChunkEnabled(String chunkId, boolean enabled) {
        KnowledgeChunk updated = getChunk(chunkId).withEnabled(enabled);
        return chunkStore.save(updated);
    }

    /** 切换文档启用/禁用状态。 */
    public synchronized KnowledgeDocument setDocumentEnabled(String documentId, boolean enabled) {
        KnowledgeDocument updated = getDocument(documentId).withEnabled(enabled);
        return documentStore.save(updated, documentStore.rawContent(documentId));
    }

    /** 更新文档名称与知识类型，并同步刷新其下分块与向量库。 */
    public synchronized KnowledgeDocument updateDocument(String documentId, String sourceName, String knowledgeType) {
        KnowledgeDocument document = getDocument(documentId);
        String updatedSourceName = blank(sourceName) ? document.sourceName() : sourceName.strip();
        String updatedKnowledgeType = blank(knowledgeType) ? document.knowledgeType() : knowledgeType.strip();
        KnowledgeDocument updated = document.withDocumentFields(updatedSourceName, updatedKnowledgeType);
        documentStore.save(updated, documentStore.rawContent(documentId));
        for (KnowledgeChunk chunk : chunkStore.listByDocumentId(documentId)) {
            KnowledgeChunk updatedChunk = chunk.withDocumentFields(updatedKnowledgeType, updatedSourceName);
            chunkStore.save(updatedChunk);
            vectorStore.replace(toRetrievedChunk(updatedChunk));
        }
        return updated;
    }

    /** 软删除文档：从检索中移除向量，保留墓碑行；普通列表隐藏。 */
    public synchronized void deleteDocument(String documentId) {
        KnowledgeDocument document = getDocument(documentId);
        List<String> chunkIds = chunkStore.listByDocumentId(document.id()).stream()
                .map(KnowledgeChunk::id)
                .toList();
        vectorStore.removeChunks(chunkIds);
        long now = System.currentTimeMillis();
        documentStore.save(
                document.withSoftDeleted(now, now + TOMBSTONE_GRACE_MILLIS),
                documentStore.rawContent(document.id())
        );
    }

    /** 为文档手工新增分块并立即写入向量库。 */
    public synchronized KnowledgeChunk createChunk(String documentId, String chunkId, int index, String content) {
        KnowledgeDocument document = getDocument(documentId);
        String actualChunkId = blank(chunkId) ? idGenerator.nextIdString() : chunkId.strip();
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
        chunkStore.save(chunk);
        int newChunkCount = chunkStore.listByDocumentId(document.id()).size();
        documentStore.save(
                document.withChunkCount(newChunkCount).withLocalOnlyOverride(true),
                documentStore.rawContent(document.id())
        );
        vectorStore.index(List.of(toRetrievedChunk(chunk)));
        return chunk;
    }

    /** 更新分块内容并替换向量库条目。 */
    public synchronized KnowledgeChunk updateChunk(String documentId, String chunkId, String content) {
        ensureChunkBelongsToDocument(documentId, chunkId);
        KnowledgeChunk updated = getChunk(chunkId).withContent(content == null ? "" : content).withLocalOnlyOverride();
        chunkStore.save(updated);
        vectorStore.replace(toRetrievedChunk(updated));
        KnowledgeDocument document = getDocument(documentId);
        documentStore.save(document.withLocalOnlyOverride(true), documentStore.rawContent(documentId));
        return updated;
    }

    /** 删除分块并同步文档分块计数与向量库。 */
    public synchronized boolean deleteChunk(String documentId, String chunkId) {
        ensureChunkBelongsToDocument(documentId, chunkId);
        if (chunkStore.findById(chunkId).isEmpty()) {
            return false;
        }
        chunkStore.delete(chunkId);
        vectorStore.removeChunks(List.of(chunkId));
        KnowledgeDocument document = getDocument(documentId);
        int newChunkCount = chunkStore.listByDocumentId(documentId).size();
        documentStore.save(document.withChunkCount(newChunkCount), documentStore.rawContent(documentId));
        return true;
    }

    /** 批量切换分块启用状态。chunkIds 为空时作用于该文档全部分块。 */
    public synchronized int batchSetChunksEnabled(String documentId, List<String> chunkIds, boolean enabled) {
        getDocument(documentId);
        List<String> targets = chunkIds == null || chunkIds.isEmpty()
                ? chunkStore.listByDocumentId(documentId).stream().map(KnowledgeChunk::id).toList()
                : chunkIds;
        int updatedCount = 0;
        for (String chunkId : targets) {
            ensureChunkBelongsToDocument(documentId, chunkId);
            setChunkEnabled(chunkId, enabled);
            updatedCount++;
        }
        return updatedCount;
    }

    /** 跨知识库按名称/类型/原文模糊搜索文档，带数量上限。 */
    public synchronized List<KnowledgeDocument> searchAllDocuments(String keyword, int limit) {
        String normalizedKeyword = normalize(keyword);
        int safeLimit = limit <= 0 ? 8 : limit;
        return documentStore.listAll().stream()
                .filter(KnowledgeDocument::visible)
                .filter(document -> matchesDocument(document, normalizedKeyword))
                .limit(safeLimit)
                .toList();
    }

    /** 查询到期需要刷新的文档，供调度器使用。 */
    public synchronized List<KnowledgeDocument> dueRefreshDocuments(long nowEpochMillis, int limit) {
        int safeLimit = limit <= 0 ? 20 : limit;
        return documentStore.listAll().stream()
                .filter(KnowledgeDocument::visible)
                .filter(document -> document.nextRefreshAtEpochMillis() > 0L)
                .filter(document -> document.nextRefreshAtEpochMillis() <= nowEpochMillis)
                .limit(safeLimit)
                .toList();
    }

    /** 暴露底层向量库端口，供检索引擎和摄取引擎使用。 */
    public VectorStore vectorStore() {
        return vectorStore;
    }

    private KnowledgeDocument writeDocument(
            PipelineDefinition pipeline,
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source
    ) {
        requireActiveBase(command.knowledgeBaseId());
        return indexDocument(pipeline, command, source, null, true);
    }

    private KnowledgeDocument indexDocument(
            PipelineDefinition pipeline,
            WriteKnowledgeDocumentCommand command,
            KnowledgeDocumentSource source,
            KnowledgeDocument previous,
            boolean sourceMutation
    ) {
        String documentId = previous == null ? idGenerator.nextIdString() : previous.id();
        if (previous != null) {
            List<String> oldChunkIds = chunkStore.listByDocumentId(previous.id()).stream()
                    .map(KnowledgeChunk::id)
                    .toList();
            vectorStore.removeChunks(oldChunkIds);
            chunkStore.deleteByDocumentId(previous.id());
        }
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
        List<RetrievedChunk> temporaryChunks = ingestionResult.chunks();

        ArrayList<KnowledgeChunk> persistedChunks = new ArrayList<>();
        ArrayList<RetrievedChunk> indexedChunks = new ArrayList<>();
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
            final long revisionSyncVersion = previous == null ? 1L : previous.syncVersion() + 1L;
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
        KnowledgeDocument savedDocument = documentStore.save(document, rawContent);
        if (pendingRevision != null) {
            revisionStore.save(pendingRevision);
        }
        chunkStore.saveAll(persistedChunks);
        vectorStore.index(indexedChunks);
        return savedDocument;
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

    private boolean matchesDocument(KnowledgeDocument document, String normalizedKeyword) {
        return normalizedKeyword.isBlank()
                || normalize(document.sourceName()).contains(normalizedKeyword)
                || normalize(document.knowledgeType()).contains(normalizedKeyword)
                || normalize(document.rawPreview()).contains(normalizedKeyword)
                || normalize(documentStore.rawContent(document.id())).contains(normalizedKeyword);
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

    private List<KnowledgeDocument> visibleDocuments(List<KnowledgeDocument> documents) {
        return documents.stream().filter(KnowledgeDocument::visible).toList();
    }

    private void ensureChunkBelongsToDocument(String documentId, String chunkId) {
        getDocument(documentId);
        if (blank(chunkId) || chunkStore.listByDocumentId(documentId).stream().noneMatch(chunk -> chunk.id().equals(chunkId))) {
            throw new IllegalArgumentException("knowledge chunk not found in document: " + chunkId);
        }
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

    private KnowledgeBase requireActiveBase(String knowledgeBaseId) {
        KnowledgeBase base = inspectBase(knowledgeBaseId);
        if (!base.visible()) {
            throw new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId);
        }
        return base;
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

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
