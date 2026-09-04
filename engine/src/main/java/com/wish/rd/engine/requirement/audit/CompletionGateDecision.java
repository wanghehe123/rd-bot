package com.wish.rd.engine.requirement.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision from {@link AuditedCompletionGate}.
 *
 * @param supportsCompletion whether Host may write {@code COMPLETED}
 * @param gapRecordIds blocking records that are not evidenced, capped at 32
 * @param shadowWouldReject whether ENFORCE would have rejected
 */
public record CompletionGateDecision(
        boolean supportsCompletion,
        List<String> gapRecordIds,
        boolean shadowWouldReject
) {
    public static final int MAX_GAPS = 32;

    public CompletionGateDecision {
        gapRecordIds = gapRecordIds == null ? List.of() : List.copyOf(gapRecordIds);
        if (gapRecordIds.size() > MAX_GAPS) {
            gapRecordIds = List.copyOf(gapRecordIds.subList(0, MAX_GAPS));
        }
    }
}
