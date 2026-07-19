package com.wish.rd.rag.context.model;

import java.util.List;

/**
 * 面向单个 Agent 角色的 RAG 上下文包。
 *
 * <p>供 engine 编排层按角色派发任务时引用。上下文包必须保留证据、预算、验收标准和省略证据，
 * 避免把不可追踪的大段材料直接塞给执行器。
 *
 * @param packageId             上下文包 ID
 * @param taskId                RD 任务 ID
 * @param role                  Agent 角色
 * @param packageVersion        上下文包版本
 * @param evidence              已纳入上下文的证据
 * @param acceptanceCriteria    验收标准
 * @param riskHints             风险提示
 * @param maxChars              上下文字符预算
 * @param usedChars             已使用字符数
 * @param omittedEvidenceIds    因预算或角色不匹配省略的证据 ID
 * @param retrievalRunId        生成本上下文包的不可变 RetrievalRun ID
 * @param createdAtEpochMillis  创建时间
 */
public record RoleContextPackage(
        String packageId,
        String taskId,
        String role,
        int packageVersion,
        List<RoleContextEvidence> evidence,
        List<String> acceptanceCriteria,
        List<String> riskHints,
        int maxChars,
        int usedChars,
        List<String> omittedEvidenceIds,
        String retrievalRunId,
        long createdAtEpochMillis
) {

    public RoleContextPackage {
        packageId = safe(packageId);
        taskId = safe(taskId);
        role = safe(role).toUpperCase();
        packageVersion = Math.max(1, packageVersion);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        acceptanceCriteria = acceptanceCriteria == null
                ? List.of()
                : acceptanceCriteria.stream().map(RoleContextPackage::safe).filter(value -> !value.isBlank()).toList();
        riskHints = riskHints == null
                ? List.of()
                : riskHints.stream().map(RoleContextPackage::safe).filter(value -> !value.isBlank()).toList();
        maxChars = Math.max(0, maxChars);
        usedChars = Math.max(0, usedChars);
        omittedEvidenceIds = omittedEvidenceIds == null
                ? List.of()
                : omittedEvidenceIds.stream().map(RoleContextPackage::safe).filter(value -> !value.isBlank()).toList();
        retrievalRunId = safe(retrievalRunId);
    }

    /** Backward-compatible constructor for historical rows that predate RetrievalRun binding. */
    public RoleContextPackage(
            String packageId,
            String taskId,
            String role,
            int packageVersion,
            List<RoleContextEvidence> evidence,
            List<String> acceptanceCriteria,
            List<String> riskHints,
            int maxChars,
            int usedChars,
            List<String> omittedEvidenceIds,
            long createdAtEpochMillis
    ) {
        this(
                packageId, taskId, role, packageVersion, evidence, acceptanceCriteria, riskHints,
                maxChars, usedChars, omittedEvidenceIds, "", createdAtEpochMillis
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
