package com.wish.rd.framework.convention.model;

import java.util.Map;
import java.util.Objects;

/**
 * 检索证据块的统一约定：向量库、各检索通道、摄取索引都以该记录表示一个证据单元。
 *
 * <p>跨层流通的核心数据结构，同时承载内容、归属（知识库/类型/来源）与打分元信息。
 *
 * @param chunkId         分块唯一 ID（必填）
 * @param content         分块文本内容（必填）
 * @param knowledgeBaseId 所属知识库 ID（必填）
 * @param knowledgeType   知识类型，如 api、runtime-log、code-snippet（必填，决定 Prompt 分段）
 * @param sourceName      来源名（文件名/仓库名等，必填，参与检索打分）
 * @param score           检索得分，由检索阶段填充
 * @param metadata        附加元数据（chunkIndex、documentId 等）
 */
public record RetrievedChunk(
        String chunkId,
        String content,
        String knowledgeBaseId,
        String knowledgeType,
        String sourceName,
        double score,
        Map<String, String> metadata
) {

    public RetrievedChunk {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        Objects.requireNonNull(sourceName, "sourceName must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(chunkId, content, knowledgeBaseId, knowledgeType, sourceName, newScore, metadata);
    }
}
