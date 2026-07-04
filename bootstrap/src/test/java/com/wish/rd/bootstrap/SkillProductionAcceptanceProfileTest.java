package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillProductionAcceptanceProfileTest {

    @Test
    void shouldReportAllMissingSkillSmokePropertiesTogether() {
        java.util.List<String> missing = SkillProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.containsAll(SkillProductionAcceptanceProfile.requiredPropertyNames()));
        assertTrue(missing.contains("rd.skill.smoke.skill-source-uri"));
        assertTrue(missing.contains("rd.skill.smoke.skill-checksum"));
        assertTrue(missing.contains("rd.skill.smoke.install-root"));
        assertTrue(missing.contains("rd.skill.smoke.allowed-role"));
        assertTrue(missing.contains("rd.skill.smoke.rejected-role"));
        assertTrue(missing.contains("rd.skill.smoke.high-risk-role"));
    }

    @Test
    void shouldRequireExplicitProductionEvidence() {
        Map<String, String> properties = validProperties();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(properties);
        changed.put("rd.skill.smoke.production-evidence", "false");

        assertEquals(
                java.util.List.of("rd.skill.smoke.production-evidence=true"),
                SkillProductionAcceptanceProfile.missingRequiredProperties(changed)
        );
    }

    @Test
    void shouldRequireExplicitRoleBoundaryProperties() {
        Map<String, String> properties = validProperties();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(properties);
        changed.remove("rd.skill.smoke.allowed-role");
        changed.remove("rd.skill.smoke.rejected-role");
        changed.remove("rd.skill.smoke.high-risk-role");

        java.util.List<String> missing = SkillProductionAcceptanceProfile.missingRequiredProperties(changed);

        assertTrue(missing.contains("rd.skill.smoke.allowed-role"));
        assertTrue(missing.contains("rd.skill.smoke.rejected-role"));
        assertTrue(missing.contains("rd.skill.smoke.high-risk-role"));
    }

    @Test
    void shouldRejectWeakSkillArtifactPropertiesForRealSmoke() {
        Map<String, String> properties = validProperties();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(properties);
        changed.put("rd.skill.smoke.skill-source-uri", "mock://skill");
        changed.put("rd.skill.smoke.skill-checksum", "sha256:abc123");
        changed.put("rd.skill.smoke.install-root", "relative/skills-installed");

        java.util.List<String> missing = SkillProductionAcceptanceProfile.missingRequiredProperties(changed);

        assertTrue(missing.contains("rd.skill.smoke.skill-source-uri=file://<absolute-path>"));
        assertTrue(missing.contains("rd.skill.smoke.skill-checksum=sha256:<64-hex>"));
        assertTrue(missing.contains("rd.skill.smoke.install-root=<absolute-path>"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillProductionAcceptanceProfile.from(changed)
        );
    }

    @Test
    void shouldRejectProfilesWhenRoleBoundariesAreNotDistinct() {
        Map<String, String> properties = validProperties();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(properties);
        changed.put("rd.skill.smoke.rejected-role", "QA_AGENT");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SkillProductionAcceptanceProfile.from(changed)
        );

        assertTrue(failure.getMessage().contains("role boundaries"));
    }

    @Test
    void shouldBuildProfileFromRealSkillProperties() {
        SkillProductionAcceptanceProfile profile = SkillProductionAcceptanceProfile.from(validProperties());

        assertEquals("0.1.0-smoke", profile.rdBotVersion());
        assertEquals("prod-equivalent-a", profile.environmentId());
        assertEquals("qa-runner", profile.executedBy());
        assertEquals("qa-real-runner", profile.skillId());
        assertEquals("v1", profile.skillVersion());
        assertEquals("file:///opt/rd-bot/skills-src/qa-real-runner", profile.skillSourceUri());
        assertEquals(validChecksum(), profile.skillChecksum());
        assertEquals("/opt/rd-bot/skills-installed", profile.installRoot());
        assertEquals("QA_AGENT", profile.allowedRole());
        assertEquals("REQUIREMENT_REVIEWER", profile.rejectedRole());
        assertEquals("CODING_AGENT", profile.highRiskRole());
        assertEquals("qa-real-runner-high-risk", profile.highRiskSkillId());
        assertEquals(java.util.List.of("secret", "token"), profile.secretScanNeedles());
        assertTrue(profile.describeMissingRequirements().contains("skill-source-uri"));
    }

    @Test
    void shouldAcceptOptionalMainTaskIdForSameTaskSkillPolicyEvidence() {
        Map<String, String> properties = new java.util.LinkedHashMap<>(validProperties());
        properties.put("rd.skill.smoke.task-id", "7478000000000000000");

        SkillProductionAcceptanceProfile profile = SkillProductionAcceptanceProfile.from(properties);

        assertEquals("7478000000000000000", profile.taskId());
        assertTrue(profile.describeMissingRequirements().contains("rd.skill.smoke.task-id"));
    }

    private Map<String, String> validProperties() {
        return Map.ofEntries(
                entry("rd.skill.smoke.production-evidence", "true"),
                entry("rd.skill.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.skill.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.skill.smoke.executed-by", "qa-runner"),
                entry("rd.skill.smoke.skill-id", "qa-real-runner"),
                entry("rd.skill.smoke.skill-version", "v1"),
                entry("rd.skill.smoke.skill-source-uri", "file:///opt/rd-bot/skills-src/qa-real-runner"),
                entry("rd.skill.smoke.skill-checksum", validChecksum()),
                entry("rd.skill.smoke.install-root", "/opt/rd-bot/skills-installed"),
                entry("rd.skill.smoke.allowed-role", "QA_AGENT"),
                entry("rd.skill.smoke.rejected-role", "REQUIREMENT_REVIEWER"),
                entry("rd.skill.smoke.high-risk-role", "CODING_AGENT"),
                entry("rd.skill.smoke.secret-scan-needles", "secret,token")
        );
    }

    private String validChecksum() {
        return "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    }
}
