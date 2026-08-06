package com.wish.rd.engine.provider;

import com.wish.rd.engine.agent.model.AgentRole;

import java.util.Objects;

/**
 * Evaluates an already-observed provider fallback against {@link ProviderFallbackGate}.
 * Used by the stage orchestrator to reject silent unsafe degradation after the fact.
 */
public final class ProviderFallbackPolicyEnforcer {

    private final ProviderFallbackGate gate;
    private final ProviderCapabilityCatalog catalog;

    public ProviderFallbackPolicyEnforcer() {
        this(new ProviderFallbackGate(), new ProviderCapabilityCatalog());
    }

    public ProviderFallbackPolicyEnforcer(ProviderFallbackGate gate, ProviderCapabilityCatalog catalog) {
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /**
     * @param role           stage role (maps to work risk)
     * @param failedProvider primary that failed
     * @param failedStatus   raw failure status / code
     * @param activeProvider provider that actually served the result
     * @return host decision
     */
    public ProviderFallbackDecision evaluate(
            AgentRole role,
            String failedProvider,
            String failedStatus,
            String activeProvider
    ) {
        return evaluateWithReason(
                role,
                failedProvider,
                failedStatus,
                activeProvider,
                ProviderFallbackSideEffectSafety.unknown(
                        "side-effect operation state was not supplied"
                )
        ).decision();
    }

    /**
     * Evaluates a fallback with explicit host-side operation/attempt evidence.
     *
     * @param role              stage role (maps to work risk)
     * @param failedProvider    primary that failed
     * @param failedStatus      raw failure status / code
     * @param activeProvider    provider that actually served the result
     * @param sideEffectSafety  host-side operation/attempt evidence
     * @return host decision
     */
    public ProviderFallbackDecision evaluate(
            AgentRole role,
            String failedProvider,
            String failedStatus,
            String activeProvider,
            ProviderFallbackSideEffectSafety sideEffectSafety
    ) {
        return evaluateWithReason(
                role,
                failedProvider,
                failedStatus,
                activeProvider,
                sideEffectSafety
        ).decision();
    }

    /**
     * Evaluates a fallback and retains the reason used by host audit evidence.
     *
     * @param role              stage role (maps to work risk)
     * @param failedProvider    primary that failed
     * @param failedStatus      raw failure status / code
     * @param activeProvider    provider that actually served the result
     * @param sideEffectSafety  host-side operation/attempt evidence
     * @return auditable host evaluation
     */
    public ProviderFallbackEvaluation evaluateWithReason(
            AgentRole role,
            String failedProvider,
            String failedStatus,
            String activeProvider,
            ProviderFallbackSideEffectSafety sideEffectSafety
    ) {
        ProviderCapabilityProfile primary = catalog.resolve(failedProvider);
        ProviderCapabilityProfile fallback = catalog.resolve(activeProvider);
        ProviderWorkRisk workRisk = ProviderCapabilityCatalog.workRiskForRole(role);
        ProviderCapabilityProfile.ProviderFailureClass failureClass =
                ProviderCapabilityProfile.parseFailure(failedStatus);
        return gate.evaluate(primary, fallback, workRisk, failureClass, sideEffectSafety);
    }
}
