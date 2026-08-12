package com.wish.rd.engine.provider.model;

import java.util.Objects;

/**
 * Auditable host result for one provider fallback decision.
 *
 * @param decision policy aggregate decision
 * @param reason   diagnostic reason retained for alerts and aggregate results
 */
public record ProviderFallbackEvaluation(
        ProviderFallbackDecision decision,
        String reason
) {

    public ProviderFallbackEvaluation {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        reason = reason == null || reason.isBlank()
                ? defaultReason(decision)
                : reason.strip();
    }

    /**
     * Returns whether the fallback may proceed without policy escalation.
     *
     * @return true only for {@link ProviderFallbackDecision#ALLOW}
     */
    public boolean allowed() {
        return decision == ProviderFallbackDecision.ALLOW;
    }

    private static String defaultReason(ProviderFallbackDecision decision) {
        return switch (decision) {
            case ALLOW -> "provider fallback allowed by capability and failure policy";
            case WAITING_POLICY -> "provider fallback requires an explicit policy decision";
            case NEEDS_HUMAN -> "provider fallback requires human confirmation";
        };
    }
}
