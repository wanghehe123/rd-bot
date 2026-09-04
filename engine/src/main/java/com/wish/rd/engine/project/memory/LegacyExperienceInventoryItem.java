package com.wish.rd.engine.project.memory;

import com.wish.rd.rag.project.memory.model.LegacyExperienceInventoryDecision;

/** Per-row inventory outcome. */
public record LegacyExperienceInventoryItem(
        String legacyExperienceId,
        LegacyExperienceInventoryDecision decision,
        String reason,
        String candidateRevisionId
) {
    public LegacyExperienceInventoryItem {
        legacyExperienceId = legacyExperienceId == null ? "" : legacyExperienceId.strip();
        decision = decision == null ? LegacyExperienceInventoryDecision.REJECTED : decision;
        reason = reason == null ? "" : reason.strip();
        candidateRevisionId = candidateRevisionId == null ? "" : candidateRevisionId.strip();
    }
}
