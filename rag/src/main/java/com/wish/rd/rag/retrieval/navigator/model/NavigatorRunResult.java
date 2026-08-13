package com.wish.rd.rag.retrieval.navigator.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次三层导航（或 L0 旁路探测）的完整结果。{@link #stopReason()} 必为终态，从不为空。
 *
 * @param stopReason      唯一退出原因
 * @param evidence        已准入且已围栏的证据，按收集顺序
 * @param rounds          每轮留证
 * @param rejectionCounts 全程按原因累计的拒绝
 * @param remoteCalls     实际发出的 L0+L1+L2 次数
 * @param tokens          累计上下文 Token
 * @param elapsedMillis   墙钟耗时
 */
public record NavigatorRunResult(
        NavigatorStopReason stopReason,
        List<NavigatorCollectedEvidence> evidence,
        List<NavigatorRoundRecord> rounds,
        Map<EvidenceRejectionReason, Integer> rejectionCounts,
        int remoteCalls,
        int tokens,
        long elapsedMillis
) {

    public NavigatorRunResult {
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
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
        remoteCalls = Math.max(0, remoteCalls);
        tokens = Math.max(0, tokens);
        elapsedMillis = Math.max(0L, elapsedMillis);
    }
}
