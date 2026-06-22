package com.wish.rd.exec.repair.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 测试用容器执行器，写入确定性的 Docker Claude Code 协议产物且不调用真实 Docker。
 */
class FakeContainerRunner implements ContainerRunnerPort {

    private final int exitCode;
    private final Set<String> missingArtifacts;
    private static final Set<String> STANDARD_ARTIFACTS = Set.of(
            "result.json",
            "patch.diff",
            "test.log",
            "claude-events.jsonl",
            "docker-meta.json"
    );

    private FakeContainerRunner(int exitCode, Set<String> missingArtifacts) {
        this.exitCode = exitCode;
        this.missingArtifacts = new LinkedHashSet<>(missingArtifacts);
    }

    /**
     * 创建成功退出的 fake runner。
     *
     * @return 成功退出的 fake runner
     */
    static FakeContainerRunner succeeding() {
        return new FakeContainerRunner(0, Set.of());
    }

    /**
     * 创建指定退出码的 fake runner。
     *
     * @param exitCode 模拟容器退出码
     * @return 指定退出码的 fake runner
     */
    static FakeContainerRunner withExitCode(int exitCode) {
        return new FakeContainerRunner(exitCode, Set.of());
    }

    /**
     * 返回一个不会写入指定产物文件名的新 fake runner。
     *
     * @param artifactName 标准协议产物文件名
     * @return 缺少指定产物的新 fake runner
     */
    FakeContainerRunner withoutArtifact(String artifactName) {
        String normalized = artifactName == null ? "" : artifactName.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("artifactName must not be blank");
        }
        if (!STANDARD_ARTIFACTS.contains(normalized)) {
            throw new IllegalArgumentException("unknown artifactName: " + artifactName);
        }
        Set<String> nextMissingArtifacts = new LinkedHashSet<>(missingArtifacts);
        nextMissingArtifacts.add(normalized);
        return new FakeContainerRunner(exitCode, nextMissingArtifacts);
    }

    @Override
    public ContainerRunResult run(ContainerRunRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.outputDirectory() == null) {
            throw new IllegalArgumentException("outputDirectory must not be null");
        }
        Instant startedAt = Instant.now();
        Path outputDirectory = request.outputDirectory();
        Files.createDirectories(outputDirectory);

        Path resultJson = outputDirectory.resolve("result.json");
        Path patchDiff = outputDirectory.resolve("patch.diff");
        Path testLog = outputDirectory.resolve("test.log");
        Path claudeEventsJsonl = outputDirectory.resolve("claude-events.jsonl");
        Path dockerMetaJson = outputDirectory.resolve("docker-meta.json");

        writeIfPresent(resultJson, resultJsonBody(), "result.json");
        writeIfPresent(patchDiff, patchDiffBody(), "patch.diff");
        writeIfPresent(testLog, "BUILD SUCCESS - fake container tests passed\n", "test.log");
        writeIfPresent(claudeEventsJsonl, "{\"type\":\"message\",\"message\":\"fake runner completed\"}\n", "claude-events.jsonl");
        writeIfPresent(dockerMetaJson, dockerMetaJsonBody(request), "docker-meta.json");

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("containerName", request.containerName());
        metadata.put("image", request.image());
        if (!missingArtifacts.isEmpty()) {
            metadata.put("missingArtifacts", String.join(",", missingArtifacts));
        }
        long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
        return new ContainerRunResult(
                exitCode,
                durationMillis,
                "fake container completed",
                exitCode == 0 ? "" : "simulated container exit code " + exitCode,
                pathIfPresent(resultJson, "result.json"),
                pathIfPresent(patchDiff, "patch.diff"),
                pathIfPresent(testLog, "test.log"),
                pathIfPresent(claudeEventsJsonl, "claude-events.jsonl"),
                pathIfPresent(dockerMetaJson, "docker-meta.json"),
                metadata
        );
    }

    private Path pathIfPresent(Path path, String artifactName) {
        return missingArtifacts.contains(artifactName) ? null : path;
    }

    private void writeIfPresent(Path path, String body, String artifactName) throws IOException {
        if (!missingArtifacts.contains(artifactName)) {
            Files.writeString(path, body, StandardCharsets.UTF_8);
        }
    }

    private static String resultJsonBody() {
        return """
                {
                  "status": "SUCCESS",
                  "summary": "Fake runner produced a deterministic successful repair.",
                  "prBody": "Fake PR body for Docker Claude Code dry run.",
                  "changedFiles": ["src/main/java/example/OrderService.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                }
                """;
    }

    private static String patchDiffBody() {
        return """
                diff --git a/src/main/java/example/OrderService.java b/src/main/java/example/OrderService.java
                --- a/src/main/java/example/OrderService.java
                +++ b/src/main/java/example/OrderService.java
                @@ -1 +1 @@
                -return null;
                +return order;
                """;
    }

    private static String dockerMetaJsonBody(ContainerRunRequest request) {
        String commandJson = request.command().stream()
                .map(FakeContainerRunner::json)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return """
                {
                  "containerName": %s,
                  "image": %s,
                  "command": [%s],
                  "workingDirectory": %s,
                  "networkMode": %s,
                  "removeAfterExit": %s,
                  "allowPrivileged": %s
                }
                """.formatted(
                json(request.containerName()),
                json(request.image()),
                commandJson,
                json(request.workingDirectory()),
                json(request.networkMode()),
                request.removeAfterExit(),
                request.allowPrivileged()
        );
    }

    private static String json(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
