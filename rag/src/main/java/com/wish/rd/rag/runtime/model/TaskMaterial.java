package com.wish.rd.rag.runtime.model;

/**
 * RD 任务输入材料。
 *
 * <p>材料保存需求正文、本地上传和 Feishu 文档等输入资产的索引与预览。大文本正文应进入
 * artifact 或知识库文档，任务材料只保留 hash、preview 和关联 ID。
 *
 * @param materialId            材料 ID
 * @param taskId                任务 ID
 * @param materialType          材料类型
 * @param sourceType            来源类型
 * @param title                 展示标题
 * @param sourceUri             来源 URI
 * @param mimeType              MIME 类型
 * @param contentHash           内容 hash
 * @param contentPreview        内容预览
 * @param artifactUri           artifact URI
 * @param knowledgeDocumentId   知识库文档 ID
 * @param revisionId            来源修订 ID
 * @param metadataJson          扩展元数据 JSON
 * @param createTimeEpochMillis 创建时间
 * @param updateTimeEpochMillis 更新时间
 */
public record TaskMaterial(
        String materialId,
        String taskId,
        TaskMaterialType materialType,
        TaskMaterialSourceType sourceType,
        String title,
        String sourceUri,
        String mimeType,
        String contentHash,
        String contentPreview,
        String artifactUri,
        String knowledgeDocumentId,
        String revisionId,
        String metadataJson,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public TaskMaterial {
        materialId = safe(materialId);
        taskId = safe(taskId);
        materialType = materialType == null ? TaskMaterialType.REQUIREMENT_DOC : materialType;
        sourceType = sourceType == null ? TaskMaterialSourceType.MANUAL_TEXT : sourceType;
        title = safe(title);
        sourceUri = safe(sourceUri);
        mimeType = safe(mimeType);
        contentHash = safe(contentHash);
        contentPreview = safe(contentPreview);
        artifactUri = safe(artifactUri);
        knowledgeDocumentId = safe(knowledgeDocumentId);
        revisionId = safe(revisionId);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson.strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
