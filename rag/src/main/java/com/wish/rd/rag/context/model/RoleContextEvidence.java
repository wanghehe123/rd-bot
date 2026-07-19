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
 * @param selectionReason       进入当前角色上下文的确定性原因
 * @param relevanceScore        当前角色相关性分数，范围 0..1
 * @param requiredEvidenceType  该证据满足的关键证据类型
 * @param sharedRoot            是否为所有角色可共享的任务根证据
 */
public record RoleContextEvidence(
        String evidenceId,
        String sourceType,
        String sourceUri,
        String title,
        String contentHash,
        String summary,
        long collectedAtEpochMillis,
        String selectionReason,
        double relevanceScore,
        String requiredEvidenceType,
        boolean sharedRoot
) {

    public RoleContextEvidence {
        evidenceId = safe(evidenceId);
        sourceType = safe(sourceType);
        sourceUri = safe(sourceUri);
        title = safe(title);
        contentHash = safe(contentHash);
        summary = safe(summary);
        selectionReason = safe(selectionReason);
        if (selectionReason.isBlank()) {
            selectionReason = "legacy context evidence";
        }
        relevanceScore = Math.max(0.0d, Math.min(1.0d, relevanceScore));
        requiredEvidenceType = safe(requiredEvidenceType).toUpperCase();
    }

    /** Backward-compatible constructor for historical context evidence rows. */
    public RoleContextEvidence(
            String evidenceId,
            String sourceType,
            String sourceUri,
            String title,
            String contentHash,
            String summary,
            long collectedAtEpochMillis
    ) {
        this(
                evidenceId, sourceType, sourceUri, title, contentHash, summary, collectedAtEpochMillis,
                "legacy context evidence", 0.0d, "", false
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
