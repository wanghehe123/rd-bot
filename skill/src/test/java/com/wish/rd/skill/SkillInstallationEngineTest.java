package com.wish.rd.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillInstallationEngineTest {

    @Test
    void shouldInstallLowRiskSkillWhenRoleIsAllowed() {
        AgentSkillDescriptor descriptor = descriptor(SkillRiskLevel.LOW, "QA_AGENT");
        AtomicInteger installerCalls = new AtomicInteger();
        SkillInstallationEngine engine = new SkillInstallationEngine(
                registry(descriptor),
                new RoleAllowlistSkillPolicyGate(true),
                (command, skill) -> {
                    installerCalls.incrementAndGet();
                    return new SkillInstallResult(
                            true,
                            skill.skillId(),
                            skill.version(),
                            "/opt/rd-bot/skills/" + skill.skillId(),
                            "installed",
                            "{}"
                    );
                }
        );

        SkillInstallResult result = engine.install(new SkillInstallCommand(
                "task-1",
                "stage-1",
                "qa_agent",
                "qa-real-runner",
                "v1"
        ));

        assertTrue(result.installed());
        assertEquals(1, installerCalls.get());
        assertEquals("qa-real-runner", result.skillId());
        assertEquals("v1", result.version());
        assertTrue(result.policyJson().contains("\"action\":\"ALLOWED\""));
        assertTrue(result.policyJson().contains("\"riskLevel\":\"LOW\""));
    }

    @Test
    void shouldRejectUnauthorizedRoleWithoutCallingInstaller() {
        AgentSkillDescriptor descriptor = descriptor(SkillRiskLevel.LOW, "QA_AGENT");
        AtomicInteger installerCalls = new AtomicInteger();
        SkillInstallationEngine engine = new SkillInstallationEngine(
                registry(descriptor),
                new RoleAllowlistSkillPolicyGate(true),
                (command, skill) -> {
                    installerCalls.incrementAndGet();
                    return new SkillInstallResult(true, skill.skillId(), skill.version(), "/tmp/skill", "", "{}");
                }
        );

        SkillInstallResult result = engine.install(new SkillInstallCommand(
                "task-1",
                "stage-1",
                "CODING_AGENT",
                "qa-real-runner",
                "v1"
        ));

        assertFalse(result.installed());
        assertEquals(0, installerCalls.get());
        assertTrue(result.message().contains("skill is not allowed for role"));
        assertTrue(result.policyJson().contains("\"action\":\"REJECTED\""));
    }

    @Test
    void shouldWaitForApprovalForHighRiskSkillWithoutCallingInstaller() {
        AgentSkillDescriptor descriptor = descriptor(SkillRiskLevel.HIGH, "CODING_AGENT");
        AtomicInteger installerCalls = new AtomicInteger();
        SkillInstallationEngine engine = new SkillInstallationEngine(
                registry(descriptor),
                new RoleAllowlistSkillPolicyGate(true),
                (command, skill) -> {
                    installerCalls.incrementAndGet();
                    return new SkillInstallResult(true, skill.skillId(), skill.version(), "/tmp/skill", "", "{}");
                }
        );

        SkillInstallResult result = engine.install(new SkillInstallCommand(
                "task-1",
                "stage-1",
                "CODING_AGENT",
                "qa-real-runner",
                "v1"
        ));

        assertFalse(result.installed());
        assertEquals(0, installerCalls.get());
        assertTrue(result.message().contains("high risk skill requires manual approval"));
        assertTrue(result.policyJson().contains("\"action\":\"WAITING_APPROVAL\""));
    }

    @Test
    void shouldRejectSkillWithoutRequiredProductionMetadataBeforePolicyInstall() {
        AgentSkillDescriptor descriptor = new AgentSkillDescriptor(
                "metadata-missing",
                "",
                "",
                "",
                List.of("QA_AGENT"),
                SkillRiskLevel.LOW,
                "./install.sh",
                "缺失元数据 Skill"
        );
        AtomicInteger installerCalls = new AtomicInteger();
        SkillInstallationEngine engine = new SkillInstallationEngine(
                registry(descriptor),
                new RoleAllowlistSkillPolicyGate(true),
                (command, skill) -> {
                    installerCalls.incrementAndGet();
                    return new SkillInstallResult(true, skill.skillId(), skill.version(), "/tmp/skill", "", "{}");
                }
        );

        SkillInstallResult result = engine.install(new SkillInstallCommand(
                "task-1",
                "stage-1",
                "QA_AGENT",
                "metadata-missing",
                ""
        ));

        assertFalse(result.installed());
        assertEquals(0, installerCalls.get());
        assertTrue(result.message().contains("skill metadata incomplete"));
        assertTrue(result.policyJson().contains("\"action\":\"REJECTED\""));
    }

    @Test
    void shouldRejectSkillWithoutRiskLevelBeforePolicyInstall() {
        AgentSkillDescriptor descriptor = new AgentSkillDescriptor(
                "risk-missing",
                "v1",
                "file:///skills/risk-missing",
                "sha256:skill",
                List.of("QA_AGENT"),
                null,
                "./install.sh",
                "缺失风险等级 Skill"
        );
        AtomicInteger installerCalls = new AtomicInteger();
        SkillInstallationEngine engine = new SkillInstallationEngine(
                registry(descriptor),
                new RoleAllowlistSkillPolicyGate(true),
                (command, skill) -> {
                    installerCalls.incrementAndGet();
                    return new SkillInstallResult(true, skill.skillId(), skill.version(), "/tmp/skill", "", "{}");
                }
        );

        SkillInstallResult result = engine.install(new SkillInstallCommand(
                "task-1",
                "stage-1",
                "QA_AGENT",
                "risk-missing",
                "v1"
        ));

        assertFalse(result.installed());
        assertEquals(0, installerCalls.get());
        assertTrue(result.message().contains("skill metadata incomplete: riskLevel"));
        assertTrue(result.policyJson().contains("\"riskLevel\":\"UNKNOWN\""));
    }

    private SkillRegistryPort registry(AgentSkillDescriptor descriptor) {
        return new SkillRegistryPort() {

            @Override
            public List<AgentSkillDescriptor> listAvailable() {
                return List.of(descriptor);
            }

            @Override
            public Optional<AgentSkillDescriptor> findById(String skillId) {
                return descriptor.skillId().equals(skillId) ? Optional.of(descriptor) : Optional.empty();
            }
        };
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
