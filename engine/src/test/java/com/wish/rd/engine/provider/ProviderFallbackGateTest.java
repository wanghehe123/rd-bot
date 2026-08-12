package com.wish.rd.engine.provider;

import com.wish.rd.engine.provider.model.ProviderCapability;
import com.wish.rd.engine.provider.model.ProviderCapabilityProfile;
import com.wish.rd.engine.provider.model.ProviderFallbackDecision;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;
import com.wish.rd.engine.provider.model.ProviderWorkRisk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderFallbackGateTest {

    private final ProviderFallbackGate gate = new ProviderFallbackGate();

    @Test
    void shouldAllowGenerationFallbackOnRateLimit() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "primary", ProviderWorkRisk.GENERATION_ONLY, ProviderCapability.STRICT_JSON);
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "fallback", ProviderWorkRisk.GENERATION_ONLY, ProviderCapability.STRICT_JSON);

        assertEquals(
                ProviderFallbackDecision.ALLOW,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.GENERATION_ONLY,
                        ProviderCapabilityProfile.ProviderFailureClass.RATE_LIMIT
                )
        );
    }

    @Test
    void shouldRequireHumanForHighRiskWork() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "primary",
                ProviderWorkRisk.HIGH_RISK,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS
        );
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "fallback",
                ProviderWorkRisk.HIGH_RISK,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS
        );

        assertEquals(
                ProviderFallbackDecision.NEEDS_HUMAN,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.HIGH_RISK,
                        ProviderCapabilityProfile.ProviderFailureClass.SERVER_ERROR
                )
        );
    }

    @Test
    void shouldBlockToolFallbackWithoutSideEffectCapability() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "primary",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS
        );
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "fallback",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING
        );

        assertEquals(
                ProviderFallbackDecision.WAITING_POLICY,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.TOOL_SIDE_EFFECT,
                        ProviderCapabilityProfile.ProviderFailureClass.TIMEOUT
                )
        );
    }

    @Test
    void shouldAllowToolFallbackWithExplicitCleanAttempt() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "primary",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        );
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "fallback",
                ProviderWorkRisk.TOOL_SIDE_EFFECT,
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.SIDE_EFFECT_TOOLS,
                ProviderCapability.STRICT_JSON
        );

        assertEquals(
                ProviderFallbackDecision.ALLOW,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.TOOL_SIDE_EFFECT,
                        ProviderCapabilityProfile.ProviderFailureClass.TIMEOUT,
                        ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                "operation-1",
                                "attempt-2"
                        )
                )
        );
    }

    @Test
    void shouldAllowGenerationFallbackOnFailedValidation() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "deepseek", ProviderWorkRisk.GENERATION_ONLY, ProviderCapability.STRICT_JSON);
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "claude", ProviderWorkRisk.GENERATION_ONLY, ProviderCapability.STRICT_JSON);

        assertEquals(
                ProviderFallbackDecision.ALLOW,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.GENERATION_ONLY,
                        ProviderCapabilityProfile.parseFailure("FAILED_VALIDATION")
                )
        );
    }

    @Test
    void shouldRequireHumanOnAuthFailure() {
        ProviderCapabilityProfile primary = ProviderCapabilityProfile.of(
                "primary", ProviderWorkRisk.GENERATION_ONLY);
        ProviderCapabilityProfile fallback = ProviderCapabilityProfile.of(
                "fallback", ProviderWorkRisk.GENERATION_ONLY);

        assertEquals(
                ProviderFallbackDecision.NEEDS_HUMAN,
                gate.decide(
                        primary,
                        fallback,
                        ProviderWorkRisk.GENERATION_ONLY,
                        ProviderCapabilityProfile.ProviderFailureClass.AUTH
                )
        );
    }
}
