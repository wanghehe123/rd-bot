package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.ingestion.IngestionNodeLog;
import com.wish.rd.rag.ingestion.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.IngestionTaskResult;
import com.wish.rd.rag.ingestion.PipelineDefinition;
import com.wish.rd.rag.ingestion.TaskIngestionEngine;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.vector.InMemoryVectorStore;
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
import org.springframework.stereotype.Component;

/**
 * 知识库工作区 facade：统一管理知识库、文档、分块与向量索引的一致性。
 *
 * <p>对上保持原有 {@code /knowledge-base} 管理接口语义，对下委托 Store 端口完成
 * 内存或 PostgreSQL 持久化。跨实体级联操作仍收敛在此聚合根，避免外部绕过根直接
 * 修改子实体导致向量库、分块计数和文档状态不一致。
 */
@Component
public final class KnowledgeWorkspace {

    private final VectorStore vectorStore;
    private final SnowflakeIdGenerator idGenerator;
    private final KnowledgeBaseStore baseStore;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeChunkStore chunkStore;

    public KnowledgeWorkspace(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore
    ) {
        this.vectorStore = vectorStore;
        this.idGenerator = idGenerator;
        this.baseStore = baseStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
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
                new InMemoryKnowledgeChunkStore()
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
        return new KnowledgeWorkspace(vectorStore, idGenerator, baseStore, documentStore, chunkStore);
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
        String checksum = checksum(command.content());
        return documentStore.findBySource(
                        command.knowledgeBaseId(),
                        source.sourceType(),
                        source.sourceToken(),
                        source.sourceUrl()
                )
                .filter(existing -> sameRevision(existing, source, checksum))
                .orElseGet(() -> writeDocument(command, source));
    }

    /** 返回全部知识库快照。 */
    public synchronized List<KnowledgeBase> listBases() {
        return baseStore.list();
    }

    /** 按 ID 查询知识库，不存在抛异常。 */
    public synchronized KnowledgeBase getBase(String knowledgeBaseId) {
        return requireBase(knowledgeBaseId);
    }

    /** 重命名知识库。 */
    public synchronized KnowledgeBase updateBase(String knowledgeBaseId, String name) {
        KnowledgeBase updated = requireBase(knowledgeBaseId).withName(name);
        return baseStore.save(updated);
    }

    /** 删除知识库及其下属全部文档、分块和向量。 */
    public synchronized void deleteBase(String knowledgeBaseId) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        documentStore.listByKnowledgeBaseId(base.id())
                .stream()
                .map(KnowledgeDocument::id)
                .toList()
                .forEach(this::deleteDocument);
        baseStore.delete(base.id());
    }

    /** 按名称/描述模糊搜索知识库，关键词为空时返回全部。 */
    public synchronized List<KnowledgeBase> searchBases(String keyword) {
        String normalizedKeyword = normalize(keyword);
        return baseStore.list().stream()
                .filter(base -> normalizedKeyword.isBlank()
                        || normalize(base.name()).contains(normalizedKeyword)
                        || normalize(base.description()).contains(normalizedKeyword))
                .toList();
    }

    /** 统计指定知识库下的文档数。 */
    public synchronized long countDocuments(String knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return documentStore.listByKnowledgeBaseId(knowledgeBaseId).size();
    }

    /** 按 ID 查询文档，不存在抛异常。 */
    public synchronized KnowledgeDocument getDocument(String documentId) {
        return documentStore.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found: " + documentId));
    }

    /** 列出指定知识库下的文档。 */
    public synchronized List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return documentStore.listByKnowledgeBaseId(knowledgeBaseId);
    }

    /** 列出全部文档。 */
    public synchronized List<KnowledgeDocument> listAllDocuments() {
        return documentStore.listAll();
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

    /** 列出全部分块。 */
    public synchronized List<KnowledgeChunk> listAllChunks() {
        return chunkStore.listAll();
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

    /** 删除文档及其全部分块、原文与向量库条目。 */
    public synchronized void deleteDocument(String documentId) {
        KnowledgeDocument document = getDocument(documentId);
        List<String> chunkIds = chunkStore.listByDocumentId(document.id()).stream()
                .map(KnowledgeChunk::id)
                .toList();
        vectorStore.removeChunks(chunkIds);
        chunkStore.deleteByDocumentId(document.id());
        documentStore.delete(document.id());
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
                        "manual", "true"
                )
        );
        chunkStore.save(chunk);
        int newChunkCount = chunkStore.listByDocumentId(document.id()).size();
        documentStore.save(document.withChunkCount(newChunkCount), documentStore.rawContent(document.id()));
        vectorStore.index(List.of(toRetrievedChunk(chunk)));
        return chunk;
    }

    /** 更新分块内容并替换向量库条目。 */
    public synchronized KnowledgeChunk updateChunk(String documentId, String chunkId, String content) {
        ensureChunkBelongsToDocument(documentId, chunkId);
        KnowledgeChunk updated = getChunk(chunkId).withContent(content == null ? "" : content);
        chunkStore.save(updated);
        vectorStore.replace(toRetrievedChunk(updated));
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
                .filter(document -> matchesDocument(document, normalizedKeyword))
                .limit(safeLimit)
                .toList();
    }

    /** 查询到期需要刷新的文档，供调度器使用。 */
    public synchronized List<KnowledgeDocument> dueRefreshDocuments(long nowEpochMillis, int limit) {
        int safeLimit = limit <= 0 ? 20 : limit;
        return documentStore.listAll().stream()
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
        requireBase(command.knowledgeBaseId());
        String documentId = idGenerator.nextIdString();
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
        KnowledgeDocument document = new KnowledgeDocument(
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
                checksum(command.content()),
                preview(rawContent),
                source.lastSyncedAtEpochMillis() > 0L ? source.lastSyncedAtEpochMillis() : now,
                source.nextRefreshAtEpochMillis()
        );
        KnowledgeDocument savedDocument = documentStore.save(document, rawContent);
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

    private boolean sameRevision(KnowledgeDocument existing, KnowledgeDocumentSource source, String checksum) {
        boolean revisionMatches = !source.revisionId().isBlank() && source.revisionId().equals(existing.revisionId());
        boolean checksumMatches = !checksum.isBlank() && checksum.equals(existing.checksum());
        return revisionMatches || checksumMatches;
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

    private KnowledgeBase requireBase(String knowledgeBaseId) {
        return baseStore.findById(knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId));
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
