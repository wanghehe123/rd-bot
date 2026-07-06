package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA failure blocker production smoke. Disabled by default and validates an existing real task.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=QaFailureBlockerRealSmokeTest \
 *   -Drd.integration.qa-failure.enabled=true \
 *   -Drd.qa-failure.smoke.production-evidence=true \
 *   -Drd.qa-failure.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.qa-failure.smoke.environment-id=prod-equivalent-a \
 *   -Drd.qa-failure.smoke.executed-by=qa-runner \
 *   -Drd.qa-failure.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.qa-failure.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.qa-failure.smoke.postgres-user=rd_bot \
 *   -Drd.qa-failure.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.qa-failure.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.qa-failure.smoke.qa-report-artifact-uri=s3://rd-bot-qa/qa-failure/qa-report.json \
 *   -Drd.qa-failure.smoke.validation-log-artifact-uris=s3://rd-bot-qa/qa-failure/logs/a.log \
 *   -Drd.qa-failure.smoke.feishu-alert-message-id=om_xxx \
 *   -Drd.qa-failure.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.qa-failure.enabled", matches = "true")
class QaFailureBlockerRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final AgentRoleResultValidator ROLE_RESULT_VALIDATOR = new AgentRoleResultValidator();
    private static final List<String> BLOCKING_EXECUTION_STATUSES = List.of(
            "FAILED",
            "NEEDS_HUMAN",
            "FAILED_NEEDS_HUMAN"
    );
    private static final List<String> BLOCKING_QA_STAGE_STATUSES = List.of(
            "FAILED_NEEDS_HUMAN",
            "FAILED_VALIDATION"
    );

    @Test
    void validatesQaFailureBlockedTaskAgainstRealHttpAndPostgres() throws Exception {
        QaFailureBlockerProductionAcceptanceReport report =
                QaFailureBlockerProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = QaFailureBlockerProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("qa-failure-blocker", missing, skippedReport);
        }
        QaFailureBlockerProductionAcceptanceProfile profile =
                QaFailureBlockerProductionAcceptanceProfile.from(properties);
        QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence evidence = initialEvidence(profile);
        try {
            JsonNode detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + profile.taskId(), requestTimeout());
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "QA failure smoke must validate the configured taskId");
            assertEquals("FAILED_NEEDS_HUMAN", detail.path("status").asText(""),
                    "QA failure task must be FAILED_NEEDS_HUMAN");
            JsonNode executionResult = executionResultJson(detail);
            String executionStatus = firstNonBlank(
                    executionResult.path("status").asText(""),
                    detail.path("executionStatus").asText("")
            );
            assertTrue(BLOCKING_EXECUTION_STATUSES.contains(executionStatus),
                    "executionResultJson.status must be a blocking status");

            QaStageEvidence stage;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                stage = qaStage(connection, profile.taskId());
            }
            QaReportMetrics metrics = qaReportMetrics(stage.resultContentPreview());
            boolean prCreated = prCreated(detail, executionResult);
            boolean successReportCreated = successReportCreated(detail, executionResult);
            boolean blockedBeforePrCreating = !prCreated
                    && !successReportCreated
                    && "FAILED_NEEDS_HUMAN".equals(detail.path("status").asText(""));
            evidence = new QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence(
                    profile,
                    stage.stageRunId(),
                    detail.path("status").asText(""),
                    stage.status(),
                    executionStatus,
                    stage.resultArtifactId(),
                    metrics.failedAcceptanceCount(),
                    metrics.acceptanceResultCount(),
                    metrics.validationCommandCount(),
                    prCreated,
                    successReportCreated,
                    blockedBeforePrCreating
            );
            assertNoSecretNeedles(profile, List.of(detail.toString(), stage.resultContentPreview()));
            java.nio.file.Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] qa-failure-blocker taskId=" + profile.taskId()
                    + " stageRunId=" + stage.stageRunId()
                    + " failedAcceptanceCount=" + metrics.failedAcceptanceCount()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            java.nio.file.Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] qa-failure-blocker failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return QaFailureBlockerProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence initialEvidence(
            QaFailureBlockerProductionAcceptanceProfile profile
    ) {
        return new QaFailureBlockerProductionAcceptanceReport.QaFailureSmokeEvidence(
                profile,
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                0,
                true,
                true,
                false
        );
    }

    private static QaStageEvidence qaStage(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    r.id,
                    r.status,
                    r.result_artifact_id,
                    a.content_preview
                FROM rd_agent_stage_runs r
                LEFT JOIN rd_agent_stage_artifacts a
                  ON a.id = r.result_artifact_id
                WHERE r.task_id = ?
                  AND r.role = 'QA_AGENT'
                ORDER BY r.attempt_no DESC
                LIMIT 1
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "QA_AGENT stage must exist");
                String status = resultSet.getString("status");
                assertTrue(BLOCKING_QA_STAGE_STATUSES.contains(status),
                        "QA_AGENT stage must be a blocking QA failure status");
                String stageRunId = resultSet.getString("id");
                String resultArtifactId = resultSet.getString("result_artifact_id");
                assertFalse(resultArtifactId == null || resultArtifactId.isBlank(),
                        "QA stage must bind result artifact");
                String contentPreview = resultSet.getString("content_preview");
                assertFalse(contentPreview == null || contentPreview.isBlank(),
                        "QA result artifact content_preview must not be blank");
                return new QaStageEvidence(stageRunId, status, resultArtifactId, contentPreview);
            }
        }
    }

    private static QaReportMetrics qaReportMetrics(String contentPreview) throws Exception {
        AgentRoleResultValidation validation = ROLE_RESULT_VALIDATOR.validate("QA_AGENT", contentPreview);
        assertTrue(validation.valid(), "QA report artifact must satisfy role result contract: " + validation.errors());
        JsonNode root = parseJsonObject(contentPreview);
        JsonNode acceptanceResults = root.path("acceptanceResults");
        int acceptanceResultCount = acceptanceResults.isArray() ? acceptanceResults.size() : 0;
        int failedAcceptanceCount = 0;
        int validationCommandCount = 0;
        int validationLogArtifactCount = 0;
        if (acceptanceResults.isArray()) {
            for (JsonNode result : acceptanceResults) {
                if ("FAILED".equalsIgnoreCase(result.path("status").asText("").strip())) {
                    failedAcceptanceCount++;
                }
                if (!result.path("command").asText("").isBlank()) {
                    validationCommandCount++;
                }
                if (!result.path("logArtifactId").asText("").isBlank()) {
                    validationLogArtifactCount++;
                }
            }
        }
        assertTrue(failedAcceptanceCount > 0, "QA report must include at least one failed acceptance result");
        return new QaReportMetrics(
                failedAcceptanceCount,
                acceptanceResultCount,
                validationCommandCount,
                validationLogArtifactCount
        );
    }

    private static JsonNode executionResultJson(JsonNode detail) throws Exception {
        JsonNode value = detail.path("executionResultJson");
        if (value.isObject()) {
            return value;
        }
        String raw = value.asText("{}");
        if (raw.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        return OBJECT_MAPPER.readTree(raw);
    }

    private static JsonNode parseJsonObject(String value) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(value == null ? "{}" : value);
        assertTrue(root != null && root.isObject(), "QA result artifact must be a JSON object");
        return root;
    }

    private static boolean prCreated(JsonNode detail, JsonNode executionResult) {
        return !detail.path("pullRequestUrl").asText("").isBlank()
                || executionResult.path("pullRequestPublication").path("success").asBoolean(false)
                || !executionResult.path("pullRequestPublication").path("pullRequestUrl").asText("").isBlank();
    }

    private static boolean successReportCreated(JsonNode detail, JsonNode executionResult) {
        String taskStatus = detail.path("status").asText("");
        String executionStatus = executionResult.path("status").asText("");
        return "COMPLETED".equals(taskStatus)
                || "SUCCEEDED".equalsIgnoreCase(executionStatus)
                || "PASSED".equalsIgnoreCase(executionStatus);
    }

    private static JsonNode getJson(String baseUrl, String path, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(baseUrl, path))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "GET " + path + " failed with status " + response.statusCode() + ": " + response.body());
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static URI uri(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path);
    }

    private static Duration requestTimeout() {
        String value = System.getProperty("rd.qa-failure.smoke.request-timeout-seconds", "30");
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip())));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(30);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static void assertNoSecretNeedles(
            QaFailureBlockerProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = safe(needle);
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "QA failure blocker evidence must not contain configured secret needle");
            }
        }
    }

    private record QaStageEvidence(
            String stageRunId,
            String status,
            String resultArtifactId,
            String resultContentPreview
    ) {
        QaStageEvidence {
            stageRunId = safe(stageRunId);
            status = safe(status);
            resultArtifactId = safe(resultArtifactId);
            resultContentPreview = safe(resultContentPreview);
        }
    }

    private record QaReportMetrics(
            int failedAcceptanceCount,
            int acceptanceResultCount,
            int validationCommandCount,
            int validationLogArtifactCount
    ) {
    }
}
