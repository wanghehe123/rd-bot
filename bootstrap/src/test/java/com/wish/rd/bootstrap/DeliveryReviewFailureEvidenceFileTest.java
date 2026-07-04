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

class DeliveryReviewFailureEvidenceFileTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidatedDeliveryReviewFailureEvidenceFromSameProductionRun() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("task-review-rejected", evidence.taskId());
        assertEquals("REJECTED", evidence.taskStatus());
        assertFalse(evidence.deliveryReviewApproved());
        assertEquals("REJECTED", evidence.reviewDecision());
        assertEquals("DELIVERY_REVIEWER", evidence.reviewer());
        assertEquals("artifact-delivery-review", evidence.reviewArtifactId());
        assertEquals("s3://rd-bot-review/delivery-review/rejection.json", evidence.reviewArtifactUri());
        assertEquals("agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                evidence.rejectionReason());
        assertFalse(evidence.pullRequestPublicationAttempted());
        assertFalse(evidence.prCreated());
        assertFalse(evidence.successReportCreated());
        assertTrue(evidence.failureReportCreated());
        assertFalse(evidence.successDeliveryReportExperienceCreated());
        assertTrue(evidence.blockedBeforePrCreating());
        assertTrue(evidence.stagePullRequestUrlRejected());
        assertTrue(evidence.feishuAlertDelivered());
        assertEquals("om-delivery-review-failed", evidence.feishuAlertMessageId());
        assertTrue(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectDeliveryReviewFailureEvidenceFromDifferentEnvironment() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "other-env",
                false,
                false,
                false,
                true,
                true
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
        assertFalse(evidence.toReportEvidence().validated());
    }

    @Test
    void shouldRejectDeliveryReviewFailureEvidenceWhenReviewFailureStillDelivered() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                true,
                true,
                true,
                false,
                false
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldLoadDeliveryReviewFailureEvidenceWhenSpecificStagePullRequestUrlFlagIsMissing() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ).replace(
                "\"stagePullRequestUrlRejected\": true,\n",
                ""
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertFalse(evidence.stagePullRequestUrlRejected());
    }

    @Test
    void shouldLoadDeliveryReviewFailureEvidenceWhenQaEvidenceIsIncomplete() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ).replace("\"reviewDecision\": \"REJECTED\"", "\"reviewDecision\": \"FAILED\"")
                .replace(
                        "\"rejectionReason\": \"agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT\"",
                        "\"rejectionReason\": \"QA_AGENT delivery evidence is incomplete\""
                )
                .replace("\"stagePullRequestUrlRejected\": true", "\"stagePullRequestUrlRejected\": false"));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertTrue(evidence.validated());
        assertEquals("FAILED", evidence.reviewDecision());
        assertEquals("QA_AGENT delivery evidence is incomplete", evidence.rejectionReason());
        assertFalse(evidence.stagePullRequestUrlRejected());
        assertTrue(evidence.blockedBeforePrCreating());
    }

    @Test
    void shouldRejectDeliveryReviewFailureEvidenceWhenFeishuAlertTypeIsWrong() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ).replace(
                "\"feishuAlertType\": \"DELIVERY_REVIEW_FAILED\"",
                "\"feishuAlertType\": \"QA_FAILED\""
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDeliveryReviewFailureEvidenceWithoutDurableReviewArtifactUri() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ).replace(
                "\"reviewArtifactUri\": \"s3://rd-bot-review/delivery-review/rejection.json\"",
                "\"reviewArtifactUri\": \"qa-runs/delivery-review/rejection.json\""
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    @Test
    void shouldRejectDeliveryReviewFailureEvidenceWithLocalFileReviewArtifactUri() throws Exception {
        Path evidenceJson = tempDir.resolve("delivery-review-failure-production-acceptance.json");
        Files.writeString(evidenceJson, validEvidenceJson(
                "prod-equivalent-a",
                false,
                false,
                false,
                true,
                true
        ).replace(
                "\"reviewArtifactUri\": \"s3://rd-bot-review/delivery-review/rejection.json\"",
                "\"reviewArtifactUri\": \"file:///tmp/rd-bot/delivery-review/rejection.json\""
        ));

        DeliveryReviewFailureEvidenceFile evidence = DeliveryReviewFailureEvidenceFile.from(profile(evidenceJson));

        assertFalse(evidence.validated());
    }

    private static String validEvidenceJson(
            String environmentId,
            boolean pullRequestPublicationAttempted,
            boolean prCreated,
            boolean successReportCreated,
            boolean failureReportCreated,
            boolean blockedBeforePrCreating
    ) {
        return """
                {
                  "deliveryReviewFailureEvidenceValidated": true,
                  "rdBotVersion": "0.1.0-smoke",
                  "environmentId": "%s",
                  "executedBy": "qa-runner",
                  "taskId": "task-review-rejected",
                  "taskStatus": "REJECTED",
                  "deliveryReviewApproved": false,
                  "reviewDecision": "REJECTED",
                  "reviewer": "DELIVERY_REVIEWER",
                  "reviewArtifactId": "artifact-delivery-review",
                  "reviewArtifactUri": "s3://rd-bot-review/delivery-review/rejection.json",
                  "rejectionReason": "agent stage must not contain pullRequestUrl before delivery review: CODING_AGENT",
                  "pullRequestPublicationAttempted": %s,
                  "prCreated": %s,
                  "successReportCreated": %s,
                  "failureReportCreated": %s,
                  "successDeliveryReportExperienceCreated": false,
                  "blockedBeforePrCreating": %s,
                  "stagePullRequestUrlRejected": true,
                  "feishuAlertDelivered": true,
                  "feishuAlertType": "DELIVERY_REVIEW_FAILED",
                  "feishuAlertMessageId": "om-delivery-review-failed"
                }
                """.formatted(
                environmentId,
                pullRequestPublicationAttempted,
                prCreated,
                successReportCreated,
                failureReportCreated,
                blockedBeforePrCreating
        );
    }

    private static MultiAgentProductionAcceptanceProfile profile(Path deliveryReviewFailureEvidenceJson) {
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
