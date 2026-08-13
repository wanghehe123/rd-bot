package com.wish.rd.rag.retrieval.navigator.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 allowlist 判定的结果。拒绝计数四个原因始终在场，零也要出现，
 * 这样评测才能区分「远端未召回」和「召回了但被拒」。
 *
 * @param admitted        通过的命中，保持输入顺序
 * @param rejectionCounts 每个拒绝原因的条数，含零值
 */
public record KnowledgeEvidenceAllowlistResult(
        List<AdmittedKnowledgeEvidence> admitted,
        Map<EvidenceRejectionReason, Integer> rejectionCounts
) {

    public KnowledgeEvidenceAllowlistResult {
        admitted = admitted == null ? List.of() : List.copyOf(admitted);
        EnumMap<EvidenceRejectionReason, Integer> complete = new EnumMap<>(EvidenceRejectionReason.class);
        for (EvidenceRejectionReason reason : EvidenceRejectionReason.values()) {
            complete.put(reason, 0);
        }
        if (rejectionCounts != null) {
            rejectionCounts.forEach((reason, count) -> {
                if (reason != null && count != null && count > 0) {
                    complete.put(reason, count);
                }
            });
        }
        rejectionCounts = Map.copyOf(complete);
    }

    /**
     * 某一原因的拒绝条数。
     *
     * @param reason 原因
     * @return 条数，不会为 null
     */
    public int count(EvidenceRejectionReason reason) {
        if (reason == null) {
            return 0;
        }
        return rejectionCounts.getOrDefault(reason, 0);
    }

    /**
     * 被拒绝的命中总数。
     *
     * @return 各原因之和
     */
    public int rejectedCount() {
        int total = 0;
        for (int count : rejectionCounts.values()) {
            total += count;
        }
        return total;
    }
}
