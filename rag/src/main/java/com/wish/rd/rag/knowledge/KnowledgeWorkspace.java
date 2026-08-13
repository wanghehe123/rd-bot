package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.core.chunk.model.ChunkingMode;
import com.wish.rd.rag.ingestion.model.IngestionNodeLog;
import com.wish.rd.rag.ingestion.model.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.model.PipelineDefinition;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.store.KnowledgeBaseStore;
import com.wish.rd.rag.knowledge.store.KnowledgeChunkStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentRevisionStore;
import com.wish.rd.rag.knowledge.store.KnowledgeDocumentStore;
import com.wish.rd.rag.knowledge.projection.KnowledgeProjectionWakePort;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeMutationTransactionAdapter;
import com.wish.rd.rag.vector.impl.InMemoryVectorStore;
import com.wish.rd.rag.vector.VectorStore;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
 * 知识库工作区 facade：查询知识库、文档、分块，并把写入委托给
 * {@link KnowledgeDocumentMutationPort}。
 *
 * <p>对上保持原有 {@code /knowledge-base} 管理接口语义。跨实体一致性写入由
 * {@link KnowledgeDocumentMutationEngine} 组 bundle，经事务端口提交。
 */
@Component
public final class KnowledgeWorkspace {

    private final VectorStore vectorStore;
    private final SnowflakeIdGenerator idGenerator;
    private final KnowledgeBaseStore baseStore;
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentRevisionStore revisionStore;
    private final KnowledgeDocumentMutationPort mutations;

    public KnowledgeWorkspace(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeDocumentMutationPort mutations
    ) {
        this.vectorStore = vectorStore;
        this.idGenerator = idGenerator;
        this.baseStore = baseStore;
        this.documentStore = documentStore;
        this.chunkStore = chunkStore;
        this.revisionStore = revisionStore;
        this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
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
        InMemoryKnowledgeExternalIndexBindingStore bindings = new InMemoryKnowledgeExternalIndexBindingStore();
        InMemoryKnowledgeExternalIndexOutboxStore outbox = new InMemoryKnowledgeExternalIndexOutboxStore();
        KnowledgeDocumentMutationEngine engine = new KnowledgeDocumentMutationEngine(
                idGenerator,
                baseStore,
                documentStore,
                revisionStore,
                chunkStore,
                new InMemoryKnowledgeMutationTransactionAdapter(
                        documentStore, revisionStore, chunkStore, vectorStore, bindings, outbox, baseStore),
                KnowledgeProjectionWakePort.noop()
        );
        return withStores(vectorStore, idGenerator, baseStore, documentStore, chunkStore, revisionStore, engine);
    }

    /**
     * 用指定 Store 与 mutation 端口组装工作区。
     */
    public static KnowledgeWorkspace withStores(
            VectorStore vectorStore,
            SnowflakeIdGenerator idGenerator,
            KnowledgeBaseStore baseStore,
            KnowledgeDocumentStore documentStore,
            KnowledgeChunkStore chunkStore,
            KnowledgeDocumentRevisionStore revisionStore,
            KnowledgeDocumentMutationPort mutations
    ) {
        return new KnowledgeWorkspace(
                vectorStore,
                idGenerator,
                baseStore,
                documentStore,
                chunkStore,
                revisionStore,
                mutations
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
        return mutations.writeDocument(command);
    }

    /**
     * 写入管线文档，供摄取任务调用。
     *
     * @param pipeline 摄取管线
     * @param command  摄取命令
     * @return 已索引文档
     */
    public synchronized KnowledgeDocument writeDocument(PipelineDefinition pipeline, IngestionTaskCommand command) {
        return mutations.writeDocument(pipeline, command);
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
        return mutations.writeDocument(command, source);
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
        return mutations.writeDocumentIfChanged(command, source);
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
        mutations.deleteBase(knowledgeBaseId);
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
        return mutations.rechunkDocument(documentId, mode, chunkSize, overlapSize);
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
        return mutations.setChunkEnabled(chunkId, enabled);
    }

    /** 切换文档启用/禁用状态。 */
    public synchronized KnowledgeDocument setDocumentEnabled(String documentId, boolean enabled) {
        return mutations.setDocumentEnabled(documentId, enabled);
    }

    /** 更新文档名称与知识类型，并同步刷新其下分块与向量库。 */
    public synchronized KnowledgeDocument updateDocument(String documentId, String sourceName, String knowledgeType) {
        return mutations.updateDocument(documentId, sourceName, knowledgeType);
    }

    /** 软删除文档：从检索中移除向量，保留墓碑行；普通列表隐藏。 */
    public synchronized void deleteDocument(String documentId) {
        mutations.deleteDocument(documentId);
    }

    /** 为文档手工新增分块并立即写入向量库。 */
    public synchronized KnowledgeChunk createChunk(String documentId, String chunkId, int index, String content) {
        return mutations.createChunk(documentId, chunkId, index, content);
    }

    /** 更新分块内容并替换向量库条目。 */
    public synchronized KnowledgeChunk updateChunk(String documentId, String chunkId, String content) {
        return mutations.updateChunk(documentId, chunkId, content);
    }

    /** 删除分块并同步文档分块计数与向量库。 */
    public synchronized boolean deleteChunk(String documentId, String chunkId) {
        return mutations.deleteChunk(documentId, chunkId);
    }

    /** 批量切换分块启用状态。chunkIds 为空时作用于该文档全部分块。 */
    public synchronized int batchSetChunksEnabled(String documentId, List<String> chunkIds, boolean enabled) {
        return mutations.batchSetChunksEnabled(documentId, chunkIds, enabled);
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

    public KnowledgeDocumentMutationPort mutations() {
        return mutations;
    }

    private boolean matchesDocument(KnowledgeDocument document, String normalizedKeyword) {
        return normalizedKeyword.isBlank()
                || normalize(document.sourceName()).contains(normalizedKeyword)
                || normalize(document.knowledgeType()).contains(normalizedKeyword)
                || normalize(document.rawPreview()).contains(normalizedKeyword)
                || normalize(documentStore.rawContent(document.id())).contains(normalizedKeyword);
    }

    private List<KnowledgeDocument> visibleDocuments(List<KnowledgeDocument> documents) {
        return documents.stream().filter(KnowledgeDocument::visible).toList();
    }

    private KnowledgeBase requireActiveBase(String knowledgeBaseId) {
        KnowledgeBase base = inspectBase(knowledgeBaseId);
        if (!base.visible()) {
            throw new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId);
        }
        return base;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
