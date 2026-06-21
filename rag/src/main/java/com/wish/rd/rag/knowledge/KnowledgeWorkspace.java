package com.wish.rd.rag.knowledge;

import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.ingestion.IngestionNodeLog;
import com.wish.rd.rag.ingestion.IngestionTaskCommand;
import com.wish.rd.rag.ingestion.IngestionTaskResult;
import com.wish.rd.rag.ingestion.PipelineDefinition;
import com.wish.rd.rag.ingestion.TaskIngestionEngine;
import com.wish.rd.rag.vector.InMemoryVectorStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库工作区：知识域的内存聚合根，统一管理知识库、文档、分块与向量存储。
 *
 * <p>这是 /knowledge-base 系列管理接口、摄取任务、以及 /rag/v3/chat 检索的共同数据后端。
 * 所有对外写操作均加 {@code synchronized} 保证并发安全（MVP 单机内存模型，无持久化）。
 *
 * <p>核心数据结构：
 * <ul>
 *   <li>{@link InMemoryVectorStore}：实际承载检索能力，存储 {@link RetrievedChunk}；</li>
 *   <li>{@code bases/documents/chunks}：业务实体的有序存储；</li>
 *   <li>{@code documentChunkIds}：文档→分块 ID 列表的映射；</li>
 *   <li>{@code documentRawContent}：文档原始文本，供预览与搜索。</li>
 * </ul>
 *
 * <p>写文档流程（{@link #writeDocument(PipelineDefinition, IngestionTaskCommand)}）：
 * 调用 {@link TaskIngestionEngine} 走"解析→分块→索引"管线，把结果固化为
 * {@link KnowledgeDocument} + 多个 {@link KnowledgeChunk}，并写入向量库。
 */
public final class KnowledgeWorkspace {

    private final InMemoryVectorStore vectorStore;
    /** 知识库集合，按创建顺序排列。 */
    private final LinkedHashMap<String, KnowledgeBase> bases = new LinkedHashMap<>();
    /** 文档集合。 */
    private final LinkedHashMap<String, KnowledgeDocument> documents = new LinkedHashMap<>();
    /** 分块集合。 */
    private final LinkedHashMap<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
    /** 文档→其分块 ID 列表的映射，维护文档与分块的从属关系。 */
    private final LinkedHashMap<String, List<String>> documentChunkIds = new LinkedHashMap<>();
    /** 文档→原始文本内容的映射，供预览与全文搜索。 */
    private final LinkedHashMap<String, String> documentRawContent = new LinkedHashMap<>();
    /** 知识库自增序列，生成 kb-N 形式的 ID。 */
    private long baseSequence;
    /** 文档自增序列，生成 doc-N 形式的 ID。 */
    private long documentSequence;

    private KnowledgeWorkspace(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 创建一个空工作区，内置一个新的内存向量库。 */
    public static KnowledgeWorkspace inMemory() {
        return new KnowledgeWorkspace(new InMemoryVectorStore());
    }

    /**
     * 创建知识库：分配 kb-N 的 ID 并记录创建时间。
     *
     * @param command 创建命令（名称、描述）
     * @return 新建的知识库对象
     */
    public synchronized KnowledgeBase createBase(CreateKnowledgeBaseCommand command) {
        String id = "kb-" + ++baseSequence;
        KnowledgeBase base = new KnowledgeBase(
                id,
                command.name(),
                command.description(),
                true,
                System.currentTimeMillis()
        );
        bases.put(id, base);
        return base;
    }

    /**
     * 写入文档（使用默认管线 + 行内命令）的便捷重载。
     * 把 {@link WriteKnowledgeDocumentCommand} 转换为 {@link IngestionTaskCommand} 后委托给完整流程。
     */
    public synchronized KnowledgeDocument writeDocument(WriteKnowledgeDocumentCommand command) {
        return writeDocument(
                PipelineDefinition.defaultDocumentPipeline(),
                new IngestionTaskCommand(
                        "task-inline",
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
    }

    /**
     * 写入文档的完整流程：执行摄取管线生成分块，再逐一固化为知识分块并写入向量库。
     *
     * <p>步骤：
     * <ol>
     *   <li>校验目标知识库存在，分配 doc-N 文档 ID；</li>
     *   <li>用 {@link TaskIngestionEngine} 跑管线，得到 {@link RetrievedChunk} 列表与节点日志；</li>
     *   <li>清除同名旧分块后，按顺序为每个分块分配 chunkId 并写入 {@link KnowledgeChunk}；</li>
     *   <li>把分块索引进向量库，记录文档→分块映射与原文；</li>
     *   <li>组装并存储 {@link KnowledgeDocument}（含分块数与节点日志）。</li>
     * </ol>
     *
     * @param pipeline 摄取管线定义（节点链）
     * @param command  摄取任务命令
     * @return 新建的知识文档
     */
    public synchronized KnowledgeDocument writeDocument(PipelineDefinition pipeline, IngestionTaskCommand command) {
        requireBase(command.knowledgeBaseId());
        String documentId = "doc-" + ++documentSequence;
        IngestionTaskResult ingestionResult = TaskIngestionEngine.inMemory(vectorStore).execute(pipeline, command);

        List<String> chunkIds = new ArrayList<>();
        List<RetrievedChunk> retrievedChunks = ingestionResult.chunks();
        // 先清除管线可能已索引的临时分块，统一由工作区重新分配 chunkId
        vectorStore.removeChunks(retrievedChunks.stream()
                .map(RetrievedChunk::chunkId)
                .toList());
        List<RetrievedChunk> indexedChunks = new ArrayList<>();
        for (int i = 0; i < retrievedChunks.size(); i++) {
            RetrievedChunk retrievedChunk = retrievedChunks.get(i);
            int chunkIndex = chunkIndex(retrievedChunk, i);
            String chunkId = documentId + "-" + chunkIndex;
            KnowledgeChunk chunk = new KnowledgeChunk(
                    chunkId,
                    documentId,
                    command.knowledgeBaseId(),
                    chunkIndex,
                    retrievedChunk.content(),
                    retrievedChunk.knowledgeType(),
                    retrievedChunk.sourceName(),
                    true,
                    withDocumentMetadata(retrievedChunk.metadata(), documentId)
            );
            chunks.put(chunkId, chunk);
            chunkIds.add(chunkId);
            indexedChunks.add(toRetrievedChunk(chunk));
        }
        vectorStore.index(indexedChunks);
        documentChunkIds.put(documentId, List.copyOf(chunkIds));
        documentRawContent.put(documentId, new String(command.content(), StandardCharsets.UTF_8));

        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                command.knowledgeBaseId(),
                command.sourceName(),
                command.knowledgeType(),
                command.mimeType(),
                KnowledgeDocumentStatus.INDEXED,
                true,
                chunkIds.size(),
                ingestionResult.nodeLogs(),
                System.currentTimeMillis()
        );
        documents.put(documentId, document);
        return document;
    }

    /** 返回全部知识库的快照。 */
    public synchronized List<KnowledgeBase> listBases() {
        return List.copyOf(bases.values());
    }

    /** 按 ID 查询知识库，不存在抛异常。 */
    public synchronized KnowledgeBase getBase(String knowledgeBaseId) {
        return requireBase(knowledgeBaseId);
    }

    /** 重命名知识库。 */
    public synchronized KnowledgeBase updateBase(String knowledgeBaseId, String name) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        KnowledgeBase updated = base.withName(name);
        bases.put(knowledgeBaseId, updated);
        return updated;
    }

    /**
     * 删除知识库及其下属全部文档（级联清理分块、原文与向量）。
     */
    public synchronized void deleteBase(String knowledgeBaseId) {
        KnowledgeBase base = requireBase(knowledgeBaseId);
        List<String> documentIds = documents.values().stream()
                .filter(document -> document.knowledgeBaseId().equals(base.id()))
                .map(KnowledgeDocument::id)
                .toList();
        documentIds.forEach(this::deleteDocument);
        bases.remove(base.id());
    }

    /** 按名称/描述模糊搜索知识库，关键词为空时返回全部。 */
    public synchronized List<KnowledgeBase> searchBases(String keyword) {
        String normalizedKeyword = normalize(keyword);
        return bases.values().stream()
                .filter(base -> normalizedKeyword.isBlank()
                        || normalize(base.name()).contains(normalizedKeyword)
                        || normalize(base.description()).contains(normalizedKeyword))
                .toList();
    }

    /** 统计指定知识库下的文档数。 */
    public synchronized long countDocuments(String knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return documents.values().stream()
                .filter(document -> document.knowledgeBaseId().equals(knowledgeBaseId))
                .count();
    }

    /** 按 ID 查询文档，不存在抛异常。 */
    public synchronized KnowledgeDocument getDocument(String documentId) {
        KnowledgeDocument document = documents.get(documentId);
        if (document == null) {
            throw new IllegalArgumentException("knowledge document not found: " + documentId);
        }
        return document;
    }

    /** 列出指定知识库下的全部文档。 */
    public synchronized List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        requireBase(knowledgeBaseId);
        return documents.values().stream()
                .filter(document -> document.knowledgeBaseId().equals(knowledgeBaseId))
                .toList();
    }

    /** 列出全部文档（跨知识库），供全局检索/概览使用。 */
    public synchronized List<KnowledgeDocument> listAllDocuments() {
        return List.copyOf(documents.values());
    }

    /** 按 ID 查询分块，不存在抛异常。 */
    public synchronized KnowledgeChunk getChunk(String chunkId) {
        KnowledgeChunk chunk = chunks.get(chunkId);
        if (chunk == null) {
            throw new IllegalArgumentException("knowledge chunk not found: " + chunkId);
        }
        return chunk;
    }

    /** 列出指定文档下的全部分块（按文档→分块映射）。 */
    public synchronized List<KnowledgeChunk> listChunks(String documentId) {
        getDocument(documentId);
        return documentChunkIds.getOrDefault(documentId, List.of()).stream()
                .map(chunks::get)
                .filter(chunk -> chunk != null)
                .toList();
    }

    /** 列出全部分块（跨文档），供概览/统计使用。 */
    public synchronized List<KnowledgeChunk> listAllChunks() {
        return List.copyOf(chunks.values());
    }

    /** 预览文档原始文本内容。 */
    public synchronized String previewDocument(String documentId) {
        getDocument(documentId);
        return documentRawContent.getOrDefault(documentId, "");
    }

    /** 查看文档的摄取节点日志（解析/分块/索引各步骤记录）。 */
    public synchronized List<IngestionNodeLog> listDocumentLogs(String documentId) {
        return getDocument(documentId).nodeLogs();
    }

    /** 在指定知识库范围内按名称/原文模糊搜索文档。 */
    public synchronized List<KnowledgeDocument> searchDocuments(String knowledgeBaseId, String keyword) {
        String normalizedKeyword = normalize(keyword);
        return listDocuments(knowledgeBaseId).stream()
                .filter(document -> normalizedKeyword.isBlank()
                        || normalize(document.sourceName()).contains(normalizedKeyword)
                        || normalize(previewDocument(document.id())).contains(normalizedKeyword))
                .toList();
    }

    /** 切换单个分块的启用/禁用状态。 */
    public synchronized KnowledgeChunk setChunkEnabled(String chunkId, boolean enabled) {
        KnowledgeChunk chunk = chunks.get(chunkId);
        if (chunk == null) {
            throw new IllegalArgumentException("knowledge chunk not found: " + chunkId);
        }
        KnowledgeChunk updated = chunk.withEnabled(enabled);
        chunks.put(chunkId, updated);
        return updated;
    }

    /** 切换文档的启用/禁用状态。 */
    public synchronized KnowledgeDocument setDocumentEnabled(String documentId, boolean enabled) {
        KnowledgeDocument document = getDocument(documentId);
        KnowledgeDocument updated = document.withEnabled(enabled);
        documents.put(documentId, updated);
        return updated;
    }

    /**
     * 更新文档的名称与知识类型，并同步刷新其下所有分块的对应字段与向量库条目，
     * 保证检索时打分所用的文本（含类型/来源）保持一致。
     */
    public synchronized KnowledgeDocument updateDocument(String documentId, String sourceName, String knowledgeType) {
        KnowledgeDocument document = getDocument(documentId);
        String updatedSourceName = blank(sourceName) ? document.sourceName() : sourceName.strip();
        String updatedKnowledgeType = blank(knowledgeType) ? document.knowledgeType() : knowledgeType.strip();
        KnowledgeDocument updated = document.withDocumentFields(updatedSourceName, updatedKnowledgeType);
        documents.put(documentId, updated);
        // 同步更新该文档下每个分块的类型/来源，并替换向量库中的对应条目
        for (String chunkId : documentChunkIds.getOrDefault(documentId, List.of())) {
            KnowledgeChunk chunk = chunks.get(chunkId);
            if (chunk != null) {
                KnowledgeChunk updatedChunk = chunk.withDocumentFields(updatedKnowledgeType, updatedSourceName);
                chunks.put(chunkId, updatedChunk);
                vectorStore.replace(toRetrievedChunk(updatedChunk));
            }
        }
        return updated;
    }

    /**
     * 删除文档及其全部分块、原文与向量库条目。
     */
    public synchronized void deleteDocument(String documentId) {
        KnowledgeDocument document = getDocument(documentId);
        List<String> chunkIds = documentChunkIds.getOrDefault(document.id(), List.of());
        vectorStore.removeChunks(chunkIds);
        chunkIds.forEach(chunks::remove);
        documentChunkIds.remove(document.id());
        documentRawContent.remove(document.id());
        documents.remove(document.id());
    }

    /**
     * 为文档手工新增一个分块（不经过摄取管线），分配 chunkId 并立即写入向量库。
     * chunkId 已存在则抛异常。
     */
    public synchronized KnowledgeChunk createChunk(String documentId, String chunkId, int index, String content) {
        KnowledgeDocument document = getDocument(documentId);
        String actualChunkId = blank(chunkId) ? documentId + "-manual-" + Math.max(0, index) : chunkId.strip();
        if (chunks.containsKey(actualChunkId)) {
            throw new IllegalArgumentException("knowledge chunk already exists: " + actualChunkId);
        }
        KnowledgeChunk chunk = new KnowledgeChunk(
                actualChunkId,
                document.id(),
                document.knowledgeBaseId(),
                Math.max(0, index),
                content == null ? "" : content,
                document.knowledgeType(),
                document.sourceName(),
                true,
                Map.of(
                        "documentId", document.id(),
                        "chunkIndex", String.valueOf(Math.max(0, index)),
                        "manual", "true"
                )
        );
        chunks.put(chunk.id(), chunk);
        // 维护文档→分块映射并更新文档的分块计数
        List<String> updatedChunkIds = new ArrayList<>(documentChunkIds.getOrDefault(document.id(), List.of()));
        updatedChunkIds.add(chunk.id());
        documentChunkIds.put(document.id(), List.copyOf(updatedChunkIds));
        documents.put(document.id(), document.withChunkCount(updatedChunkIds.size()));
        vectorStore.index(List.of(toRetrievedChunk(chunk)));
        return chunk;
    }

    /** 更新分块内容并替换向量库中的对应条目（保证检索文本同步）。 */
    public synchronized KnowledgeChunk updateChunk(String documentId, String chunkId, String content) {
        ensureChunkBelongsToDocument(documentId, chunkId);
        KnowledgeChunk chunk = getChunk(chunkId);
        KnowledgeChunk updated = chunk.withContent(content == null ? "" : content);
        chunks.put(chunkId, updated);
        vectorStore.replace(toRetrievedChunk(updated));
        return updated;
    }

    /**
     * 删除分块：校验归属后从存储、文档映射与向量库中一并移除，并更新文档分块计数。
     *
     * @return 是否真的删除了（不存在时返回 false）
     */
    public synchronized boolean deleteChunk(String documentId, String chunkId) {
        ensureChunkBelongsToDocument(documentId, chunkId);
        KnowledgeChunk removed = chunks.remove(chunkId);
        if (removed == null) {
            return false;
        }
        List<String> updatedChunkIds = new ArrayList<>(documentChunkIds.getOrDefault(documentId, List.of()));
        updatedChunkIds.remove(chunkId);
        documentChunkIds.put(documentId, List.copyOf(updatedChunkIds));
        documents.put(documentId, getDocument(documentId).withChunkCount(updatedChunkIds.size()));
        vectorStore.removeChunks(List.of(chunkId));
        return true;
    }

    /**
     * 批量切换分块启用状态。chunkIds 为空时作用于该文档的全部分块。
     *
     * @return 实际更新的分块数量
     */
    public synchronized int batchSetChunksEnabled(String documentId, List<String> chunkIds, boolean enabled) {
        getDocument(documentId);
        List<String> targets = chunkIds == null || chunkIds.isEmpty()
                ? documentChunkIds.getOrDefault(documentId, List.of())
                : chunkIds;
        int updatedCount = 0;
        for (String chunkId : targets) {
            ensureChunkBelongsToDocument(documentId, chunkId);
            setChunkEnabled(chunkId, enabled);
            updatedCount++;
        }
        return updatedCount;
    }

    /** 跨知识库按名称/类型/原文模糊搜索文档，带数量上限（默认 8）。 */
    public synchronized List<KnowledgeDocument> searchAllDocuments(String keyword, int limit) {
        String normalizedKeyword = normalize(keyword);
        int safeLimit = limit <= 0 ? 8 : limit;
        return documents.values().stream()
                .filter(document -> normalizedKeyword.isBlank()
                        || normalize(document.sourceName()).contains(normalizedKeyword)
                        || normalize(document.knowledgeType()).contains(normalizedKeyword)
                        || normalize(previewDocument(document.id())).contains(normalizedKeyword))
                .limit(safeLimit)
                .toList();
    }

    /** 暴露底层向量库，供检索引擎与摄取引擎直接使用。 */
    public InMemoryVectorStore vectorStore() {
        return vectorStore;
    }

    /** 校验分块归属于指定文档，不存在或归属不符则抛异常。 */
    private void ensureChunkBelongsToDocument(String documentId, String chunkId) {
        getDocument(documentId);
        if (blank(chunkId) || !documentChunkIds.getOrDefault(documentId, List.of()).contains(chunkId)) {
            throw new IllegalArgumentException("knowledge chunk not found in document: " + chunkId);
        }
    }

    /** 把知识分块转为可检索的 {@link RetrievedChunk}（打分统一占位 1.0，实际打分由检索阶段计算）。 */
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

    /** 校验知识库存在，不存在抛异常。 */
    private KnowledgeBase requireBase(String knowledgeBaseId) {
        KnowledgeBase base = bases.get(knowledgeBaseId);
        if (base == null) {
            throw new IllegalArgumentException("knowledge base not found: " + knowledgeBaseId);
        }
        return base;
    }

    /** 从分块元数据读取 chunkIndex，缺失或非法时回退为序号。 */
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

    /** 复制元数据并补入 documentId，便于检索结果回溯到文档。 */
    private Map<String, String> withDocumentMetadata(Map<String, String> metadata, String documentId) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>(metadata);
        copied.put("documentId", documentId);
        return copied;
    }

    /** 关键词归一化：转小写，null 视为空串。 */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
