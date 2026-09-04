package com.wish.rd.engine.project.memory;

/** Host-observed delivery evidence; extractor output cannot supply these facts. */
public record ProjectMemoryPromotionEvidence(
        boolean deliverySucceeded,
        boolean qaSucceeded,
        boolean failedOrCancelled,
        boolean administratorConfirmed
) {
}
