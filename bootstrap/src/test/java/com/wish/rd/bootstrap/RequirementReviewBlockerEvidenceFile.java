package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Structured evidence for a real requirement-reviewer blocker rehearsal.
 */
record RequirementReviewBlockerEvidenceFile(
        boolean validated,
        String taskId,
        String stageRunId,
        String taskStatus,
        String executionResultStatus,
        String reviewDecision,
        String reviewArtifactId,
        String reviewArtifactUri,
        String errorMessage,
        List<String> missingInformation,
        int pendingDownstreamRoleCount,
        boolean downstreamAgentsDispatched,
        boolean feishuAlertDelivered,
        String feishuAlertMessageId
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> BLOCKING_DECISIONS = List.of(
            "NEED_INFO",
            "NEEDS_HUMAN",
            "UNSAFE",
            "REJECTED",
            "FAILED",
            "BLOCKED"
    );
    private static final List<String> BLOCKING_TASK_STATUSES = List.of(
            "FAILED_NEEDS_HUMAN",
            "REJECTED"
    );
    private static final List<String> BLOCKING_EXECUTION_STATUSES = List.of(
            "NEEDS_HUMAN",
            "FAILED",
            "FAILED_NEEDS_HUMAN"
    );
    private static final List<String> REQUIRED_PENDING_ROLES = List.of(
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
    );
    private static final String REQUIRED_ALERT_TYPE = "STAGE_FAILED_NEEDS_HUMAN";

    RequirementReviewBlockerEvidenceFile {
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        taskStatus = safe(taskStatus);
        executionResultStatus = safe(executionResultStatus);
        reviewDecision = safe(reviewDecision);
        reviewArtifactId = safe(reviewArtifactId);
        reviewArtifactUri = safe(reviewArtifactUri);
        errorMessage = safe(errorMessage);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        pendingDownstreamRoleCount = Math.max(pendingDownstreamRoleCount, 0);
        feishuAlertMessageId = safe(feishuAlertMessageId);
    }

    static RequirementReviewBlockerEvidenceFile empty() {
        return new RequirementReviewBlockerEvidenceFile(
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                0,
                false,
                false,
                ""
        );
    }

    static RequirementReviewBlockerEvidenceFile from(MultiAgentProductionAcceptanceProfile profile) {
        if (profile == null || profile.requirementReviewEvidenceJson().isBlank()) {
            return empty();
        }
        Path path = Path.of(profile.requirementReviewEvidenceJson()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            String reviewDecision = root.path("reviewDecision").asText("");
            List<String> missingInformation = values(root.path("missingInformation"));
            List<String> pendingRoles = values(root.path("pendingDownstreamRoles"));
            boolean sameRun = profile.rdBotVersion().equals(root.path("rdBotVersion").asText(""))
                    && profile.environmentId().equals(root.path("environmentId").asText(""))
                    && profile.executedBy().equals(root.path("executedBy").asText(""));
            boolean validated = root.path("requirementReviewBlockerEvidenceValidated").asBoolean(false)
                    && sameRun
                    && !root.path("taskId").asText("").isBlank()
                    && !root.path("stageRunId").asText("").isBlank()
                    && "REQUIREMENT_REVIEWER".equals(root.path("role").asText(""))
                    && BLOCKING_TASK_STATUSES.contains(root.path("taskStatus").asText(""))
                    && BLOCKING_EXECUTION_STATUSES.contains(root.path("executionResultStatus").asText(""))
                    && BLOCKING_DECISIONS.contains(reviewDecision)
                    && !root.path("reviewArtifactId").asText("").isBlank()
                    && productionArtifactUri(root.path("reviewArtifactUri").asText(""))
                    && !root.path("errorMessage").asText("").isBlank()
                    && !missingInformation.isEmpty()
                    && pendingRoles.containsAll(REQUIRED_PENDING_ROLES)
                    && !root.path("downstreamAgentsDispatched").asBoolean(true)
                    && root.path("feishuAlertDelivered").asBoolean(false)
                    && REQUIRED_ALERT_TYPE.equals(root.path("feishuAlertType").asText(""))
                    && !root.path("feishuAlertMessageId").asText("").isBlank();
            return validated ? new RequirementReviewBlockerEvidenceFile(
                    true,
                    root.path("taskId").asText(""),
                    root.path("stageRunId").asText(""),
                    root.path("taskStatus").asText(""),
                    root.path("executionResultStatus").asText(""),
                    reviewDecision,
                    root.path("reviewArtifactId").asText(""),
                    root.path("reviewArtifactUri").asText(""),
                    root.path("errorMessage").asText(""),
                    missingInformation,
                    pendingRoles.size(),
                    false,
                    true,
                    root.path("feishuAlertMessageId").asText("")
            ) : empty();
        } catch (RuntimeException | java.io.IOException ignored) {
            return empty();
        }
    }

    MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence toReportEvidence() {
        if (!validated) {
            return MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence.empty();
        }
        return new MultiAgentProductionAcceptanceReport.RequirementReviewBlockerEvidence(
                true,
                taskId,
                stageRunId,
                taskStatus,
                executionResultStatus,
                reviewDecision,
                reviewArtifactId,
                reviewArtifactUri,
                errorMessage,
                missingInformation,
                pendingDownstreamRoleCount,
                downstreamAgentsDispatched,
                feishuAlertDelivered,
                feishuAlertMessageId
        );
    }

    private static List<String> values(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").strip();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean productionArtifactUri(String value) {
        return ProductionEvidenceUris.isProductionArtifactUri(value);
    }
}
