package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.ContainerSecurityPolicy;

class ContainerRunnerContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void requestRejectsBlankRequiredContainerFields() {
        Path outputDirectory = temporaryDirectory.resolve("output");

        assertThrows(IllegalArgumentException.class, () -> request(" ", "claude-code:local", outputDirectory));
        assertThrows(IllegalArgumentException.class, () -> request("repair-task-1001", "", outputDirectory));
    }

    @Test
    void requestRejectsInvalidCommandOutputAndMapEntries() {
        Path outputDirectory = temporaryDirectory.resolve("output");

        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of(),
                Map.of(),
                Map.of(),
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        ));
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude", " "),
                Map.of(),
                Map.of(),
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        ));
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude"),
                Map.of(),
                Map.of(),
                "/work/repo",
                "none",
                true,
                false,
                null
        ));

        Map<String, String> envWithBlankKey = new java.util.LinkedHashMap<>();
        envWithBlankKey.put(" ", "value");
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude"),
                envWithBlankKey,
                Map.of(),
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        ));

        Map<String, String> mountsWithDuplicateSource = new java.util.LinkedHashMap<>();
        mountsWithDuplicateSource.put("/tmp/repo", "/work/repo");
        mountsWithDuplicateSource.put(" /tmp/repo ", "/work/other");
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude"),
                Map.of(),
                mountsWithDuplicateSource,
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        ));

        Map<String, String> mountsWithBlankTarget = new java.util.LinkedHashMap<>();
        mountsWithBlankTarget.put("/tmp/repo", " ");
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude"),
                Map.of(),
                mountsWithBlankTarget,
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        ));
    }

    @Test
    void requestNormalizesNullCollectionsAndCopiesMutableInputs() {
        Path outputDirectory = temporaryDirectory.resolve("output");
        List<String> command = new java.util.ArrayList<>(List.of("claude", "--dangerously-skip-permissions"));
        Map<String, String> env = new java.util.LinkedHashMap<>(Map.of("ANTHROPIC_MODEL", "claude-sonnet"));
        Map<String, String> mounts = new java.util.LinkedHashMap<>(Map.of(
                outputDirectory.toString(), "/work/output"
        ));

        ContainerRunRequest request = new ContainerRunRequest(
                " repair-task-1001 ",
                " claude-code:local ",
                command,
                env,
                mounts,
                " /work/repo ",
                null,
                true,
                false,
                outputDirectory
        );
        command.add("--mutated");
        env.put("MUTATED", "true");
        mounts.put("/tmp/mutated", "/mutated");

        assertEquals("repair-task-1001", request.containerName());
        assertEquals("claude-code:local", request.image());
        assertEquals(List.of("claude", "--dangerously-skip-permissions"), request.command());
        assertEquals(Map.of("ANTHROPIC_MODEL", "claude-sonnet"), request.env());
        assertEquals(Map.of(outputDirectory.toString(), "/work/output"), request.mounts());
        assertEquals("/work/repo", request.workingDirectory());
        assertEquals("", request.networkMode());
        assertFalse(request.securityPolicy().enabled());
        assertThrows(UnsupportedOperationException.class, () -> request.command().add("--blocked"));
        assertThrows(UnsupportedOperationException.class, () -> request.env().put("BLOCKED", "true"));
        assertThrows(UnsupportedOperationException.class, () -> request.mounts().put("/blocked", "/blocked"));
    }

    @Test
    void requestRejectsInvalidOrPrivilegedSecurityPolicies() {
        Path outputDirectory = temporaryDirectory.resolve("output");
        assertThrows(IllegalArgumentException.class, () -> new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "",
                "4",
                512,
                "1000:1000",
                Map.of("/tmp", "rw,size=1g")
        ));

        ContainerSecurityPolicy policy = new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "8g",
                "4",
                512,
                "1000:1000",
                Map.of("/tmp", "rw,size=1g")
        );
        assertThrows(IllegalArgumentException.class, () -> new ContainerRunRequest(
                "repair-task-1001",
                "claude-code:local",
                List.of("claude"),
                Map.of(),
                Map.of(),
                "/work/repo",
                "none",
                true,
                true,
                outputDirectory,
                false,
                "",
                60_000L,
                policy
        ));
    }

    @Test
    void fakeRunnerWritesDeterministicSuccessArtifactsAndMetadata() throws IOException {
        Path outputDirectory = temporaryDirectory.resolve("output");
        ContainerRunRequest request = request("repair-task-1001", "claude-code:local", outputDirectory);
        FakeContainerRunner runner = FakeContainerRunner.succeeding();

        ContainerRunResult result = runner.run(request);

        assertEquals(0, result.exitCode());
        assertTrue(result.durationMillis() >= 0);
        assertEquals(outputDirectory.resolve("result.json"), result.resultJson());
        assertEquals(outputDirectory.resolve("patch.diff"), result.patchDiff());
        assertEquals(outputDirectory.resolve("test.log"), result.testLog());
        assertEquals(outputDirectory.resolve("claude-events.jsonl"), result.claudeEventsJsonl());
        assertEquals(outputDirectory.resolve("docker-meta.json"), result.dockerMetaJson());
        assertEquals("repair-task-1001", result.metadata().get("containerName"));
        assertEquals("claude-code:local", result.metadata().get("image"));

        JsonNode resultJson = OBJECT_MAPPER.readTree(Files.readString(outputDirectory.resolve("result.json"), StandardCharsets.UTF_8));
        assertEquals("SUCCESS", resultJson.get("status").asText());
        assertFalse(resultJson.has("patchArtifact"));
        assertEquals("PASSED", resultJson.get("testStatus").asText());
        assertEquals("LOW", resultJson.get("riskLevel").asText());

        assertTrue(Files.readString(outputDirectory.resolve("patch.diff"), StandardCharsets.UTF_8).contains("diff --git"));
        assertTrue(Files.readString(outputDirectory.resolve("test.log"), StandardCharsets.UTF_8).contains("BUILD SUCCESS"));
        assertTrue(Files.readString(outputDirectory.resolve("claude-events.jsonl"), StandardCharsets.UTF_8).contains("\"type\":\"message\""));

        JsonNode dockerMeta = OBJECT_MAPPER.readTree(Files.readString(outputDirectory.resolve("docker-meta.json"), StandardCharsets.UTF_8));
        assertEquals("claude-code:local", dockerMeta.get("image").asText());
        assertEquals("claude", dockerMeta.get("command").get(0).asText());
        assertEquals("--dangerously-skip-permissions", dockerMeta.get("command").get(1).asText());
    }

    @Test
    void fakeRunnerCanSimulateNonZeroExitCode() throws IOException {
        Path outputDirectory = temporaryDirectory.resolve("output");
        FakeContainerRunner runner = FakeContainerRunner.withExitCode(17);

        ContainerRunResult result = runner.run(request("repair-task-1001", "claude-code:local", outputDirectory));

        assertEquals(17, result.exitCode());
        assertTrue(result.stderr().contains("simulated container exit code 17"));
        assertTrue(Files.exists(outputDirectory.resolve("docker-meta.json")));
    }

    @Test
    void resultRejectsDuplicateMetadataKeysAfterTrimming() {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("artifact", "present");
        metadata.put(" artifact ", "duplicate");

        assertThrows(IllegalArgumentException.class, () -> new ContainerRunResult(
                0,
                1,
                "",
                "",
                null,
                null,
                null,
                null,
                null,
                metadata
        ));
    }

    @Test
    void fakeRunnerCanSimulateMissingArtifacts() throws IOException {
        Path outputDirectory = temporaryDirectory.resolve("output");
        FakeContainerRunner runner = FakeContainerRunner.succeeding()
                .withoutArtifact("result.json")
                .withoutArtifact("patch.diff");

        ContainerRunResult result = runner.run(request("repair-task-1001", "claude-code:local", outputDirectory));

        assertEquals(0, result.exitCode());
        assertEquals(null, result.resultJson());
        assertEquals(null, result.patchDiff());
        assertFalse(Files.exists(outputDirectory.resolve("result.json")));
        assertFalse(Files.exists(outputDirectory.resolve("patch.diff")));
        assertTrue(Files.exists(outputDirectory.resolve("test.log")));
        assertTrue(result.metadata().containsKey("missingArtifacts"));
    }

    @Test
    void fakeRunnerRejectsBlankMissingArtifactNames() {
        FakeContainerRunner runner = FakeContainerRunner.succeeding();

        assertThrows(IllegalArgumentException.class, () -> runner.withoutArtifact(" "));
    }

    @Test
    void fakeRunnerRejectsUnknownMissingArtifactNames() {
        FakeContainerRunner runner = FakeContainerRunner.succeeding();

        assertThrows(IllegalArgumentException.class, () -> runner.withoutArtifact("resultJson"));
    }

    private static ContainerRunRequest request(String containerName, String image, Path outputDirectory) {
        return new ContainerRunRequest(
                containerName,
                image,
                List.of("claude", "--dangerously-skip-permissions"),
                Map.of("ANTHROPIC_MODEL", "claude-sonnet"),
                Map.of(
                        outputDirectory.getParent().resolve("repo").toString(), "/work/repo",
                        outputDirectory.toString(), "/work/output"
                ),
                "/work/repo",
                "none",
                true,
                false,
                outputDirectory
        );
    }
}
