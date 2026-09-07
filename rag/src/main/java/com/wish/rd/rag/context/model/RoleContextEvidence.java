package com.wish.rd.rag.context.model;

/**
 * 角色上下文中的可追踪证据项。
 *
 * <p>供多 Agent 编排层构建 prompt、审计链和经验沉淀使用；来源通常来自任务材料、
 * 知识库文档、历史方案或 QA 日志。
 *
 * <p>{@code trust} 为 {@code HINT} 或 {@code VERIFIED}。检索命中默认 {@code HINT}，
 * 不得单凭类型名满足角色证据门；Host 任务根/材料与同任务有效审计引用才可 {@code VERIFIED}。
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
 * @param trust                 HINT 或 VERIFIED
 * @param auditRunId            VERIFIED 时可反查的 AuditRun；HINT 必须为空
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
        boolean sharedRoot,
        String trust,
        String auditRunId
) {

    public static final String TRUST_HINT = "HINT";
    public static final String TRUST_VERIFIED = "VERIFIED";

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
        trust = normalizeTrust(trust, sharedRoot);
        auditRunId = safe(auditRunId);
        if (TRUST_HINT.equals(trust)) {
            auditRunId = "";
        }
    }

    /** Full constructor without trust — Host shared roots default VERIFIED, else HINT. */
    public RoleContextEvidence(
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
        this(
                evidenceId, sourceType, sourceUri, title, contentHash, summary, collectedAtEpochMillis,
                selectionReason, relevanceScore, requiredEvidenceType, sharedRoot,
                sharedRoot ? TRUST_VERIFIED : TRUST_HINT,
                ""
        );
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
                "legacy context evidence", 0.0d, "", false, TRUST_HINT, ""
        );
    }

    /** Host task materials are VERIFIED for the current task without requiring an AuditRun id. */
    public static RoleContextEvidence hostMaterial(
            String evidenceId,
            String sourceType,
            String sourceUri,
            String title,
            String contentHash,
            String summary,
            long collectedAtEpochMillis
    ) {
        return new RoleContextEvidence(
                evidenceId, sourceType, sourceUri, title, contentHash, summary, collectedAtEpochMillis,
                "host task material", 0.0d, "", false, TRUST_VERIFIED, ""
        );
    }

    public boolean verified() {
        return TRUST_VERIFIED.equals(trust);
    }

    public boolean hint() {
        return TRUST_HINT.equals(trust);
    }

    private static String normalizeTrust(String trust, boolean sharedRoot) {
        String normalized = safe(trust).toUpperCase();
        if (TRUST_VERIFIED.equals(normalized)) {
            return TRUST_VERIFIED;
        }
        if (TRUST_HINT.equals(normalized)) {
            return TRUST_HINT;
        }
        return sharedRoot ? TRUST_VERIFIED : TRUST_HINT;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
