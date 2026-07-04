package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPolicyEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedSkillPolicyEvidenceForTheSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson());
        MultiAgentProductionAcceptanceProfile profile = profile(evidenceJson);

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile);

        assertTrue(evidence.validated());
        assertEquals("task-skill-smoke", evidence.taskId());
        assertEquals("stage-skill-smoke", evidence.stageRunId());
        assertEquals("qa-real-runner", evidence.skillId());
        assertEquals("v1", evidence.skillVersion());
        assertTrue(evidence.installed());
        assertTrue(evidence.unauthorizedRejected());
        assertTrue(evidence.highRiskWaitingApproval());
        assertTrue(evidence.metadataValidated());
        assertEquals(1, evidence.installerCallCount());
    }

    @Test
    void shouldIgnoreSkillPolicyEvidenceFromDifferentProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace("prod-equivalent-a", "another-env"));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertEquals("", evidence.taskId());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenHighRiskInstallReachedInstaller() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"installerCallCount\": 1",
                "\"installerCallCount\": 2"
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWithoutExplicitRoleBoundaries() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"allowedRole\": \"QA_AGENT\"",
                "\"allowedRole\": \"\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenRoleBoundariesAreNotDistinct() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"rejectedRole\": \"REQUIREMENT_REVIEWER\"",
                "\"rejectedRole\": \"QA_AGENT\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenInstallPathDoesNotMatchSkillIdentity() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"installPath\": \"/opt/rd-bot/skills/qa-real-runner/v1\"",
                "\"installPath\": \"/opt/rd-bot/skills/other-skill/v1\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenInstallPathIsRelative() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"installPath\": \"/opt/rd-bot/skills/qa-real-runner/v1\"",
                "\"installPath\": \"qa-real-runner/v1\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenSourceChecksumIsNotFullSha256() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"sourceChecksum\": \"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"",
                "\"sourceChecksum\": \"sha256:abc123\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectSkillPolicyEvidenceWhenTopLevelTaskIdDoesNotMatchTaskId() throws Exception {
        Path evidenceJson = tempDir.resolve("skill-policy.json");
        Files.writeString(evidenceJson, validEvidenceJson().replace(
                "\"skillPolicyTaskId\": \"task-skill-smoke\"",
                "\"skillPolicyTaskId\": \"task-skill-other\""
        ));

        SkillPolicyEvidenceFile evidence = SkillPolicyEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson() {
        return """
                {
                  "skillPolicyEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "prod-equivalent-a",
                  "executedBy": "qa-runner",
                  "skillPolicyTaskId": "task-skill-smoke",
                  "taskId": "task-skill-smoke",
                  "stageRunId": "stage-skill-smoke",
                  "skillId": "qa-real-runner",
                  "skillVersion": "v1",
                  "allowedRole": "QA_AGENT",
                  "rejectedRole": "REQUIREMENT_REVIEWER",
                  "highRiskRole": "CODING_AGENT",
                  "installPath": "/opt/rd-bot/skills/qa-real-runner/v1",
                  "sourceChecksum": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "installed": true,
                  "unauthorizedRejected": true,
                  "highRiskWaitingApproval": true,
                  "metadataValidated": true,
                  "installerCallCount": 1
                }
                """;
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path skillPolicyEvidenceJson) {
        return MultiAgentProductionAcceptanceProfile.from(Map.ofEntries(
                entry("rd.multi-agent.smoke.production-evidence", "true"),
                entry("rd.multi-agent.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.multi-agent.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.multi-agent.smoke.executed-by", "qa-runner"),
                entry("rd.multi-agent.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.multi-agent.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.multi-agent.smoke.postgres-user", "rd_bot"),
                entry("rd.multi-agent.smoke.postgres-password", "secret"),
                entry("rd.multi-agent.smoke.repository-url", "https://github.com/acme/rd-bot-smoke.git"),
                entry("rd.multi-agent.smoke.repo-owner", "acme"),
                entry("rd.multi-agent.smoke.repo-name", "rd-bot-smoke"),
                entry("rd.multi-agent.smoke.expected-provider-count", "2"),
                entry("rd.multi-agent.smoke.provider-secret-env-names", "LONGCAT_API_KEY,ANTHROPIC_API_KEY"),
                entry("rd.multi-agent.smoke.github-code-platform-mode", "real"),
                entry("rd.multi-agent.smoke.github-auth-mode", "PAT_LOCAL_SMOKE"),
                entry("rd.multi-agent.smoke.github-credential-env-names", "GITHUB_PAT"),
                entry("rd.multi-agent.smoke.secret-scan-needles", "postgres-secret,github-secret"),
                entry("rd.multi-agent.smoke.skill-policy-evidence-json", skillPolicyEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
