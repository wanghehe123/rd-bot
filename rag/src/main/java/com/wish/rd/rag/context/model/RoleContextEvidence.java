package com.wish.rd.rag.context.model;

/**
 * 角色上下文中的可追踪证据项。
 *
 * <p>供多 Agent 编排层构建 prompt、审计链和经验沉淀使用；来源通常来自任务材料、
 * 知识库文档、历史方案或 QA 日志。
 *
 * @param evidenceId            证据 ID
 * @param sourceType            来源类型
 * @param sourceUri             来源 URI
 * @param title                 证据标题
 * @param contentHash           内容 hash
 * @param summary               证据摘要
 * @param collectedAtEpochMillis 采集时间
 */
public record RoleContextEvidence(
        String evidenceId,
        String sourceType,
        String sourceUri,
        String title,
        String contentHash,
        String summary,
        long collectedAtEpochMillis
) {

    public RoleContextEvidence {
        evidenceId = safe(evidenceId);
        sourceType = safe(sourceType);
        sourceUri = safe(sourceUri);
        title = safe(title);
        contentHash = safe(contentHash);
        summary = safe(summary);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
