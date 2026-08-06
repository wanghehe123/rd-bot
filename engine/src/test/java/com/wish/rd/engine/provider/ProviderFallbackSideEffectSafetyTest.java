package com.wish.rd.engine.provider;

import com.wish.rd.engine.agent.model.AgentRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-PROV-01: cross-provider fallback must not silently accept weaker side-effect capability.
 */
class ProviderFallbackSideEffectSafetyTest {

    private final ProviderFallbackPolicyEnforcer enforcer = new ProviderFallbackPolicyEnforcer(
            new ProviderFallbackGate(),
            new ProviderCapabilityCatalog()
                    .register(ProviderCapabilityProfile.of(
                            "tool-primary",
                            ProviderWorkRisk.TOOL_SIDE_EFFECT,
                            ProviderCapability.TOOL_CALLING,
                            ProviderCapability.SIDE_EFFECT_TOOLS,
                            ProviderCapability.STRICT_JSON
                    ))
                    .register(ProviderCapabilityProfile.of(
                            "no-side-effect",
                            ProviderWorkRisk.TOOL_SIDE_EFFECT,
                            ProviderCapability.TOOL_CALLING,
                            ProviderCapability.STRICT_JSON
                    ))
                    .register(ProviderCapabilityProfile.of(
                            "tool-secondary",
                            ProviderWorkRisk.TOOL_SIDE_EFFECT,
                            ProviderCapability.TOOL_CALLING,
                            ProviderCapability.SIDE_EFFECT_TOOLS,
                            ProviderCapability.STRICT_JSON
                    ))
    );

    @Test
    void codingFallbackWithoutSideEffectCapabilityIsNotAllow() {
        ProviderFallbackDecision decision = enforcer.evaluate(
                AgentRole.CODING_AGENT,
                "tool-primary",
                "TIMEOUT",
                "no-side-effect"
        );
        assertEquals(ProviderFallbackDecision.WAITING_POLICY, decision);
        assertNotEquals(ProviderFallbackDecision.ALLOW, decision);
    }

    @Test
    void codingFallbackWithoutExplicitSafeAttemptStateIsNotAllow() {
        ProviderFallbackDecision decision = enforcer.evaluate(
                AgentRole.CODING_AGENT,
                "tool-primary",
                "TIMEOUT",
                "tool-secondary"
        );

        assertEquals(ProviderFallbackDecision.WAITING_POLICY, decision);
        assertNotEquals(ProviderFallbackDecision.ALLOW, decision);
    }

    @Test
    void codingFallbackWithExplicitCleanAttemptIsAllowed() {
        ProviderFallbackDecision decision = enforcer.evaluate(
                AgentRole.CODING_AGENT,
                "tool-primary",
                "TIMEOUT",
                "tool-secondary",
                ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                        "operation-1",
                        "attempt-2"
                )
        );

        assertEquals(ProviderFallbackDecision.ALLOW, decision);
    }

    @Test
    void unknownSideEffectStateCarriesAuditableReason() {
        ProviderFallbackEvaluation evaluation = enforcer.evaluateWithReason(
                AgentRole.CODING_AGENT,
                "tool-primary",
                "TIMEOUT",
                "tool-secondary",
                ProviderFallbackSideEffectSafety.unknown("publication state is UNKNOWN_REMOTE_RESULT")
        );

        assertEquals(ProviderFallbackDecision.WAITING_POLICY, evaluation.decision());
        assertTrue(evaluation.reason().contains("UNKNOWN_REMOTE_RESULT"));
    }

    @Test
    void qaFallbackOntoUnknownGenerationProviderIsNotAllow() {
        ProviderFallbackDecision decision = enforcer.evaluate(
                AgentRole.QA_AGENT,
                "tool-primary",
                "500",
                "unknown-summary-model"
        );
        assertEquals(ProviderFallbackDecision.WAITING_POLICY, decision);
    }
}
