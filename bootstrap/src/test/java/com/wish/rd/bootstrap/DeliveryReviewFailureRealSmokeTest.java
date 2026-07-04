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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery review failure production smoke. Disabled by default and validates an existing real task.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=DeliveryReviewFailureRealSmokeTest \
 *   -Drd.integration.delivery-review-failure.enabled=true \
 *   -Drd.delivery-review-failure.smoke.production-evidence=true \
 *   -Drd.delivery-review-failure.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.delivery-review-failure.smoke.environment-id=prod-equivalent-a \
 *   -Drd.delivery-review-failure.smoke.executed-by=qa-runner \
 *   -Drd.delivery-review-failure.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.delivery-review-failure.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.delivery-review-failure.smoke.postgres-user=rd_bot \
 *   -Drd.delivery-review-failure.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.delivery-review-failure.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.delivery-review-failure.smoke.review-artifact-uri=s3://rd-bot-review/delivery-review/rejection.json \
 *   -Drd.delivery-review-failure.smoke.feishu-alert-message-id=om_xxx \
 *   -Drd.delivery-review-failure.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.delivery-review-failure.enabled", matches = "true")
class DeliveryReviewFailureRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesDeliveryReviewRejectedTaskAgainstRealHttpAndPostgres() throws Exception {
        DeliveryReviewFailureProductionAcceptanceReport report =
                DeliveryReviewFailureProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = DeliveryReviewFailureProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("delivery-review-failure", missing, skippedReport);
        }
        DeliveryReviewFailureProductionAcceptanceProfile profile =
                DeliveryReviewFailureProductionAcceptanceProfile.from(properties);
        DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence evidence =
                initialEvidence(profile);
        try {
            JsonNode detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + profile.taskId(), requestTimeout());
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "delivery review failure smoke must validate the configured taskId");
            assertEquals("REJECTED", detail.path("status").asText(""),
                    "delivery review failure task must be REJECTED");
            JsonNode reviewJson = executionResultJson(detail);
            assertFalse(reviewJson.path("approved").asBoolean(true),
                    "delivery review must be rejected");
            String reviewer = firstNonBlank(reviewJson.path("reviewer").asText(""), "DELIVERY_REVIEWER");
            assertEquals("DELIVERY_REVIEWER", reviewer, "reviewer must be DELIVERY_REVIEWER");
            String rejectionReason = firstNonBlank(
                    reviewJson.path("reason").asText(""),
                    detail.path("errorMessage").asText("")
            );
            assertFalse(rejectionReason.isBlank(),
                    "rejection reason must identify why delivery review rejected the task");

            DeliveryFailureExperienceEvidence experience;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                experience = deliveryFailureExperience(connection, profile.taskId());
            }
            boolean pullRequestPublicationAttempted = reviewJson.has("pullRequestPublication")
                    || reviewJson.has("pullRequestUrl");
            boolean prCreated = !detail.path("pullRequestUrl").asText("").isBlank()
                    || !reviewJson.path("pullRequestUrl").asText("").isBlank();
            boolean successReportCreated = "COMPLETED".equals(detail.path("status").asText(""))
                    || reviewJson.path("approved").asBoolean(false);
            boolean blockedBeforePrCreating = !pullRequestPublicationAttempted
                    && !prCreated
                    && !successReportCreated
                    && "REJECTED".equals(detail.path("status").asText(""));
            evidence = new DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence(
                    profile,
                    detail.path("status").asText(""),
                    reviewJson.path("approved").asBoolean(true),
                    reviewDecision(reviewJson),
                    experience.failureExperienceId(),
                    rejectionReason,
                    pullRequestPublicationAttempted,
                    prCreated,
                    successReportCreated,
                    experience.failureReportCreated(),
                    experience.successDeliveryReportExperienceCreated(),
                    blockedBeforePrCreating,
                    rejectionReason.contains("pullRequestUrl")
            );
            assertNoSecretNeedles(profile, List.of(detail.toString(), experience.contentJson()));
            java.nio.file.Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] delivery-review-failure taskId=" + profile.taskId()
                    + " reviewArtifactId=" + experience.failureExperienceId()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            java.nio.file.Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] delivery-review-failure failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return DeliveryReviewFailureProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence initialEvidence(
            DeliveryReviewFailureProductionAcceptanceProfile profile
    ) {
        return new DeliveryReviewFailureProductionAcceptanceReport.DeliveryReviewFailureSmokeEvidence(
                profile,
                "",
                true,
                "",
                "",
                "",
                true,
                true,
                true,
                false,
                true,
                false,
                false
        );
    }

    private static DeliveryFailureExperienceEvidence deliveryFailureExperience(Connection connection, String taskId)
            throws Exception {
        String failureExperienceId = "";
        String contentJson = "";
        boolean failureReportCreated = false;
        boolean successDeliveryReportExperienceCreated = false;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, content_json::text AS content_json
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND experience_type = 'DELIVERY_REPORT'
                  AND failure = true
                  AND reusable = false
                ORDER BY created_at DESC
                LIMIT 1
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    failureExperienceId = resultSet.getString("id");
                    contentJson = safe(resultSet.getString("content_json"));
                    failureReportCreated = true;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS success_count
                FROM rd_experience_entries
                WHERE task_id = ?
                  AND experience_type = 'DELIVERY_REPORT'
                  AND failure = false
                  AND reusable = true
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    successDeliveryReportExperienceCreated = resultSet.getLong("success_count") > 0;
                }
            }
        }
        assertFalse(failureExperienceId.isBlank(), "delivery review failure experience must exist");
        assertFalse(contentJson.isBlank(),
                "delivery review failure experience must preserve rejection evidence");
        return new DeliveryFailureExperienceEvidence(
                failureExperienceId,
                contentJson,
                failureReportCreated,
                successDeliveryReportExperienceCreated
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

    private static String reviewDecision(JsonNode reviewJson) {
        if (reviewJson.path("approved").asBoolean(false)) {
            return "APPROVED";
        }
        String reason = reviewJson.path("reason").asText("");
        if (reason.contains("pullRequestUrl")) {
            return "REJECTED";
        }
        return "FAILED";
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
        String value = System.getProperty("rd.delivery-review-failure.smoke.request-timeout-seconds", "30");
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
            DeliveryReviewFailureProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = safe(needle);
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "delivery review failure evidence must not contain configured secret needle");
            }
        }
    }

    private record DeliveryFailureExperienceEvidence(
            String failureExperienceId,
            String contentJson,
            boolean failureReportCreated,
            boolean successDeliveryReportExperienceCreated
    ) {
        DeliveryFailureExperienceEvidence {
            failureExperienceId = safe(failureExperienceId);
            contentJson = safe(contentJson);
        }
    }
}
