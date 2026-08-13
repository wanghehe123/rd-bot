package com.wish.rd.rag.knowledge.projection.model;

/**
 * 墓碑行：本地已软删的文档及其投影观测。
 *
 * @param documentId           逻辑文档 ID
 * @param sourceName           文档名
 * @param deletedAtEpochMillis 软删时间
 * @param binding              投影绑定，没有时为 null
 */
public record ProjectionAdminTombstone(
        String documentId,
        String sourceName,
        long deletedAtEpochMillis,
        KnowledgeExternalIndexBinding binding
) {

    public ProjectionAdminTombstone {
        documentId = documentId == null ? "" : documentId;
        sourceName = sourceName == null ? "" : sourceName;
        deletedAtEpochMillis = Math.max(0L, deletedAtEpochMillis);
    }
}
