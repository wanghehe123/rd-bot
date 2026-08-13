package com.wish.rd.rag.knowledge.projection.model;

/**
 * 文档映射表的一行。binding 是投影账本，sourceName/tombstone 来自本地文档。
 *
 * @param documentId 逻辑文档 ID
 * @param sourceName 文档名，没有本地行时为空
 * @param tombstone  本地已软删
 * @param binding    投影绑定，没有时为 null
 */
public record ProjectionAdminDocumentRow(
        String documentId,
        String sourceName,
        boolean tombstone,
        KnowledgeExternalIndexBinding binding
) {

    public ProjectionAdminDocumentRow {
        documentId = documentId == null ? "" : documentId;
        sourceName = sourceName == null ? "" : sourceName;
    }
}
