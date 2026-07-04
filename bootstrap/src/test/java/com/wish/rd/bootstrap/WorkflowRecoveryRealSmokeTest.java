package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workflow recovery production smoke. Disabled by default and requires a real restart rehearsal first.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=WorkflowRecoveryRealSmokeTest \
 *   -Drd.integration.workflow-recovery.enabled=true \
 *   -Drd.workflow.recovery.smoke.production-evidence=true \
 *   -Drd.workflow.recovery.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.workflow.recovery.smoke.environment-id=prod-equivalent-a \
 *   -Drd.workflow.recovery.smoke.executed-by=qa-runner \
 *   -Drd.workflow.recovery.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.workflow.recovery.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.workflow.recovery.smoke.postgres-user=rd_bot \
 *   -Drd.workflow.recovery.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.workflow.recovery.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.workflow.recovery.smoke.stage-run-count-before-restart=2 \
 *   -Drd.workflow.recovery.smoke.stage-event-count-before-restart=5 \
 *   -Drd.workflow.recovery.smoke.retry-attempt-count=1 \
 *   -Drd.workflow.recovery.smoke.retained-retry-artifact-count=1 \
 *   -Drd.workflow.recovery.smoke.startup-log-evidence-uri=s3://rd-bot-qa/startup.log \
 *   -Drd.workflow.recovery.smoke.database-snapshot-evidence-uri=s3://rd-bot-qa/recovery-snapshot.json \
 *   -Drd.workflow.recovery.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.workflow-recovery.enabled", matches = "true")
class WorkflowRecoveryRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesRecoveredWorkflowStateAgainstRealHttpAndPostgres() throws Exception {
        WorkflowRecoveryProductionAcceptanceReport report =
                WorkflowRecoveryProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = WorkflowRecoveryProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("workflow-recovery", missing, skippedReport);
        }
        WorkflowRecoveryProductionAcceptanceProfile profile =
                WorkflowRecoveryProductionAcceptanceProfile.from(properties);
        WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence evidence =
                initialEvidence(profile);
        try {
            Duration timeout = requestTimeout();
            JsonNode detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + profile.taskId(), timeout);
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "workflow recovery smoke must validate the configured taskId");
            assertEquals("COMPLETED", detail.path("status").asText(""),
                    "workflow recovery smoke must validate the recovered task after completion");

            JsonNode timeline = getJson(
                    profile.baseUrl(),
                    "/admin/rd-tasks/" + profile.taskId() + "/timeline",
                    timeout
            );
            assertTrue(timeline.isArray(), "timeline endpoint must return an array");
            List<String> statuses = timelineStatuses(timeline);
            assertTrue(statuses.contains("EXECUTING"), "timeline must contain EXECUTING after recovery");
            assertTrue(statuses.contains("COMPLETED"), "timeline must contain COMPLETED after recovery");

            int stageRunCountAfterRestart;
            int stageEventCountAfterRestart;
            int duplicateSuccessfulStageCount;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                stageRunCountAfterRestart = stageRunCount(connection, profile.taskId());
                stageEventCountAfterRestart = stageEventCount(connection, profile.taskId());
                duplicateSuccessfulStageCount = duplicateSuccessfulStageCount(connection, profile.taskId());
            }
            boolean deliveryReviewApprovedBeforePrCreating =
                    deliveryReviewApproved(detail) && firstIndex(statuses, "VALIDATING") < firstIndex(statuses, "PR_CREATING");
            evidence = new WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence(
                    profile,
                    stageRunCountAfterRestart,
                    stageEventCountAfterRestart,
                    duplicateSuccessfulStageCount,
                    deliveryReviewApprovedBeforePrCreating,
                    statuses
            );
            assertNoSecretNeedles(profile, List.of(detail.toString(), timeline.toString()));
            java.nio.file.Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] workflow-recovery taskId=" + profile.taskId()
                    + " stageRunsAfterRestart=" + stageRunCountAfterRestart
                    + " stageEventsAfterRestart=" + stageEventCountAfterRestart
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            java.nio.file.Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] workflow-recovery failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return WorkflowRecoveryProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence initialEvidence(
            WorkflowRecoveryProductionAcceptanceProfile profile
    ) {
        return new WorkflowRecoveryProductionAcceptanceReport.WorkflowRecoverySmokeEvidence(
                profile,
                0,
                0,
                0,
                false,
                List.of()
        );
    }

    private static int stageRunCount(Connection connection, String taskId) throws Exception {
        return count(connection, """
                SELECT COUNT(*) AS total
                FROM rd_agent_stage_runs
                WHERE task_id = ?
                """, taskId);
    }

    private static int stageEventCount(Connection connection, String taskId) throws Exception {
        return count(connection, """
                SELECT COUNT(*) AS total
                FROM rd_agent_stage_events
                WHERE task_id = ?
                """, taskId);
    }

    private static int duplicateSuccessfulStageCount(Connection connection, String taskId) throws Exception {
        return count(connection, """
                SELECT COUNT(*) AS total
                FROM (
                    SELECT role
                    FROM rd_agent_stage_runs
                    WHERE task_id = ?
                      AND status = 'SUCCEEDED'
                    GROUP BY role
                    HAVING COUNT(*) > 1
                ) duplicated_successful_roles
                """, taskId);
    }

    private static int count(Connection connection, String sql, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("total");
                }
                return 0;
            }
        }
    }

    private static boolean deliveryReviewApproved(JsonNode detail) throws Exception {
        JsonNode executionResult = executionResultJson(detail);
        return executionResult.path("deliveryReview").path("approved").asBoolean(false);
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

    private static List<String> timelineStatuses(JsonNode timeline) {
        ArrayList<String> statuses = new ArrayList<>();
        timeline.forEach(event -> {
            String status = event.path("status").asText("").strip();
            if (!status.isBlank()) {
                statuses.add(status);
            }
        });
        return List.copyOf(statuses);
    }

    private static int firstIndex(List<String> statuses, String status) {
        int index = statuses.indexOf(status);
        return index < 0 ? Integer.MAX_VALUE : index;
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
        String value = System.getProperty("rd.workflow.recovery.smoke.request-timeout-seconds", "30");
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip())));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(30);
        }
    }

    private static void assertNoSecretNeedles(
            WorkflowRecoveryProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = needle == null ? "" : needle.strip();
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "workflow recovery evidence must not contain configured secret needle");
            }
        }
    }
}
