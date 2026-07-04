package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Structured evidence for a production-equivalent QA failure rehearsal that blocks delivery.
 */
record QaFailureBlockerEvidenceFile(
        boolean validated,
        String taskId,
        String stageRunId,
        String taskStatus,
        String qaStageStatus,
        String executionResultStatus,
        String qaReportArtifactId,
        String qaReportArtifactUri,
        List<String> validationLogArtifactUris,
        int failedAcceptanceCount,
        int acceptanceResultCount,
        int validationCommandCount,
        int validationLogArtifactCount,
        boolean prCreated,
        boolean successReportCreated,
        boolean blockedBeforePrCreating,
        boolean feishuAlertDelivered,
        String feishuAlertMessageId
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> BLOCKING_EXECUTION_STATUSES = List.of(
            "FAILED",
            "NEEDS_HUMAN",
            "FAILED_NEEDS_HUMAN"
    );
    private static final List<String> BLOCKING_QA_STAGE_STATUSES = List.of(
            "FAILED_NEEDS_HUMAN",
            "FAILED_VALIDATION"
    );
    private static final String REQUIRED_ALERT_TYPE = "QA_FAILED";

    QaFailureBlockerEvidenceFile {
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        taskStatus = safe(taskStatus);
        qaStageStatus = safe(qaStageStatus);
        executionResultStatus = safe(executionResultStatus);
        qaReportArtifactId = safe(qaReportArtifactId);
        qaReportArtifactUri = safe(qaReportArtifactUri);
        validationLogArtifactUris = validationLogArtifactUris == null ? List.of() : List.copyOf(validationLogArtifactUris);
        failedAcceptanceCount = Math.max(failedAcceptanceCount, 0);
        acceptanceResultCount = Math.max(acceptanceResultCount, 0);
        validationCommandCount = Math.max(validationCommandCount, 0);
        validationLogArtifactCount = Math.max(validationLogArtifactCount, 0);
        feishuAlertMessageId = safe(feishuAlertMessageId);
    }

    static QaFailureBlockerEvidenceFile empty() {
        return new QaFailureBlockerEvidenceFile(
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                ""
        );
    }

    static QaFailureBlockerEvidenceFile from(MultiAgentProductionAcceptanceProfile profile) {
        if (profile == null || profile.qaFailureEvidenceJson().isBlank()) {
            return empty();
        }
        Path path = Path.of(profile.qaFailureEvidenceJson()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            int failedAcceptanceCount = root.path("failedAcceptanceCount").asInt(0);
            int acceptanceResultCount = root.path("acceptanceResultCount").asInt(0);
            int validationCommandCount = root.path("validationCommandCount").asInt(0);
            int validationLogArtifactCount = root.path("validationLogArtifactCount").asInt(0);
            String qaReportArtifactUri = root.path("qaReportArtifactUri").asText("");
            List<String> validationLogArtifactUris = values(root.path("validationLogArtifactUris"));
            boolean sameRun = profile.rdBotVersion().equals(root.path("rdBotVersion").asText(""))
                    && profile.environmentId().equals(root.path("environmentId").asText(""))
                    && profile.executedBy().equals(root.path("executedBy").asText(""));
            String executionResultStatus = root.path("executionResultStatus").asText("");
            boolean validated = root.path("qaFailureBlockerEvidenceValidated").asBoolean(false)
                    && sameRun
                    && !root.path("taskId").asText("").isBlank()
                    && !root.path("stageRunId").asText("").isBlank()
                    && "QA_AGENT".equals(root.path("role").asText(""))
                    && "FAILED_NEEDS_HUMAN".equals(root.path("taskStatus").asText(""))
                    && BLOCKING_QA_STAGE_STATUSES.contains(root.path("qaStageStatus").asText(""))
                    && BLOCKING_EXECUTION_STATUSES.contains(executionResultStatus)
                    && !root.path("qaReportArtifactId").asText("").isBlank()
                    && productionArtifactUri(qaReportArtifactUri)
                    && productionArtifactUrisCover(validationLogArtifactUris, acceptanceResultCount)
                    && failedAcceptanceCount > 0
                    && acceptanceResultCount >= failedAcceptanceCount
                    && validationCommandCount >= acceptanceResultCount
                    && validationLogArtifactCount >= acceptanceResultCount
                    && !root.path("prCreated").asBoolean(true)
                    && !root.path("successReportCreated").asBoolean(true)
                    && root.path("blockedBeforePrCreating").asBoolean(false)
                    && root.path("feishuAlertDelivered").asBoolean(false)
                    && REQUIRED_ALERT_TYPE.equals(root.path("feishuAlertType").asText(""))
                    && !root.path("feishuAlertMessageId").asText("").isBlank();
            return validated ? new QaFailureBlockerEvidenceFile(
                    true,
                    root.path("taskId").asText(""),
                    root.path("stageRunId").asText(""),
                    root.path("taskStatus").asText(""),
                    root.path("qaStageStatus").asText(""),
                    executionResultStatus,
                    root.path("qaReportArtifactId").asText(""),
                    qaReportArtifactUri,
                    validationLogArtifactUris,
                    failedAcceptanceCount,
                    acceptanceResultCount,
                    validationCommandCount,
                    validationLogArtifactCount,
                    false,
                    false,
                    true,
                    true,
                    root.path("feishuAlertMessageId").asText("")
            ) : empty();
        } catch (RuntimeException | java.io.IOException ignored) {
            return empty();
        }
    }

    MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence toReportEvidence() {
        if (!validated) {
            return MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence.empty();
        }
        return new MultiAgentProductionAcceptanceReport.QaFailureBlockerEvidence(
                true,
                taskId,
                stageRunId,
                taskStatus,
                qaStageStatus,
                executionResultStatus,
                qaReportArtifactId,
                qaReportArtifactUri,
                validationLogArtifactUris,
                failedAcceptanceCount,
                acceptanceResultCount,
                validationCommandCount,
                validationLogArtifactCount,
                prCreated,
                successReportCreated,
                blockedBeforePrCreating,
                feishuAlertDelivered,
                feishuAlertMessageId
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> values(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = safe(item.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static boolean productionArtifactUrisCover(List<String> values, int acceptanceResultCount) {
        return values.size() >= acceptanceResultCount
                && values.stream().allMatch(QaFailureBlockerEvidenceFile::productionArtifactUri)
                && values.stream().distinct().count() == values.size();
    }

    private static boolean productionArtifactUri(String value) {
        return ProductionEvidenceUris.isProductionArtifactUri(value);
    }
}
