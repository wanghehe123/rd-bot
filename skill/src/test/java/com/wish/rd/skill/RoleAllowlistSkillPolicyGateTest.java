package com.wish.rd.skill;

import com.wish.rd.skill.impl.RoleAllowlistSkillPolicyGate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.skill.model.AgentSkillDescriptor;
import com.wish.rd.skill.model.SkillPolicyDecision;
import com.wish.rd.skill.model.SkillRiskLevel;

class RoleAllowlistSkillPolicyGateTest {

    @Test
    void shouldAllowSkillOnlyForConfiguredRoles() {
        AgentSkillDescriptor descriptor = descriptor(SkillRiskLevel.LOW, "QA_AGENT");
        RoleAllowlistSkillPolicyGate gate = new RoleAllowlistSkillPolicyGate(true);

        SkillPolicyDecision allowed = gate.decide(descriptor, "qa_agent");
        SkillPolicyDecision rejected = gate.decide(descriptor, "CODING_AGENT");

        assertTrue(allowed.allowed());
        assertEquals("ALLOWED", allowed.action());
        assertFalse(rejected.allowed());
        assertEquals("REJECTED", rejected.action());
    }

    @Test
    void shouldRequireApprovalForHighRiskSkillWhenConfigured() {
        AgentSkillDescriptor descriptor = descriptor(SkillRiskLevel.HIGH, "CODING_AGENT");
        RoleAllowlistSkillPolicyGate gate = new RoleAllowlistSkillPolicyGate(true);

        SkillPolicyDecision decision = gate.decide(descriptor, "CODING_AGENT");

        assertFalse(decision.allowed());
        assertEquals("WAITING_APPROVAL", decision.action());
        assertEquals(SkillRiskLevel.HIGH, decision.riskLevel());
    }

    private AgentSkillDescriptor descriptor(SkillRiskLevel riskLevel, String role) {
        return new AgentSkillDescriptor(
                "qa-real-runner",
                "v1",
                "file:///skills/qa-real-runner",
                "sha256:skill",
                List.of(role),
                riskLevel,
                "./install.sh",
                "真实 QA 验收 Skill"
        );
    }
}
