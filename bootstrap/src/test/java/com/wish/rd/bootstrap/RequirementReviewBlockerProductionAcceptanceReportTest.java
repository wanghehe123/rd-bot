package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementReviewBlockerProductionAcceptanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealRequirementReviewBlockerProperties() {
        List<String> missing = RequirementReviewBlockerProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.requirement-review.smoke.production-evidence"));
        assertTrue(missing.contains("rd.requirement-review.smoke.base-url"));
        assertTrue(missing.contains("rd.requirement-review.smoke.postgres-url"));
        assertTrue(missing.contains("rd.requirement-review.smoke.task-id"));
        assertTrue(missing.contains("rd.requirement-review.smoke.review-artifact-uri"));
        assertTrue(missing.contains("rd.requirement-review.smoke.feishu-alert-message-id"));
    }

    @Test
    void shouldRejectMockOrLocalRequirementReviewArtifactUris() {
        Map<String, String> properties = validProperties("mock://rd-bot/review/blocker.json");

        List<String> missing = RequirementReviewBlockerProductionAcceptanceProfile
                .missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.requirement-review.smoke.review-artifact-uri=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> RequirementReviewBlockerProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        RequirementReviewBlockerProductionAcceptanceProfile profile =
                RequirementReviewBlockerProductionAcceptanceProfile.from(validProperties());

        assertEquals("task-review-blocked", profile.taskId());
        assertEquals("s3://rd-bot-review/requirement-review/blocker.json", profile.reviewArtifactUri());
        assertEquals("om-review-blocked", profile.feishuAlertMessageId());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleRequirementReviewSidecar() throws Exception {
        RequirementReviewBlockerProductionAcceptanceProfile profile =
                RequirementReviewBlockerProductionAcceptanceProfile.from(validProperties());
        RequirementReviewBlockerProductionAcceptanceReport report =
                new RequirementReviewBlockerProductionAcceptanceReport(
                        tempDir,
                        Clock.fixed(Instant.parse("2026-07-03T12:00:00Z"), ZoneOffset.UTC)
                );
        RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence evidence =
                new RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence(
                        profile,
                        "stage-reviewer",
                        "FAILED_NEEDS_HUMAN",
                        "NEEDS_HUMAN",
                        "NEED_INFO",
                        "artifact-review-json",
                        "缺少业务边界和验收命令",
                        List.of("业务边界", "验收命令"),
                        List.of("SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"),
                        false
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_REQUIREMENT_REVIEW_BLOCKER_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        RequirementReviewBlockerEvidenceFile loaded = RequirementReviewBlockerEvidenceFile.from(
                multiAgentProfile(json)
        );
        assertTrue(loaded.validated());
        assertEquals("task-review-blocked", loaded.taskId());
        assertEquals("stage-reviewer", loaded.stageRunId());
        assertEquals(3, loaded.pendingDownstreamRoleCount());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteRequirementReviewCommand() throws Exception {
        RequirementReviewBlockerProductionAcceptanceReport report =
                new RequirementReviewBlockerProductionAcceptanceReport(
                        tempDir,
                        Clock.fixed(Instant.parse("2026-07-03T12:01:00Z"), ZoneOffset.UTC)
                );

        Path markdown = report.writeSkipped(List.of("rd.requirement-review.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("RequirementReviewBlockerRealSmokeTest"));
        assertTrue(content.contains("-Drd.requirement-review.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.requirement-review.smoke.task-id=<rd-bot-task-id>"));
        assertTrue(content.contains("| 3 | 需求评审 Agent 能阻断不可交付需求 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties("s3://rd-bot-review/requirement-review/blocker.json");
    }

    private static Map<String, String> validProperties(String reviewArtifactUri) {
        return Map.ofEntries(
                entry("rd.requirement-review.smoke.production-evidence", "true"),
                entry("rd.requirement-review.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.requirement-review.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.requirement-review.smoke.executed-by", "qa-runner"),
                entry("rd.requirement-review.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.requirement-review.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.requirement-review.smoke.postgres-user", "rd_bot"),
                entry("rd.requirement-review.smoke.postgres-password", "postgres-secret"),
                entry("rd.requirement-review.smoke.task-id", "task-review-blocked"),
                entry("rd.requirement-review.smoke.review-artifact-uri", reviewArtifactUri),
                entry("rd.requirement-review.smoke.feishu-alert-message-id", "om-review-blocked"),
                entry("rd.requirement-review.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path requirementReviewEvidenceJson) {
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
                entry("rd.multi-agent.smoke.requirement-review-evidence-json",
                        requirementReviewEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
