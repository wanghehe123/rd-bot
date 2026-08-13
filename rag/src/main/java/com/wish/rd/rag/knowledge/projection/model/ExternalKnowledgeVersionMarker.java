package com.wish.rd.rag.knowledge.projection.model;

/**
 * 版本核验期望值。核验只比对远端实际读到的 tags 与内容，不接受"提交过就算对"。
 *
 * @param provider        外部索引提供方
 * @param resourceRootUri 资源根
 * @param ownershipMarker {@code rd-bot:{kbId}:{docId}}
 * @param knowledgeBaseId 知识库数字 ID
 * @param documentId      逻辑文档数字 ID
 * @param syncVersion     期望投影版本
 * @param checksum        期望规范正文 SHA-256
 */
public record ExternalKnowledgeVersionMarker(
        String provider,
        String resourceRootUri,
        String ownershipMarker,
        String knowledgeBaseId,
        String documentId,
        long syncVersion,
        String checksum
) {

    public ExternalKnowledgeVersionMarker {
        provider = provider == null || provider.isBlank()
                ? KnowledgeExternalIndexBinding.OPENVIKING
                : provider.strip().toUpperCase();
        resourceRootUri = resourceRootUri == null ? "" : resourceRootUri.strip();
        ownershipMarker = ownershipMarker == null ? "" : ownershipMarker.strip();
        knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId.strip();
        documentId = documentId == null ? "" : documentId.strip();
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        checksum = checksum == null ? "" : checksum.strip();
    }
}
