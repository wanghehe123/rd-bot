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

class DeliveryReviewFailureProductionAcceptanceReportTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireRealDeliveryReviewFailureProperties() {
        List<String> missing = DeliveryReviewFailureProductionAcceptanceProfile.missingRequiredProperties(Map.of());

        assertTrue(missing.contains("rd.delivery-review-failure.smoke.production-evidence"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.base-url"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.postgres-url"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.task-id"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.review-artifact-uri"));
        assertTrue(missing.contains("rd.delivery-review-failure.smoke.feishu-alert-message-id"));
    }

    @Test
    void shouldRejectMockOrLocalDeliveryReviewArtifactUris() {
        Map<String, String> properties = validProperties("mock://rd-bot/delivery-review/rejection.json");

        List<String> missing = DeliveryReviewFailureProductionAcceptanceProfile
                .missingRequiredProperties(properties);

        assertTrue(missing.contains("rd.delivery-review-failure.smoke.review-artifact-uri=http(s)|s3|rd-artifact"));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryReviewFailureProductionAcceptanceProfile.from(properties));
    }

    @Test
    void shouldBuildProfileWithoutLeakingSecretsInDescription() {
        DeliveryReviewFailureProductionAcceptanceProfile profile =
                DeliveryReviewFailureProductionAcceptanceProfile.from(validProperties());

        assertEquals("task-review-rejected", profile.taskId());
        assertEquals("s3://rd-bot-review/delivery-review/rejection.json", profile.reviewArtifactUri());
        assertEquals("om-delivery-review-failed", profile.feishuAlertMessageId());
        assertEquals(List.of("postgres-secret"), profile.secretScanNeedles());
        assertFalse(profile.describeMissingRequirements().contains("postgres-secret"));
    }

    @Test
    void shouldWritePassedReportAsMultiAgentCompatibleDeliveryReviewFailureSidecar() throws Exception {
        DeliveryReviewFailureProductionAcceptanceProfile profile =
                DeliveryReviewFailureProductionAcceptanceProfile.from(validProperties());
        DeliveryReviewFailureProductionAcceptanceReport report = new DeliveryReviewFailureProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T12:20:00Z"), ZoneOffset.UTC)
        );
        DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence evidence =
                new DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence(
                        profile,
                        "REJECTED",
                        false,
                        "REJECTED",
                        "artifact-delivery-review",
                        "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        true
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        assertTrue(Files.readString(markdown).contains("PASSED_DELIVERY_REVIEW_FAILURE_SMOKE"));
        assertTrue(Files.readString(markdown).contains("| 8 | 交付复核通过后才提交为已交付 | PASSED |"));
        assertTrue(Files.isRegularFile(json));
        DeliveryReviewFailureEvidenceFile loaded = DeliveryReviewFailureEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("task-review-rejected", loaded.taskId());
        assertEquals("REJECTED", loaded.taskStatus());
        assertTrue(loaded.stagePullRequestUrlRejected());
    }

    @Test
    void shouldWritePassedReportWhenDeliveryReviewRejectsIncompleteQaEvidence() throws Exception {
        DeliveryReviewFailureProductionAcceptanceProfile profile =
                DeliveryReviewFailureProductionAcceptanceProfile.from(validProperties());
        DeliveryReviewFailureProductionAcceptanceReport report = new DeliveryReviewFailureProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T12:20:30Z"), ZoneOffset.UTC)
        );
        DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence evidence =
                new DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence(
                        profile,
                        "REJECTED",
                        false,
                        "FAILED",
                        "artifact-delivery-review",
                        "QA_AGENT delivery evidence is incomplete",
                        false,
                        false,
                        false,
                        true,
                        false,
                        true,
                        false
                );

        Path markdown = report.writePassed(evidence);
        Path json = markdown.resolveSibling(markdown.getFileName().toString().replace(".md", ".json"));

        String markdownContent = Files.readString(markdown);
        assertTrue(markdownContent.contains("PASSED_DELIVERY_REVIEW_FAILURE_SMOKE"));
        assertTrue(markdownContent.contains("stagePullRequestUrlRejected=false"));
        DeliveryReviewFailureEvidenceFile loaded = DeliveryReviewFailureEvidenceFile.from(multiAgentProfile(json));
        assertTrue(loaded.validated());
        assertEquals("FAILED", loaded.reviewDecision());
        assertEquals("QA_AGENT delivery evidence is incomplete", loaded.rejectionReason());
        assertFalse(loaded.stagePullRequestUrlRejected());
    }

    @Test
    void shouldWriteSkippedReportWithConcreteDeliveryReviewCommand() throws Exception {
        DeliveryReviewFailureProductionAcceptanceReport report = new DeliveryReviewFailureProductionAcceptanceReport(
                tempDir,
                Clock.fixed(Instant.parse("2026-07-03T12:21:00Z"), ZoneOffset.UTC)
        );

        Path markdown = report.writeSkipped(List.of("rd.delivery-review-failure.smoke.base-url"));
        String content = Files.readString(markdown);

        assertTrue(content.contains("DeliveryReviewFailureRealSmokeTest"));
        assertTrue(content.contains("-Drd.delivery-review-failure.smoke.production-evidence=true"));
        assertTrue(content.contains("-Drd.delivery-review-failure.smoke.task-id=<rd-bot-task-id>"));
        assertTrue(content.contains("| 8 | 交付复核通过后才提交为已交付 | NOT_RUN |"));
    }

    private static Map<String, String> validProperties() {
        return validProperties("s3://rd-bot-review/delivery-review/rejection.json");
    }

    private static Map<String, String> validProperties(String reviewArtifactUri) {
        return Map.ofEntries(
                entry("rd.delivery-review-failure.smoke.production-evidence", "true"),
                entry("rd.delivery-review-failure.smoke.rd-bot-version", "0.1.0-smoke"),
                entry("rd.delivery-review-failure.smoke.environment-id", "prod-equivalent-a"),
                entry("rd.delivery-review-failure.smoke.executed-by", "qa-runner"),
                entry("rd.delivery-review-failure.smoke.base-url", "http://127.0.0.1:8080"),
                entry("rd.delivery-review-failure.smoke.postgres-url", "jdbc:postgresql://127.0.0.1:5432/rd_bot"),
                entry("rd.delivery-review-failure.smoke.postgres-user", "rd_bot"),
                entry("rd.delivery-review-failure.smoke.postgres-password", "postgres-secret"),
                entry("rd.delivery-review-failure.smoke.task-id", "task-review-rejected"),
                entry("rd.delivery-review-failure.smoke.review-artifact-uri", reviewArtifactUri),
                entry("rd.delivery-review-failure.smoke.feishu-alert-message-id", "om-delivery-review-failed"),
                entry("rd.delivery-review-failure.smoke.secret-scan-needles", "postgres-secret")
        );
    }

    private static MultiAgentProductionAcceptanceProfile multiAgentProfile(Path deliveryReviewFailureEvidenceJson) {
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
                entry("rd.multi-agent.smoke.delivery-review-failure-evidence-json",
                        deliveryReviewFailureEvidenceJson.toString())
        ), Map.of(
                "LONGCAT_API_KEY", "longcat-secret",
                "ANTHROPIC_API_KEY", "anthropic-secret",
                "GITHUB_PAT", "github-secret"
        ));
    }
}
