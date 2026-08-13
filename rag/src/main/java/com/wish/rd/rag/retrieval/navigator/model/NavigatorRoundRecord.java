package com.wish.rd.rag.retrieval.navigator.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 三层导航一轮的留证。中间轮的 {@code terminalStop} 为空；最后一轮带上退出原因。
 *
 * @param roundIndex       从 1 起的轮次
 * @param query            本轮查询
 * @param knowledgeBaseIds 本轮 scope
 * @param l0Calls          本轮实际发出的 L0 次数
 * @param l1Calls          本轮实际发出的 L1 次数
 * @param l2Calls          本轮实际发出的 L2 次数
 * @param admittedCount    本轮 allowlist 通过条数
 * @param rejectionCounts  本轮按原因计的拒绝
 * @param terminalStop     若本轮结束了循环则为退出原因
 * @param elapsedMillis    从循环起点到本轮结束的墙钟
 * @param tokens           到本轮结束时累计的上下文 Token
 */
public record NavigatorRoundRecord(
        int roundIndex,
        String query,
        List<String> knowledgeBaseIds,
        int l0Calls,
        int l1Calls,
        int l2Calls,
        int admittedCount,
        Map<EvidenceRejectionReason, Integer> rejectionCounts,
        Optional<NavigatorStopReason> terminalStop,
        long elapsedMillis,
        int tokens
) {

    public NavigatorRoundRecord {
        roundIndex = Math.max(1, roundIndex);
        query = query == null ? "" : query;
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        l0Calls = Math.max(0, l0Calls);
        l1Calls = Math.max(0, l1Calls);
        l2Calls = Math.max(0, l2Calls);
        admittedCount = Math.max(0, admittedCount);
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
        terminalStop = terminalStop == null ? Optional.empty() : terminalStop;
        elapsedMillis = Math.max(0L, elapsedMillis);
        tokens = Math.max(0, tokens);
    }
}
