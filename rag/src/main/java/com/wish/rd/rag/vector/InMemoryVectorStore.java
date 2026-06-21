package com.wish.rd.rag.vector;

import com.wish.rd.framework.convention.RetrievedChunk;
import com.wish.rd.rag.text.TextAnalyzer;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存向量库：MVP 阶段的检索后端，用 {@link CopyOnWriteArrayList} 存储全部分块。
 *
 * <p>注意：当前未引入真实 Embedding，向量检索与关键词检索都退化为
 * {@link TextAnalyzer#overlapScore} 的"词项重叠打分"。把分块的
 * 来源名+类型+内容拼成可搜索文本后计算与查询的重叠度，按分数降序取 topK。
 *
 * <p>写操作（index/replace/removeChunks）线程安全；读操作返回不可变快照。
 * 该实现仅适用于本地开发与单测，不适用于生产规模。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryVectorStore implements VectorStore {

    /** 全部分块，写时复制保证遍历安全。 */
    private final CopyOnWriteArrayList<RetrievedChunk> chunks = new CopyOnWriteArrayList<>();

    /** 批量写入新分块。 */
    @Override
    public void index(Collection<RetrievedChunk> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        chunks.addAll(newChunks);
    }

    /**
     * 替换单个分块：先按 ID 删除再追加新版本，用于内容更新后同步检索文本。
     */
    @Override
    public void replace(RetrievedChunk chunk) {
        if (chunk == null) {
            return;
        }
        removeChunks(List.of(chunk.chunkId()));
        chunks.add(chunk);
    }

    /** 按 ID 批量删除分块。 */
    @Override
    public void removeChunks(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        Set<String> targetIds = Set.copyOf(chunkIds);
        chunks.removeIf(chunk -> targetIds.contains(chunk.chunkId()));
    }

    /**
     * 向量（语义）检索：在指定知识库范围内按重叠分降序取 topK。
     *
     * @param query           查询文本
     * @param knowledgeBaseIds 知识库范围，为空表示全库
     * @param topK            返回上限
     */
    @Override
    public List<RetrievedChunk> vectorSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
        return search(query, knowledgeBaseIds, topK, 0.0d);
    }

    /**
     * 关键词检索：与向量检索共用同一打分实现，差异在于调用语义（精确命中 vs 语义相似）。
     */
    @Override
    public List<RetrievedChunk> keywordSearch(String query, Collection<String> knowledgeBaseIds, int topK) {
        return search(query, knowledgeBaseIds, topK, 0.0d);
    }

    /** 返回全部分块的不可变快照。 */
    @Override
    public List<RetrievedChunk> allChunks() {
        return List.copyOf(chunks);
    }

    /**
     * 统一的打分检索：过滤知识库范围 → 计算重叠分 → 过滤零分 → 降序取 topK。
     *
     * @param minimumScore 最低分阈值，>0 可用于剔除弱相关结果
     */
    private List<RetrievedChunk> search(
            String query,
            Collection<String> knowledgeBaseIds,
            int topK,
            double minimumScore
    ) {
        Set<String> targetKnowledgeBases = knowledgeBaseIds == null ? Set.of() : Set.copyOf(knowledgeBaseIds);
        int limit = topK <= 0 ? 10 : topK;
        return chunks.stream()
                // 知识库范围为空时不过滤，否则限定在指定范围
                .filter(chunk -> targetKnowledgeBases.isEmpty() || targetKnowledgeBases.contains(chunk.knowledgeBaseId()))
                // 用查询与可搜索文本的重叠分作为该分块的得分
                .map(chunk -> chunk.withScore(TextAnalyzer.overlapScore(query, searchableText(chunk))))
                .filter(chunk -> chunk.score() > minimumScore)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(limit)
                .toList();
    }

    /** 拼接分块的可搜索文本：来源名 + 类型 + 内容，三者都参与打分。 */
    private String searchableText(RetrievedChunk chunk) {
        return String.join("\n", chunk.sourceName(), chunk.knowledgeType(), chunk.content());
    }
}
