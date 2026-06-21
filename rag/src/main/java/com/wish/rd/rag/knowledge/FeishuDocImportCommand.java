package com.wish.rd.rag.knowledge;

import java.util.Objects;

/**
 * Feishu 文档导入命令。
 *
 * @param knowledgeBaseId 知识库 ID
 * @param source          docx/wiki URL 或 token
 * @param knowledgeType   知识类型
 * @param chunkSize       分块大小
 * @param overlapSize     重叠大小
 */
public record FeishuDocImportCommand(
        String knowledgeBaseId,
        String source,
        String knowledgeType,
        int chunkSize,
        int overlapSize
) {

    public FeishuDocImportCommand {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        knowledgeType = knowledgeType == null || knowledgeType.isBlank() ? "feishu-doc" : knowledgeType.strip();
        chunkSize = chunkSize <= 0 ? 512 : chunkSize;
        overlapSize = Math.max(0, overlapSize);
    }
}
