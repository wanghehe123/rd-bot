package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Structured evidence for a production-equivalent delivery-review failure rehearsal.
 */
record DeliveryReviewFailureEvidenceFile(
        boolean validated,
        String taskId,
        String taskStatus,
        boolean deliveryReviewApproved,
        String reviewDecision,
        String reviewer,
        String reviewArtifactId,
        String reviewArtifactUri,
        String rejectionReason,
        boolean pullRequestPublicationAttempted,
        boolean prCreated,
        boolean successReportCreated,
        boolean failureReportCreated,
        boolean successDeliveryReportExperienceCreated,
        boolean blockedBeforePrCreating,
        boolean stagePullRequestUrlRejected,
        boolean feishuAlertDelivered,
        String feishuAlertMessageId
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> REJECTION_DECISIONS = List.of(
            "REJECTED",
            "FAILED",
            "BLOCKED",
            "NEEDS_HUMAN"
    );
    private static final String REQUIRED_ALERT_TYPE = "DELIVERY_REVIEW_FAILED";

    DeliveryReviewFailureEvidenceFile {
        taskId = safe(taskId);
        taskStatus = safe(taskStatus);
        reviewDecision = safe(reviewDecision);
        reviewer = safe(reviewer);
        reviewArtifactId = safe(reviewArtifactId);
        reviewArtifactUri = safe(reviewArtifactUri);
        rejectionReason = safe(rejectionReason);
        feishuAlertMessageId = safe(feishuAlertMessageId);
    }

    static DeliveryReviewFailureEvidenceFile empty() {
        return new DeliveryReviewFailureEvidenceFile(
                false,
                "",
                "",
                false,
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                ""
        );
    }

    static DeliveryReviewFailureEvidenceFile from(MultiAgentProductionAcceptanceProfile profile) {
        if (profile == null || profile.deliveryReviewFailureEvidenceJson().isBlank()) {
            return empty();
        }
        Path path = Path.of(profile.deliveryReviewFailureEvidenceJson()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            String reviewDecision = root.path("reviewDecision").asText("");
            boolean sameRun = profile.rdBotVersion().equals(root.path("rdBotVersion").asText(""))
                    && profile.environmentId().equals(root.path("environmentId").asText(""))
                    && profile.executedBy().equals(root.path("executedBy").asText(""));
            boolean pullRequestPublicationAttempted = root.path("pullRequestPublicationAttempted").asBoolean(true);
            boolean prCreated = root.path("prCreated").asBoolean(true);
            boolean successReportCreated = root.path("successReportCreated").asBoolean(true);
            boolean failureReportCreated = root.path("failureReportCreated").asBoolean(false);
            boolean successDeliveryReportExperienceCreated = root
                    .path("successDeliveryReportExperienceCreated")
                    .asBoolean(true);
            boolean blockedBeforePrCreating = root.path("blockedBeforePrCreating").asBoolean(false);
            boolean stagePullRequestUrlRejected = root.path("stagePullRequestUrlRejected").asBoolean(false);
            boolean feishuAlertDelivered = root.path("feishuAlertDelivered").asBoolean(false);
            boolean validated = root.path("deliveryReviewFailureEvidenceValidated").asBoolean(false)
                    && sameRun
                    && !root.path("taskId").asText("").isBlank()
                    && "REJECTED".equals(root.path("taskStatus").asText(""))
                    && !root.path("deliveryReviewApproved").asBoolean(true)
                    && REJECTION_DECISIONS.contains(reviewDecision)
                    && "DELIVERY_REVIEWER".equals(root.path("reviewer").asText(""))
                    && !root.path("reviewArtifactId").asText("").isBlank()
                    && productionArtifactUri(root.path("reviewArtifactUri").asText(""))
                    && !root.path("rejectionReason").asText("").isBlank()
                    && !pullRequestPublicationAttempted
                    && !prCreated
                    && !successReportCreated
                    && failureReportCreated
                    && !successDeliveryReportExperienceCreated
                    && blockedBeforePrCreating
                    && feishuAlertDelivered
                    && REQUIRED_ALERT_TYPE.equals(root.path("feishuAlertType").asText(""))
                    && !root.path("feishuAlertMessageId").asText("").isBlank();
            return validated ? new DeliveryReviewFailureEvidenceFile(
                    true,
                    root.path("taskId").asText(""),
                    root.path("taskStatus").asText(""),
                    false,
                    reviewDecision,
                    root.path("reviewer").asText(""),
                    root.path("reviewArtifactId").asText(""),
                    root.path("reviewArtifactUri").asText(""),
                    root.path("rejectionReason").asText(""),
                    false,
                    false,
                    false,
                    true,
                    false,
                    true,
                    stagePullRequestUrlRejected,
                    true,
                    root.path("feishuAlertMessageId").asText("")
            ) : empty();
        } catch (RuntimeException | java.io.IOException ignored) {
            return empty();
        }
    }

    MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence toReportEvidence() {
        if (!validated) {
            return MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence.empty();
        }
        return new MultiAgentProductionAcceptanceReport.DeliveryReviewFailureEvidence(
                true,
                taskId,
                taskStatus,
                deliveryReviewApproved,
                reviewDecision,
                reviewer,
                reviewArtifactId,
                reviewArtifactUri,
                rejectionReason,
                pullRequestPublicationAttempted,
                prCreated,
                successReportCreated,
                failureReportCreated,
                successDeliveryReportExperienceCreated,
                blockedBeforePrCreating,
                stagePullRequestUrlRejected,
                feishuAlertDelivered,
                feishuAlertMessageId
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean productionArtifactUri(String value) {
        return ProductionEvidenceUris.isProductionArtifactUri(value);
    }
}
