package com.wish.rd.rag.ingestion.impl;

import com.wish.rd.rag.ingestion.IngestionPipeline;

import com.wish.rd.framework.convention.model.RetrievedChunk;
import com.wish.rd.rag.vector.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wish.rd.rag.ingestion.model.DocumentSource;

/**
 * 简易摄取管线：把字节内容直接按段落 + 长度切分为分块并写入向量库。
 *
 * <p>这是最朴素的摄取实现（不经过解析器选择/分块策略工厂），主要用于
 * {@link com.wish.rd.rag.runtime.RagRuntimeFactory} 在测试通道中快速种入示例文档。
 * 切分策略：先按空行分段，每段超过 {@link #MAX_CHUNK_LENGTH} 再按定长截断。
 */
public final class SimpleIngestionPipeline implements IngestionPipeline {

    /** 单个分块的最大字符数，超过则继续截断。 */
    private static final int MAX_CHUNK_LENGTH = 800;

    private final VectorStore vectorStore;

    public SimpleIngestionPipeline(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 把文档源内容切分为带索引的分块并写入向量库。
     *
     * @param source 文档来源（含字节内容、知识库 ID、类型、来源名）
     */
    @Override
    public void ingest(DocumentSource source) {
        String parsedText = new String(source.content(), StandardCharsets.UTF_8);
        List<RetrievedChunk> chunks = chunk(parsedText).stream()
                .map(chunk -> new RetrievedChunk(
                        source.sourceName() + "#" + chunk.index(),
                        chunk.content(),
                        source.knowledgeBaseId(),
                        source.knowledgeType(),
                        source.sourceName(),
                        0.0d,
                        Map.of("chunkIndex", Integer.toString(chunk.index()))
                ))
                .toList();
        vectorStore.index(chunks);
    }

    /**
     * 把文本切分为分块：先按空行分段，再对超长段落按 {@link #MAX_CHUNK_LENGTH} 截断。
     *
     * @param parsedText 已解析的纯文本
     * @return 带序号的分块列表
     */
    private List<TextChunk> chunk(String parsedText) {
        String text = parsedText == null ? "" : parsedText.strip();
        if (text.isBlank()) {
            return List.of();
        }
        ArrayList<TextChunk> chunks = new ArrayList<>();
        int index = 0;
        // 按空行分段（段落是语义相对完整的单元）
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String remaining = paragraph.strip();
            // 超长段落继续按定长切，避免单块过大
            while (remaining.length() > MAX_CHUNK_LENGTH) {
                chunks.add(new TextChunk(index++, remaining.substring(0, MAX_CHUNK_LENGTH)));
                remaining = remaining.substring(MAX_CHUNK_LENGTH).strip();
            }
            if (!remaining.isBlank()) {
                chunks.add(new TextChunk(index++, remaining));
            }
        }
        return List.copyOf(chunks);
    }

    /** 内部分块表示：序号 + 内容。 */
    private record TextChunk(int index, String content) {
    }
}
