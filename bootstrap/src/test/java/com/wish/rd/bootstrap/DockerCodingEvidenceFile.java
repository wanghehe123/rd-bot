package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Structured evidence for a real Docker-backed CODING_AGENT rehearsal.
 */
record DockerCodingEvidenceFile(
        boolean validated,
        String taskId,
        String stageRunId,
        String repositoryUrl,
        String dockerImage,
        String containerId,
        String workspacePath,
        String commitHash,
        String patchArtifactId,
        String resultArtifactId,
        String testLogArtifactId,
        String dockerMetadataArtifactId,
        String patchArtifactUri,
        String resultArtifactUri,
        String testLogArtifactUri,
        String dockerMetadataArtifactUri,
        int changedFileCount,
        String validationCommand,
        int validationExitCode,
        int testsRun,
        int testsFailed,
        boolean patchNonEmpty,
        boolean resultJsonValidated,
        boolean realDockerRun
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    DockerCodingEvidenceFile {
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        repositoryUrl = safe(repositoryUrl);
        dockerImage = safe(dockerImage);
        containerId = safe(containerId);
        workspacePath = safe(workspacePath);
        commitHash = safe(commitHash);
        patchArtifactId = safe(patchArtifactId);
        resultArtifactId = safe(resultArtifactId);
        testLogArtifactId = safe(testLogArtifactId);
        dockerMetadataArtifactId = safe(dockerMetadataArtifactId);
        patchArtifactUri = safe(patchArtifactUri);
        resultArtifactUri = safe(resultArtifactUri);
        testLogArtifactUri = safe(testLogArtifactUri);
        dockerMetadataArtifactUri = safe(dockerMetadataArtifactUri);
        changedFileCount = Math.max(changedFileCount, 0);
        validationCommand = safe(validationCommand);
        validationExitCode = Math.max(validationExitCode, 0);
        testsRun = Math.max(testsRun, 0);
        testsFailed = Math.max(testsFailed, 0);
    }

    static DockerCodingEvidenceFile empty() {
        return new DockerCodingEvidenceFile(
                false,
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

    static DockerCodingEvidenceFile from(MultiAgentProductionAcceptanceProfile profile) {
        if (profile == null || profile.dockerCodingEvidenceJson().isBlank()) {
            return empty();
        }
        Path path = Path.of(profile.dockerCodingEvidenceJson()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            int changedFileCount = root.path("changedFileCount").asInt(0);
            int validationExitCode = root.path("validationExitCode").asInt(-1);
            int testsRun = root.path("testsRun").asInt(0);
            int testsFailed = root.path("testsFailed").asInt(-1);
            boolean sameRun = profile.rdBotVersion().equals(root.path("rdBotVersion").asText(""))
                    && profile.environmentId().equals(root.path("environmentId").asText(""))
                    && profile.executedBy().equals(root.path("executedBy").asText(""));
            boolean validated = root.path("dockerCodingEvidenceValidated").asBoolean(false)
                    && sameRun
                    && profile.repositoryUrl().equals(root.path("repositoryUrl").asText(""))
                    && "CODING_AGENT".equals(root.path("role").asText(""))
                    && !root.path("taskId").asText("").isBlank()
                    && !root.path("stageRunId").asText("").isBlank()
                    && !root.path("dockerImage").asText("").isBlank()
                    && !root.path("containerId").asText("").isBlank()
                    && !root.path("workspacePath").asText("").isBlank()
                    && fullGitCommitHash(root.path("commitHash").asText(""))
                    && !root.path("patchArtifactId").asText("").isBlank()
                    && !root.path("resultArtifactId").asText("").isBlank()
                    && !root.path("testLogArtifactId").asText("").isBlank()
                    && !root.path("dockerMetadataArtifactId").asText("").isBlank()
                    && artifactIdsAreDistinct(root)
                    && artifactUrisAreProduction(root)
                    && changedFileCount > 0
                    && !root.path("validationCommand").asText("").isBlank()
                    && validationExitCode == 0
                    && testsRun > 0
                    && testsFailed == 0
                    && root.path("patchNonEmpty").asBoolean(false)
                    && root.path("resultJsonValidated").asBoolean(false)
                    && root.path("realDockerRun").asBoolean(false);
            return validated ? new DockerCodingEvidenceFile(
                    true,
                    root.path("taskId").asText(""),
                    root.path("stageRunId").asText(""),
                    root.path("repositoryUrl").asText(""),
                    root.path("dockerImage").asText(""),
                    root.path("containerId").asText(""),
                    root.path("workspacePath").asText(""),
                    root.path("commitHash").asText(""),
                    root.path("patchArtifactId").asText(""),
                    root.path("resultArtifactId").asText(""),
                    root.path("testLogArtifactId").asText(""),
                    root.path("dockerMetadataArtifactId").asText(""),
                    root.path("patchArtifactUri").asText(""),
                    root.path("resultArtifactUri").asText(""),
                    root.path("testLogArtifactUri").asText(""),
                    root.path("dockerMetadataArtifactUri").asText(""),
                    changedFileCount,
                    root.path("validationCommand").asText(""),
                    validationExitCode,
                    testsRun,
                    testsFailed,
                    true,
                    true,
                    true
            ) : empty();
        } catch (RuntimeException | java.io.IOException ignored) {
            return empty();
        }
    }

    MultiAgentProductionAcceptanceReport.DockerCodingEvidence toReportEvidence() {
        if (!validated) {
            return MultiAgentProductionAcceptanceReport.DockerCodingEvidence.empty();
        }
        return new MultiAgentProductionAcceptanceReport.DockerCodingEvidence(
                true,
                taskId,
                stageRunId,
                repositoryUrl,
                dockerImage,
                containerId,
                workspacePath,
                commitHash,
                patchArtifactId,
                resultArtifactId,
                testLogArtifactId,
                dockerMetadataArtifactId,
                patchArtifactUri,
                resultArtifactUri,
                testLogArtifactUri,
                dockerMetadataArtifactUri,
                changedFileCount,
                validationCommand,
                validationExitCode,
                testsRun,
                testsFailed,
                patchNonEmpty,
                resultJsonValidated,
                realDockerRun
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean artifactIdsAreDistinct(JsonNode root) {
        List<String> artifactIds = List.of(
                safe(root.path("patchArtifactId").asText("")),
                safe(root.path("resultArtifactId").asText("")),
                safe(root.path("testLogArtifactId").asText("")),
                safe(root.path("dockerMetadataArtifactId").asText(""))
        );
        return artifactIds.stream().noneMatch(String::isBlank)
                && artifactIds.stream().distinct().count() == artifactIds.size();
    }

    private static boolean artifactUrisAreProduction(JsonNode root) {
        List<String> artifactUris = List.of(
                safe(root.path("patchArtifactUri").asText("")),
                safe(root.path("resultArtifactUri").asText("")),
                safe(root.path("testLogArtifactUri").asText("")),
                safe(root.path("dockerMetadataArtifactUri").asText(""))
        );
        return artifactUris.stream().allMatch(DockerCodingEvidenceFile::productionArtifactUri)
                && artifactUris.stream().distinct().count() == artifactUris.size();
    }

    private static boolean productionArtifactUri(String value) {
        return ProductionEvidenceUris.isProductionArtifactUri(value);
    }

    private static boolean fullGitCommitHash(String commitHash) {
        return safe(commitHash).matches("([0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
    }
}
