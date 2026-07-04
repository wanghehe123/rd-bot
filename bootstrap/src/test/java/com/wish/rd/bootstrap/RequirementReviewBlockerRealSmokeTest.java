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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement review blocker production smoke. Disabled by default and validates an existing real task.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=RequirementReviewBlockerRealSmokeTest \
 *   -Drd.integration.requirement-review-blocker.enabled=true \
 *   -Drd.requirement-review.smoke.production-evidence=true \
 *   -Drd.requirement-review.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.requirement-review.smoke.environment-id=prod-equivalent-a \
 *   -Drd.requirement-review.smoke.executed-by=qa-runner \
 *   -Drd.requirement-review.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.requirement-review.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.requirement-review.smoke.postgres-user=rd_bot \
 *   -Drd.requirement-review.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.requirement-review.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.requirement-review.smoke.review-artifact-uri=s3://rd-bot-review/requirement-review/blocker.json \
 *   -Drd.requirement-review.smoke.feishu-alert-message-id=om_xxx \
 *   -Drd.requirement-review.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.requirement-review-blocker.enabled", matches = "true")
class RequirementReviewBlockerRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> DOWNSTREAM_ROLES = List.of(
            "SOLUTION_ARCHITECT",
            "CODING_AGENT",
            "QA_AGENT"
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

    @Test
    void validatesRequirementReviewBlockedTaskAgainstRealHttpAndPostgres() throws Exception {
        RequirementReviewBlockerProductionAcceptanceReport report =
                RequirementReviewBlockerProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = RequirementReviewBlockerProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("requirement-review-blocker", missing, skippedReport);
        }
        RequirementReviewBlockerProductionAcceptanceProfile profile =
                RequirementReviewBlockerProductionAcceptanceProfile.from(properties);
        RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence evidence =
                initialEvidence(profile);
        try {
            JsonNode detail = getJson(
                    profile.baseUrl(),
                    "/admin/rd-tasks/" + profile.taskId(),
                    requestTimeout()
            );
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "requirement review blocker smoke must validate the configured taskId");
            assertTrue(BLOCKING_TASK_STATUSES.contains(detail.path("status").asText("")),
                    "blocked requirement review task must be a terminal blocking status");
            JsonNode executionResult = executionResultJson(detail);
            assertTrue(BLOCKING_EXECUTION_STATUSES.contains(executionResult.path("status").asText("")),
                    "executionResultJson.status must be a terminal blocking status");

            RequirementReviewStageEvidence stage;
            DownstreamDispatchEvidence downstream;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                stage = reviewerStage(connection, profile.taskId());
                downstream = downstreamDispatchEvidence(connection, profile.taskId());
            }
            JsonNode reviewJson = parseJsonObject(stage.resultContentPreview());
            String reviewDecision = reviewDecision(reviewJson);
            List<String> missingInformation = missingInformation(reviewJson);
            String errorMessage = firstNonBlank(
                    detail.path("errorMessage").asText(""),
                    stage.errorMessage(),
                    reviewJson.path("errorMessage").asText(""),
                    reviewJson.path("reason").asText(""),
                    reviewJson.path("summary").asText("")
            );
            evidence = new RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence(
                    profile,
                    stage.stageRunId(),
                    detail.path("status").asText(""),
                    executionResult.path("status").asText(""),
                    reviewDecision,
                    stage.resultArtifactId(),
                    errorMessage,
                    missingInformation,
                    downstream.pendingRoles(),
                    downstream.dispatched()
            );
            assertNoSecretNeedles(profile, List.of(detail.toString(), stage.resultContentPreview()));
            java.nio.file.Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] requirement-review-blocker taskId=" + profile.taskId()
                    + " stageRunId=" + stage.stageRunId()
                    + " decision=" + reviewDecision
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            java.nio.file.Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] requirement-review-blocker failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return RequirementReviewBlockerProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence
    initialEvidence(RequirementReviewBlockerProductionAcceptanceProfile profile) {
        return new RequirementReviewBlockerProductionAcceptanceReport.RequirementReviewBlockerSmokeEvidence(
                profile,
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                true
        );
    }

    private static RequirementReviewStageEvidence reviewerStage(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    r.id,
                    r.status,
                    r.result_artifact_id,
                    r.error_message,
                    a.content_preview
                FROM rd_agent_stage_runs r
                LEFT JOIN rd_agent_stage_artifacts a
                  ON a.id = r.result_artifact_id
                WHERE r.task_id = ?
                  AND r.role = 'REQUIREMENT_REVIEWER'
                ORDER BY r.attempt_no DESC
                LIMIT 1
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "REQUIREMENT_REVIEWER stage must exist");
                String status = resultSet.getString("status");
                assertEquals("FAILED_NEEDS_HUMAN", status,
                        "REQUIREMENT_REVIEWER stage must be FAILED_NEEDS_HUMAN");
                String stageRunId = resultSet.getString("id");
                String resultArtifactId = resultSet.getString("result_artifact_id");
                assertFalse(resultArtifactId == null || resultArtifactId.isBlank(),
                        "reviewer stage must bind result artifact");
                String contentPreview = resultSet.getString("content_preview");
                assertFalse(contentPreview == null || contentPreview.isBlank(),
                        "reviewer result artifact content_preview must not be blank");
                return new RequirementReviewStageEvidence(
                        stageRunId,
                        resultArtifactId,
                        safe(resultSet.getString("error_message")),
                        contentPreview
                );
            }
        }
    }

    private static DownstreamDispatchEvidence downstreamDispatchEvidence(Connection connection, String taskId)
            throws Exception {
        List<String> pendingRoles = new ArrayList<>();
        boolean dispatched = false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, status, prompt_artifact_id, result_artifact_id
                FROM rd_agent_stage_runs
                WHERE task_id = ?
                  AND role IN ('SOLUTION_ARCHITECT', 'CODING_AGENT', 'QA_AGENT')
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                java.util.Map<String, DownstreamStageRow> rows = new java.util.LinkedHashMap<>();
                while (resultSet.next()) {
                    rows.put(resultSet.getString("role"), new DownstreamStageRow(
                            resultSet.getString("status"),
                            resultSet.getString("prompt_artifact_id"),
                            resultSet.getString("result_artifact_id")
                    ));
                }
                for (String role : DOWNSTREAM_ROLES) {
                    DownstreamStageRow row = rows.get(role);
                    if (row == null) {
                        pendingRoles.add(role);
                        continue;
                    }
                    boolean roleDispatched = !"PENDING".equals(row.status())
                            || !safe(row.promptArtifactId()).isBlank()
                            || !safe(row.resultArtifactId()).isBlank();
                    if (roleDispatched) {
                        dispatched = true;
                    } else {
                        pendingRoles.add(role);
                    }
                }
            }
        }
        return new DownstreamDispatchEvidence(List.copyOf(pendingRoles), dispatched);
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
        assertTrue(root != null && root.isObject(), "requirement review result artifact must be a JSON object");
        return root;
    }

    private static String reviewDecision(JsonNode reviewJson) {
        return firstNonBlank(
                code(reviewJson.path("decision")),
                code(reviewJson.path("status")),
                code(reviewJson.path("feasibility"))
        );
    }

    private static List<String> missingInformation(JsonNode reviewJson) {
        List<String> values = values(reviewJson.path("missingInformation"));
        if (!values.isEmpty()) {
            return values;
        }
        values = values(reviewJson.path("missingInfo"));
        if (!values.isEmpty()) {
            return values;
        }
        values = values(reviewJson.path("missingFields"));
        if (!values.isEmpty()) {
            return values;
        }
        values = values(reviewJson.path("questions"));
        if (!values.isEmpty()) {
            return values;
        }
        return missingInformationFromSummary(reviewJson.path("summary").asText(""));
    }

    private static List<String> values(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").strip();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static List<String> missingInformationFromSummary(String summary) {
        String safeSummary = summary == null ? "" : summary;
        if (safeSummary.isBlank()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        boolean inMissingSection = false;
        for (String line : safeSummary.split("\\R")) {
            String normalized = line == null ? "" : line.strip();
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.contains("缺失信息") || normalized.contains("缺失项")) {
                inMissingSection = true;
                continue;
            }
            if (inMissingSection && normalized.startsWith("## ")) {
                break;
            }
            if (inMissingSection && (normalized.startsWith("- ") || normalized.startsWith("* "))) {
                String value = normalized.substring(2)
                        .replace("**", "")
                        .replace('：', ':')
                        .strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private static String code(JsonNode node) {
        return node == null ? "" : node.asText("").strip().toUpperCase(Locale.ROOT).replace('-', '_');
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
        String value = System.getProperty("rd.requirement-review.smoke.request-timeout-seconds", "30");
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
            RequirementReviewBlockerProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = safe(needle);
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "requirement review blocker evidence must not contain configured secret needle");
            }
        }
    }

    private record RequirementReviewStageEvidence(
            String stageRunId,
            String resultArtifactId,
            String errorMessage,
            String resultContentPreview
    ) {
        RequirementReviewStageEvidence {
            stageRunId = safe(stageRunId);
            resultArtifactId = safe(resultArtifactId);
            errorMessage = safe(errorMessage);
            resultContentPreview = safe(resultContentPreview);
        }
    }

    private record DownstreamDispatchEvidence(List<String> pendingRoles, boolean dispatched) {
        DownstreamDispatchEvidence {
            pendingRoles = pendingRoles == null ? List.of() : List.copyOf(pendingRoles);
        }
    }

    private record DownstreamStageRow(String status, String promptArtifactId, String resultArtifactId) {
        DownstreamStageRow {
            status = safe(status);
            promptArtifactId = safe(promptArtifactId);
            resultArtifactId = safe(resultArtifactId);
        }
    }
}
