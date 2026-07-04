package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker coding production smoke. Disabled by default and validates an existing real task.
 *
 * <p>Example:
 * <pre>
 * ./mvnw -pl bootstrap -am -Dtest=DockerCodingRealSmokeTest \
 *   -Drd.integration.docker-coding.enabled=true \
 *   -Drd.docker-coding.smoke.production-evidence=true \
 *   -Drd.docker-coding.smoke.rd-bot-version=0.1.0-smoke \
 *   -Drd.docker-coding.smoke.environment-id=prod-equivalent-a \
 *   -Drd.docker-coding.smoke.executed-by=qa-runner \
 *   -Drd.docker-coding.smoke.base-url=http://127.0.0.1:8080 \
 *   -Drd.docker-coding.smoke.postgres-url=jdbc:postgresql://127.0.0.1:5432/rd_bot \
 *   -Drd.docker-coding.smoke.postgres-user=rd_bot \
 *   -Drd.docker-coding.smoke.postgres-password=$RD_BOT_POSTGRES_PASSWORD \
 *   -Drd.docker-coding.smoke.repository-url=https://github.com/acme/rd-bot-smoke.git \
 *   -Drd.docker-coding.smoke.task-id="$RD_BOT_ACCEPTANCE_TASK_ID" \
 *   -Drd.docker-coding.smoke.patch-artifact-uri=s3://rd-bot-qa/docker-coding/patch.diff \
 *   -Drd.docker-coding.smoke.result-artifact-uri=s3://rd-bot-qa/docker-coding/result.json \
 *   -Drd.docker-coding.smoke.test-log-artifact-uri=s3://rd-bot-qa/docker-coding/test.log \
 *   -Drd.docker-coding.smoke.docker-metadata-artifact-uri=s3://rd-bot-qa/docker-coding/docker-metadata.json \
 *   -Drd.docker-coding.smoke.secret-scan-needles=<secret-scan-needles> \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 */
@EnabledIfSystemProperty(named = "rd.integration.docker-coding.enabled", matches = "true")
class DockerCodingRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesDockerCodingStageAgainstRealHttpAndPostgres() throws Exception {
        DockerCodingProductionAcceptanceReport report =
                DockerCodingProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = DockerCodingProductionAcceptanceProfile.systemProperties();
        List<String> missing = missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            java.nio.file.Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("docker-coding", missing, skippedReport);
        }
        DockerCodingProductionAcceptanceProfile profile =
                DockerCodingProductionAcceptanceProfile.from(properties);
        DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence evidence = initialEvidence(profile);
        try {
            JsonNode detail = getJson(profile.baseUrl(), "/admin/rd-tasks/" + profile.taskId(), requestTimeout());
            assertEquals(profile.taskId(), detail.path("taskId").asText(""),
                    "Docker coding smoke must validate the configured taskId");

            CodingStageEvidence stage;
            Map<String, ArtifactEvidence> artifacts;
            try (Connection connection = DriverManager.getConnection(
                    profile.postgresUrl(),
                    profile.postgresUser(),
                    profile.postgresPassword()
            )) {
                stage = codingStage(connection, profile.taskId());
                artifacts = stageArtifacts(connection, profile.taskId(), stage.stageRunId());
            }
            ArtifactEvidence patchArtifact = requiredArtifact(artifacts, "PATCH_DIFF");
            ArtifactEvidence resultArtifact = requiredArtifact(artifacts, "RESULT_JSON");
            ArtifactEvidence testLogArtifact = requiredArtifact(artifacts, "TEST_LOG");
            ArtifactEvidence dockerMetadataArtifact = requiredArtifact(artifacts, "DOCKER_METADATA");
            assertEquals(stage.resultArtifactId(), resultArtifact.artifactId(),
                    "CODING_AGENT stage result_artifact_id must reference the RESULT_JSON artifact");

            JsonNode resultJson = parseJsonObject(stage.resultContentPreview());
            JsonNode dockerMetadata = mergedDockerMetadata(resultJson, dockerMetadataArtifact.contentPreview());
            CodingResultMetrics metrics = codingResultMetrics(resultJson, dockerMetadata);
            evidence = new DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence(
                    profile,
                    stage.stageRunId(),
                    stage.status(),
                    metrics.dockerImage(),
                    metrics.containerId(),
                    metrics.workspacePath(),
                    metrics.commitHash(),
                    patchArtifact.artifactId(),
                    resultArtifact.artifactId(),
                    testLogArtifact.artifactId(),
                    dockerMetadataArtifact.artifactId(),
                    metrics.changedFileCount(),
                    metrics.validationCommand(),
                    metrics.validationExitCode(),
                    metrics.testsRun(),
                    metrics.testsFailed(),
                    !patchArtifact.contentPreview().isBlank(),
                    metrics.resultJsonValidated(),
                    metrics.realDockerRun()
            );
            assertNoSecretNeedles(profile, List.of(
                    detail.toString(),
                    stage.resultContentPreview(),
                    patchArtifact.contentPreview(),
                    testLogArtifact.contentPreview(),
                    dockerMetadataArtifact.contentPreview()
            ));
            java.nio.file.Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] docker-coding taskId=" + profile.taskId()
                    + " stageRunId=" + stage.stageRunId()
                    + " testsRun=" + metrics.testsRun()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            java.nio.file.Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] docker-coding failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    static List<String> missingRequiredProperties(Map<String, String> properties) {
        return DockerCodingProductionAcceptanceProfile.missingRequiredProperties(properties);
    }

    private static DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence initialEvidence(
            DockerCodingProductionAcceptanceProfile profile
    ) {
        return new DockerCodingProductionAcceptanceReport.DockerCodingSmokeEvidence(
                profile,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                "",
                0,
                0,
                0,
                false,
                false,
                false
        );
    }

    private static CodingStageEvidence codingStage(Connection connection, String taskId) throws Exception {
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
                  AND r.role = 'CODING_AGENT'
                ORDER BY r.attempt_no DESC
                LIMIT 1
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "CODING_AGENT stage must exist");
                String status = resultSet.getString("status");
                assertEquals("SUCCEEDED", status, "CODING_AGENT stage must be SUCCEEDED");
                String stageRunId = resultSet.getString("id");
                String resultArtifactId = resultSet.getString("result_artifact_id");
                assertFalse(resultArtifactId == null || resultArtifactId.isBlank(),
                        "CODING_AGENT stage must bind result artifact");
                String contentPreview = resultSet.getString("content_preview");
                assertFalse(contentPreview == null || contentPreview.isBlank(),
                        "CODING_AGENT result artifact content_preview must not be blank");
                return new CodingStageEvidence(stageRunId, status, resultArtifactId, contentPreview);
            }
        }
    }

    private static Map<String, ArtifactEvidence> stageArtifacts(
            Connection connection,
            String taskId,
            String stageRunId
    ) throws Exception {
        Map<String, ArtifactEvidence> artifacts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, artifact_type, artifact_uri, content_preview, metadata_json::text AS metadata_json
                FROM rd_agent_stage_artifacts
                WHERE task_id = ?
                  AND role = 'CODING_AGENT'
                  AND stage_run_id = ?
                """)) {
            statement.setLong(1, Long.parseLong(taskId));
            statement.setLong(2, Long.parseLong(stageRunId));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ArtifactEvidence artifact = new ArtifactEvidence(
                            resultSet.getString("id"),
                            resultSet.getString("artifact_type"),
                            resultSet.getString("artifact_uri"),
                            resultSet.getString("content_preview"),
                            resultSet.getString("metadata_json")
                    );
                    artifacts.putIfAbsent(artifact.artifactType(), artifact);
                }
            }
        }
        return Map.copyOf(artifacts);
    }

    private static ArtifactEvidence requiredArtifact(Map<String, ArtifactEvidence> artifacts, String artifactType) {
        ArtifactEvidence artifact = artifacts.get(artifactType);
        assertTrue(artifact != null, "CODING_AGENT stage must persist artifact type " + artifactType);
        assertFalse(artifact.artifactId().isBlank(), artifactType + " artifact id must not be blank");
        if (!"RESULT_JSON".equals(artifactType)) {
            assertFalse(artifact.contentPreview().isBlank() && artifact.artifactUri().isBlank(),
                    artifactType + " artifact must expose content_preview or artifact_uri");
        }
        return artifact;
    }

    private static CodingResultMetrics codingResultMetrics(JsonNode resultJson, JsonNode dockerMetadata) {
        int changedFileCount = stringArrayOrCsvCount(resultJson.path("changedFiles"));
        String validationCommand = firstCommand(resultJson);
        int testsRun = Math.max(1, stringArrayOrCsvCount(firstNonMissing(
                resultJson.path("testCommands"),
                resultJson.path("testMetadata").path("testCommands")
        )));
        int testsFailed = intValue(firstNonMissing(
                resultJson.path("testsFailed"),
                resultJson.path("testMetadata").path("testsFailed")
        ), 0);
        int validationExitCode = intValue(firstNonMissing(
                resultJson.path("validationExitCode"),
                dockerMetadata.path("exitCode")
        ), 1);
        String testStatus = firstNonBlank(
                resultJson.path("testStatus").asText(""),
                resultJson.path("testMetadata").path("testStatus").asText("")
        );
        boolean resultValidated = "SUCCESS".equalsIgnoreCase(resultJson.path("status").asText(""))
                && !resultJson.path("summary").asText("").isBlank()
                && !resultJson.path("prBody").asText("").isBlank()
                && changedFileCount > 0
                && !validationCommand.isBlank()
                && "PASSED".equalsIgnoreCase(testStatus)
                && testsFailed == 0;
        String dockerImage = firstNonBlank(
                dockerMetadata.path("image").asText(""),
                dockerMetadata.path("dockerImage").asText("")
        );
        String containerId = firstNonBlank(
                dockerMetadata.path("containerId").asText(""),
                dockerMetadata.path("containerName").asText(""),
                dockerMetadata.path("runner.containerId").asText("")
        );
        String workspacePath = firstNonBlank(
                dockerMetadata.path("workspacePath").asText(""),
                dockerMetadata.path("repoDirectory").asText(""),
                dockerMetadata.path("runner.workspacePath").asText(""),
                workspacePathFromOutputArtifacts(dockerMetadata.path("outputArtifactPaths").asText(""))
        );
        String commitHash = firstNonBlank(
                dockerMetadata.path("commitHash").asText(""),
                dockerMetadata.path("gitCommitHash").asText(""),
                dockerMetadata.path("headCommit").asText(""),
                dockerMetadata.path("runner.commitHash").asText(""),
                gitHeadCommit(workspacePath)
        );
        return new CodingResultMetrics(
                changedFileCount,
                validationCommand,
                validationExitCode,
                testsRun,
                testsFailed,
                resultValidated,
                !dockerImage.isBlank() && !containerId.isBlank(),
                dockerImage,
                containerId,
                workspacePath,
                commitHash
        );
    }

    private static String workspacePathFromOutputArtifacts(String outputArtifactPaths) {
        if (outputArtifactPaths == null || outputArtifactPaths.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(outputArtifactPaths.split(","))
                .map(String::strip)
                .filter(path -> path.startsWith("file://") && path.contains("/output/"))
                .map(path -> path.substring("file://".length(), path.indexOf("/output/")) + "/repo")
                .findFirst()
                .orElse("");
    }

    private static String gitHeadCommit(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return "";
        }
        try {
            Process process = new ProcessBuilder("git", "-C", workspacePath, "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JsonNode mergedDockerMetadata(JsonNode resultJson, String dockerMetadataPreview) throws Exception {
        JsonNode fromResult = resultJson.path("dockerMetadata");
        if (fromResult.isObject()) {
            return fromResult;
        }
        JsonNode fromArtifact = parseJsonObject(dockerMetadataPreview);
        return fromArtifact.isObject() ? fromArtifact : OBJECT_MAPPER.createObjectNode();
    }

    private static JsonNode parseJsonObject(String value) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(value == null || value.isBlank() ? "{}" : value);
        assertTrue(root != null && root.isObject(), "expected JSON object");
        return root;
    }

    private static JsonNode firstNonMissing(JsonNode first, JsonNode second) {
        if (first != null && !first.isMissingNode() && !first.isNull()) {
            return first;
        }
        return second;
    }

    private static int stringArrayOrCsvCount(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (value.isArray()) {
            int count = 0;
            for (JsonNode entry : value) {
                if (!entry.asText("").isBlank()) {
                    count++;
                }
            }
            return count;
        }
        String raw = value.asText("");
        if (raw.isBlank()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .count();
    }

    private static String firstCommand(JsonNode resultJson) {
        JsonNode commandNode = firstNonMissing(
                resultJson.path("testCommands"),
                resultJson.path("testMetadata").path("testCommands")
        );
        if (commandNode.isArray()) {
            for (JsonNode command : commandNode) {
                String value = safe(command.asText(""));
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
        String raw = commandNode.asText("");
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(item -> !item.isBlank())
                .findFirst()
                .orElse("");
    }

    private static int intValue(JsonNode value, int defaultValue) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        if (value.canConvertToInt()) {
            return value.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(value.asText("").strip());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
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
        String value = System.getProperty("rd.docker-coding.smoke.request-timeout-seconds", "30");
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip())));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(30);
        }
    }

    private static void assertNoSecretNeedles(
            DockerCodingProductionAcceptanceProfile profile,
            List<String> values
    ) {
        for (String needle : profile.secretScanNeedles()) {
            String normalized = safe(needle);
            if (normalized.isBlank()) {
                continue;
            }
            for (String value : values) {
                assertFalse(value != null && value.contains(normalized),
                        "Docker coding evidence must not contain configured secret needle");
            }
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

    private record CodingStageEvidence(
            String stageRunId,
            String status,
            String resultArtifactId,
            String resultContentPreview
    ) {
        CodingStageEvidence {
            stageRunId = safe(stageRunId);
            status = safe(status);
            resultArtifactId = safe(resultArtifactId);
            resultContentPreview = safe(resultContentPreview);
        }
    }

    private record ArtifactEvidence(
            String artifactId,
            String artifactType,
            String artifactUri,
            String contentPreview,
            String metadataJson
    ) {
        ArtifactEvidence {
            artifactId = safe(artifactId);
            artifactType = safe(artifactType);
            artifactUri = safe(artifactUri);
            contentPreview = safe(contentPreview);
            metadataJson = safe(metadataJson);
        }
    }

    private record CodingResultMetrics(
            int changedFileCount,
            String validationCommand,
            int validationExitCode,
            int testsRun,
            int testsFailed,
            boolean resultJsonValidated,
            boolean realDockerRun,
            String dockerImage,
            String containerId,
            String workspacePath,
            String commitHash
    ) {
        CodingResultMetrics {
            changedFileCount = Math.max(changedFileCount, 0);
            validationCommand = safe(validationCommand);
            validationExitCode = Math.max(validationExitCode, 0);
            testsRun = Math.max(testsRun, 0);
            testsFailed = Math.max(testsFailed, 0);
            dockerImage = safe(dockerImage);
            containerId = safe(containerId);
            workspacePath = safe(workspacePath);
            commitHash = safe(commitHash);
        }
    }
}
