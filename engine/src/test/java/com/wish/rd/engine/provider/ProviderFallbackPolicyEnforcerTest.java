package com.wish.rd.engine.provider;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.provider.model.ProviderFallbackDecision;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;
import com.wish.rd.engine.provider.model.ProviderWorkRisk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderFallbackPolicyEnforcerTest {

    private final ProviderFallbackPolicyEnforcer enforcer = new ProviderFallbackPolicyEnforcer();

    @Test
    void shouldAllowCodingFallbackBetweenToolCapableProvidersOnRateLimit() {
        assertEquals(
                ProviderFallbackDecision.ALLOW,
                enforcer.evaluate(
                        AgentRole.CODING_AGENT,
                        "openai",
                        "429",
                        "anthropic",
                        ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                "task-1:CODING_AGENT:1",
                                "stage-coding-1#attempt-1"
                        )
                )
        );
    }

    @Test
    void shouldAllowGenerationFallbackOnFailedValidationWithoutSideEffectEvidence() {
        assertEquals(
                ProviderFallbackDecision.ALLOW,
                enforcer.evaluate(
                        AgentRole.REQUIREMENT_REVIEWER,
                        "openai",
                        "FAILED_VALIDATION",
                        "weak-gen"
                )
        );
    }

    @Test
    void shouldBlockCodingFallbackOntoGenerationOnlyProvider() {
        assertEquals(
                ProviderFallbackDecision.WAITING_POLICY,
                enforcer.evaluate(AgentRole.CODING_AGENT, "openai", "503", "weak-gen")
        );
    }

    @Test
    void shouldRequireHumanOnAuthFailureEvenForGenerationRoles() {
        assertEquals(
                ProviderFallbackDecision.NEEDS_HUMAN,
                enforcer.evaluate(AgentRole.REQUIREMENT_REVIEWER, "openai", "401", "anthropic")
        );
    }

    @Test
    void shouldRequireHumanForHostClassifiedDatabaseMigrationEvenWhenFallbackIsCapable() {
        assertEquals(
                ProviderFallbackDecision.NEEDS_HUMAN,
                enforcer.evaluateWithReason(
                        AgentRole.CODING_AGENT,
                        "openai",
                        "503",
                        "anthropic",
                        ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                "stage-1", "attempt-2"
                        ),
                        ProviderWorkRisk.HIGH_RISK
                ).decision()
        );
    }
}
