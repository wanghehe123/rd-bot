package com.wish.rd.rag.knowledge;

/**
 * 知识文档来源元数据：记录本地写入、Feishu 导入等来源的可刷新标识。
 *
 * @param sourceType               来源类型，如 LOCAL、FEISHU
 * @param sourceToken              外部系统 token
 * @param sourceUrl                外部系统 URL
 * @param revisionId               外部系统版本号
 * @param lastSyncedAtEpochMillis  最近同步时间
 * @param nextRefreshAtEpochMillis 下次刷新时间
 */
public record KnowledgeDocumentSource(
        String sourceType,
        String sourceToken,
        String sourceUrl,
        String revisionId,
        long lastSyncedAtEpochMillis,
        long nextRefreshAtEpochMillis
) {

    public KnowledgeDocumentSource {
        sourceType = sourceType == null || sourceType.isBlank() ? "LOCAL" : sourceType.strip().toUpperCase();
        sourceToken = sourceToken == null ? "" : sourceToken.strip();
        sourceUrl = sourceUrl == null ? "" : sourceUrl.strip();
        revisionId = revisionId == null ? "" : revisionId.strip();
        lastSyncedAtEpochMillis = Math.max(0L, lastSyncedAtEpochMillis);
        nextRefreshAtEpochMillis = Math.max(0L, nextRefreshAtEpochMillis);
    }

    public static KnowledgeDocumentSource local() {
        return new KnowledgeDocumentSource("LOCAL", "", "", "", 0L, 0L);
    }
}
