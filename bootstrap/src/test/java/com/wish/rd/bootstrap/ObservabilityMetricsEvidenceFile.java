package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Structured evidence for production observability metrics and audit trace validation.
 */
record ObservabilityMetricsEvidenceFile(
        boolean validated,
        String taskId,
        String metricsEndpointUrl,
        int metricsHttpStatus,
        boolean contextBuildLatencyMetricPresent,
        boolean repairSuccessRateMetricPresent,
        boolean validationPassRateMetricPresent,
        boolean prCreationRateMetricPresent,
        boolean humanInterventionRateMetricPresent,
        boolean retryRateMetricPresent,
        boolean meanTimeToRepairMetricPresent,
        boolean topFailureCategoriesMetricPresent,
        int stageMetricCount,
        boolean auditTraceQuerySucceeded,
        boolean remotePrTraceValidated,
        int auditTraceLinkCount,
        int taskBoundAuditTraceLinkCount
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    ObservabilityMetricsEvidenceFile {
        taskId = safe(taskId);
        metricsEndpointUrl = safe(metricsEndpointUrl);
        metricsHttpStatus = Math.max(metricsHttpStatus, 0);
        stageMetricCount = Math.max(stageMetricCount, 0);
        auditTraceLinkCount = Math.max(auditTraceLinkCount, 0);
        taskBoundAuditTraceLinkCount = Math.max(taskBoundAuditTraceLinkCount, 0);
    }

    static ObservabilityMetricsEvidenceFile empty() {
        return new ObservabilityMetricsEvidenceFile(
                false,
                "",
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                false,
                false,
                0,
                0
        );
    }

    static ObservabilityMetricsEvidenceFile from(MultiAgentProductionAcceptanceProfile profile) {
        if (profile == null || profile.observabilityMetricsEvidenceJson().isBlank()) {
            return empty();
        }
        Path path = Path.of(profile.observabilityMetricsEvidenceJson()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            int metricsHttpStatus = root.path("metricsHttpStatus").asInt(0);
            int stageMetricCount = root.path("stageMetricCount").asInt(0);
            int auditTraceLinkCount = root.path("auditTraceLinkCount").asInt(0);
            int taskBoundAuditTraceLinkCount = root.path("taskBoundAuditTraceLinkCount").asInt(0);
            String taskId = root.path("taskId").asText("");
            String observabilityTaskId = root.path("observabilityTaskId").asText("");
            boolean sameRun = profile.rdBotVersion().equals(root.path("rdBotVersion").asText(""))
                    && profile.environmentId().equals(root.path("environmentId").asText(""))
                    && profile.executedBy().equals(root.path("executedBy").asText(""));
            boolean validated = root.path("observabilityMetricsEvidenceValidated").asBoolean(false)
                    && sameRun
                    && !taskId.isBlank()
                    && taskId.equals(observabilityTaskId)
                    && metricsEndpointMatchesProfile(root.path("metricsEndpointUrl").asText(""), profile.baseUrl())
                    && metricsHttpStatus == 200
                    && root.path("contextBuildLatencyMetricPresent").asBoolean(false)
                    && root.path("repairSuccessRateMetricPresent").asBoolean(false)
                    && root.path("validationPassRateMetricPresent").asBoolean(false)
                    && root.path("prCreationRateMetricPresent").asBoolean(false)
                    && root.path("humanInterventionRateMetricPresent").asBoolean(false)
                    && root.path("retryRateMetricPresent").asBoolean(false)
                    && root.path("meanTimeToRepairMetricPresent").asBoolean(false)
                    && root.path("topFailureCategoriesMetricPresent").asBoolean(false)
                    && stageMetricCount >= 4
                    && root.path("auditTraceQuerySucceeded").asBoolean(false)
                    && root.path("remotePrTraceValidated").asBoolean(false)
                    && auditTraceLinkCount >= 8
                    && taskBoundAuditTraceLinkCount >= 8
                    && taskBoundAuditTraceLinkCount <= auditTraceLinkCount;
            return validated ? new ObservabilityMetricsEvidenceFile(
                    true,
                    taskId,
                    root.path("metricsEndpointUrl").asText(""),
                    metricsHttpStatus,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    stageMetricCount,
                    true,
                    true,
                    auditTraceLinkCount,
                    taskBoundAuditTraceLinkCount
            ) : empty();
        } catch (RuntimeException | java.io.IOException ignored) {
            return empty();
        }
    }

    MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence toReportEvidence() {
        if (!validated) {
            return MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence.empty();
        }
        return new MultiAgentProductionAcceptanceReport.ObservabilityMetricsEvidence(
                true,
                taskId,
                metricsEndpointUrl,
                metricsHttpStatus,
                contextBuildLatencyMetricPresent,
                repairSuccessRateMetricPresent,
                validationPassRateMetricPresent,
                prCreationRateMetricPresent,
                humanInterventionRateMetricPresent,
                retryRateMetricPresent,
                meanTimeToRepairMetricPresent,
                topFailureCategoriesMetricPresent,
                stageMetricCount,
                auditTraceQuerySucceeded,
                remotePrTraceValidated,
                auditTraceLinkCount,
                taskBoundAuditTraceLinkCount
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean metricsEndpointMatchesProfile(String metricsEndpointUrl, String baseUrl) {
        String safeMetricsEndpointUrl = safe(metricsEndpointUrl);
        if (safeMetricsEndpointUrl.isBlank()) {
            return false;
        }
        URI actual = URI.create(safeMetricsEndpointUrl).normalize();
        URI expected = URI.create(appendPath(baseUrl, "actuator/prometheus")).normalize();
        return expected.equals(actual);
    }

    private static String appendPath(String baseUrl, String path) {
        String safeBaseUrl = safe(baseUrl);
        String safePath = safe(path);
        while (safeBaseUrl.endsWith("/")) {
            safeBaseUrl = safeBaseUrl.substring(0, safeBaseUrl.length() - 1);
        }
        while (safePath.startsWith("/")) {
            safePath = safePath.substring(1);
        }
        return safeBaseUrl + "/" + safePath;
    }
}
