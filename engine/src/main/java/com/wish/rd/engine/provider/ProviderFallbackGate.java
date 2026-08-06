package com.wish.rd.engine.provider;

import java.util.Objects;

/**
 * Host gate that decides whether a provider fallback is safe.
 * High-risk work never silently falls back to a weaker provider.
 */
public final class ProviderFallbackGate {

    /**
     * Decides whether {@code fallback} may replace {@code primary} after a classified failure.
     *
     * @param primary      failed primary profile
     * @param fallback     candidate fallback profile
     * @param workRisk     risk of the current work
     * @param failureClass classified failure of the primary
     * @return host decision
     */
    public ProviderFallbackDecision decide(
            ProviderCapabilityProfile primary,
            ProviderCapabilityProfile fallback,
            ProviderWorkRisk workRisk,
            ProviderCapabilityProfile.ProviderFailureClass failureClass
    ) {
        return evaluate(
                primary,
                fallback,
                workRisk,
                failureClass,
                ProviderFallbackSideEffectSafety.unknown(
                        "side-effect operation state was not supplied"
                )
        ).decision();
    }

    /**
     * Decides whether a fallback may proceed with explicit side-effect safety evidence.
     *
     * @param primary          failed primary profile
     * @param fallback         candidate fallback profile
     * @param workRisk         risk of the current work
     * @param failureClass     classified failure of the primary
     * @param sideEffectSafety host-side operation/attempt evidence
     * @return host decision
     */
    public ProviderFallbackDecision decide(
            ProviderCapabilityProfile primary,
            ProviderCapabilityProfile fallback,
            ProviderWorkRisk workRisk,
            ProviderCapabilityProfile.ProviderFailureClass failureClass,
            ProviderFallbackSideEffectSafety sideEffectSafety
    ) {
        return evaluate(primary, fallback, workRisk, failureClass, sideEffectSafety).decision();
    }

    /**
     * Decides whether a provider fallback may proceed with explicit side-effect
     * safety evidence.
     *
     * @param primary       failed primary profile
     * @param fallback      candidate fallback profile
     * @param workRisk      risk of the current work
     * @param failureClass  classified failure of the primary
     * @param sideEffectSafety host-side operation/attempt evidence
     * @return auditable host evaluation
     */
    public ProviderFallbackEvaluation evaluate(
            ProviderCapabilityProfile primary,
            ProviderCapabilityProfile fallback,
            ProviderWorkRisk workRisk,
            ProviderCapabilityProfile.ProviderFailureClass failureClass,
            ProviderFallbackSideEffectSafety sideEffectSafety
    ) {
        Objects.requireNonNull(primary, "primary must not be null");
        Objects.requireNonNull(fallback, "fallback must not be null");
        Objects.requireNonNull(workRisk, "workRisk must not be null");
        Objects.requireNonNull(failureClass, "failureClass must not be null");
        ProviderFallbackSideEffectSafety safety = sideEffectSafety == null
                ? ProviderFallbackSideEffectSafety.unknown(
                        "side-effect operation state was not supplied"
                )
                : sideEffectSafety;

        if (failureClass == ProviderCapabilityProfile.ProviderFailureClass.AUTH) {
            return new ProviderFallbackEvaluation(
                    ProviderFallbackDecision.NEEDS_HUMAN,
                    "provider authentication failure requires human confirmation"
            );
        }
        if (!fallback.mayServe(workRisk)) {
            ProviderFallbackDecision decision = workRisk == ProviderWorkRisk.HIGH_RISK
                    ? ProviderFallbackDecision.NEEDS_HUMAN
                    : ProviderFallbackDecision.WAITING_POLICY;
            return new ProviderFallbackEvaluation(
                    decision,
                    "fallback provider risk ceiling does not cover " + workRisk
            );
        }
        if (workRisk == ProviderWorkRisk.HIGH_RISK) {
            return new ProviderFallbackEvaluation(
                    ProviderFallbackDecision.NEEDS_HUMAN,
                    "high-risk work cannot switch providers without human confirmation"
            );
        }
        if (workRisk == ProviderWorkRisk.TOOL_SIDE_EFFECT) {
            if (!safety.isExplicitlySafe()) {
                return new ProviderFallbackEvaluation(
                        ProviderFallbackDecision.WAITING_POLICY,
                        "tool-side-effect provider fallback blocked: "
                                + safety.auditReason()
                );
            }
            if (!fallback.supports(ProviderCapability.TOOL_CALLING)
                    || !fallback.supports(ProviderCapability.SIDE_EFFECT_TOOLS)) {
                return new ProviderFallbackEvaluation(
                        ProviderFallbackDecision.WAITING_POLICY,
                        "fallback provider lacks tool side-effect capability"
                );
            }
            // Side-effect work requires at least the primary's tool capabilities.
            if (!fallback.capabilities().containsAll(requiredToolCapabilities(primary))) {
                return new ProviderFallbackEvaluation(
                        ProviderFallbackDecision.WAITING_POLICY,
                        "fallback provider does not preserve primary tool capabilities"
                );
            }
            return new ProviderFallbackEvaluation(
                    ProviderFallbackDecision.ALLOW,
                    "tool-side-effect fallback allowed with "
                            + safety.state().name().toLowerCase(java.util.Locale.ROOT)
            );
        }
        // GENERATION_ONLY: limited fallback for rate-limit / 5xx / timeout.
        return switch (failureClass) {
            case RATE_LIMIT, SERVER_ERROR, TIMEOUT -> new ProviderFallbackEvaluation(
                    ProviderFallbackDecision.ALLOW,
                    "generation fallback allowed for " + failureClass
            );
            case AUTH, UNKNOWN -> new ProviderFallbackEvaluation(
                    ProviderFallbackDecision.WAITING_POLICY,
                    "generation fallback failure class requires policy review: " + failureClass
            );
        };
    }

    private static java.util.Set<ProviderCapability> requiredToolCapabilities(ProviderCapabilityProfile primary) {
        java.util.EnumSet<ProviderCapability> required =
                java.util.EnumSet.noneOf(ProviderCapability.class);
        for (ProviderCapability capability : primary.capabilities()) {
            if (capability == ProviderCapability.TOOL_CALLING
                    || capability == ProviderCapability.SIDE_EFFECT_TOOLS
                    || capability == ProviderCapability.STRICT_JSON) {
                required.add(capability);
            }
        }
        return required;
    }
}
