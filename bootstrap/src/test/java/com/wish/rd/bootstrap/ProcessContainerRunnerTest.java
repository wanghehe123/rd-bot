package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.executor.ProcessContainerRunner;
import com.wish.rd.exec.repair.docker.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.ContainerRunResult;
import com.wish.rd.exec.repair.docker.DockerClaudeCodeExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessContainerRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExposeRequiredDockerExecutorDefaults() {
        DockerExecutorProperties properties = new DockerExecutorProperties();

        assertFalse(properties.isEnabled());
        assertEquals("rd-bot/claude-code:local", properties.getImage());
        assertEquals(Path.of("/tmp/rd-bot/repair-workspaces"), properties.getWorkspaceRoot());
        assertEquals("claude", properties.getCommand());
        assertEquals("--dangerously-skip-permissions", properties.getYoloFlag());
        assertEquals("stream-json", properties.getOutputFormat());
        assertEquals("bridge", properties.getNetworkMode());
        assertTrue(properties.isRemoveAfterExit());
        assertEquals(1_800_000L, properties.getTimeoutAlertMillis());
        assertEquals(0, new BigDecimal("5.00").compareTo(properties.getBudgetAlertUsd()));
        assertEquals(
                List.of("claude", "-p", "--dangerously-skip-permissions", "--output-format", "stream-json", "--verbose"),
                properties.claudeCommand()
        );

        DockerClaudeCodeExecutor.Configuration configuration = properties.toExecutorConfiguration();

        assertEquals("rd-bot/claude-code:local", configuration.image());
        assertEquals(properties.claudeCommand(), configuration.command());
        assertEquals("bridge", configuration.networkMode());
        assertTrue(configuration.removeAfterExit());
        assertFalse(configuration.allowPrivileged());
    }

    @Test
    void shouldBuildDockerRunArgvWithoutShellAndWithConfigurableValues() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.setImage("registry.example.local/rd/claude:test");
        properties.setNetworkMode("host");
        properties.setYoloFlag("--yolo-test");
        properties.setOutputFormat("jsonl");
        ProcessContainerRunner runner = runner(properties, argv -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        Path workspace = temporaryDirectory.resolve("workspace");
        ContainerRunRequest request = request(
                properties,
                Map.of("ANTHROPIC_MODEL", "claude-sonnet", "ANTHROPIC_API_KEY", "sk-test-secret"),
                Map.of(workspace.toString(), "/work")
        );

        List<String> argv = runner.buildCommand(request);

        assertEquals("docker", argv.getFirst());
        assertEquals("run", argv.get(1));
        assertFalse(argv.contains("sh"));
        assertFalse(argv.contains("-c"));
        assertTrue(argv.contains("registry.example.local/rd/claude:test"));
        assertTrue(argv.contains("host"));
        assertTrue(argv.contains(workspace + ":/work"));
        assertTrue(argv.contains("--yolo-test"));
        assertTrue(argv.contains("--output-format"));
        assertTrue(argv.contains("jsonl"));
        assertTrue(argv.contains("--verbose"));
        assertTrue(argv.contains("ANTHROPIC_API_KEY"));
        assertFalse(argv.toString().contains("sk-test-secret"));
        assertTrue(argv.contains("ANTHROPIC_MODEL=claude-sonnet"));
    }

    @Test
    void shouldPassProviderEnvValuesButOnlyReferenceSecretEnvNames() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = runner(properties, argv -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        ContainerRunRequest request = request(
                properties,
                Map.of(
                        "ANTHROPIC_BASE_URL", "https://api.longcat.chat/anthropic",
                        "ANTHROPIC_MODEL", "LongCat-2.0",
                        "RD_CLAUDE_AUTH_TOKEN_ENV", "LONGCAT_API_KEY",
                        "LONGCAT_API_KEY", "sk-longcat-raw-secret",
                        "RD_CLAUDE_API_KEY_ENV", "MIMO_API_KEY",
                        "MIMO_API_KEY", "sk-mimo-raw-secret"
                ),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        List<String> argv = runner.buildCommand(request);
        ContainerRunResult result = runner.run(request);
        String dockerMetaJson = Files.readString(request.outputDirectory().resolve("docker-meta.json"), StandardCharsets.UTF_8);

        assertTrue(argv.contains("ANTHROPIC_BASE_URL=https://api.longcat.chat/anthropic"));
        assertTrue(argv.contains("ANTHROPIC_MODEL=LongCat-2.0"));
        assertTrue(argv.contains("RD_CLAUDE_AUTH_TOKEN_ENV=LONGCAT_API_KEY"));
        assertTrue(argv.contains("LONGCAT_API_KEY"));
        assertFalse(argv.contains("LONGCAT_API_KEY=sk-longcat-raw-secret"));
        assertTrue(argv.contains("RD_CLAUDE_API_KEY_ENV=MIMO_API_KEY"));
        assertTrue(argv.contains("MIMO_API_KEY"));
        assertFalse(argv.contains("MIMO_API_KEY=sk-mimo-raw-secret"));
        assertFalse(result.metadata().toString().contains("sk-longcat-raw-secret"));
        assertFalse(result.metadata().toString().contains("sk-mimo-raw-secret"));
        assertFalse(dockerMetaJson.contains("sk-longcat-raw-secret"));
        assertFalse(dockerMetaJson.contains("sk-mimo-raw-secret"));
    }

    @Test
    void shouldRunWithFakeLauncherAndReturnStandardArtifactPaths() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        RecordingLauncher launcher = new RecordingLauncher(temporaryDirectory.resolve("output"), 17);
        ProcessContainerRunner runner = runner(properties, launcher);
        ContainerRunRequest request = request(properties, Map.of(), Map.of(temporaryDirectory.resolve("workspace").toString(), "/work"));

        ContainerRunResult result = runner.run(request);

        assertEquals(17, result.exitCode());
        assertEquals("fake stdout", result.stdout());
        assertEquals("fake stderr", result.stderr());
        assertEquals(request.outputDirectory().resolve("result.json"), result.resultJson());
        assertEquals(request.outputDirectory().resolve("patch.diff"), result.patchDiff());
        assertEquals(request.outputDirectory().resolve("test.log"), result.testLog());
        assertEquals(request.outputDirectory().resolve("claude-events.jsonl"), result.claudeEventsJsonl());
        assertEquals(request.outputDirectory().resolve("docker-meta.json"), result.dockerMetaJson());
        assertEquals(1, launcher.commands().size());
        assertNotNull(result.metadata().get("argv"));
        assertTrue(Files.exists(request.outputDirectory().resolve("docker-meta.json")));
    }

    @Test
    void shouldNotExposeRawSecretValuesInArgvOrMetadata() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        String apiKey = "sk-ant-raw-secret";
        String token = "ghp-raw-token";
        String unclassifiedSecret = "pat-unclassified-secret";
        ProcessContainerRunner runner = runner(properties, argv -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        ContainerRunRequest request = request(
                properties,
                Map.of(
                        "ANTHROPIC_API_KEY", apiKey,
                        "GITHUB_TOKEN", token,
                        "GITHUB_PAT", unclassifiedSecret,
                        "VISIBLE_FLAG", "visible"
                ),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        List<String> argv = runner.buildCommand(request);
        ContainerRunResult result = runner.run(request);
        String dockerMetaJson = Files.readString(request.outputDirectory().resolve("docker-meta.json"), StandardCharsets.UTF_8);

        assertFalse(argv.toString().contains(apiKey));
        assertFalse(argv.toString().contains(token));
        assertFalse(argv.toString().contains(unclassifiedSecret));
        assertFalse(result.metadata().toString().contains(apiKey));
        assertFalse(result.metadata().toString().contains(token));
        assertFalse(result.metadata().toString().contains(unclassifiedSecret));
        assertFalse(dockerMetaJson.contains(apiKey));
        assertFalse(dockerMetaJson.contains(token));
        assertFalse(dockerMetaJson.contains(unclassifiedSecret));
        assertTrue(argv.contains("VISIBLE_FLAG=visible"));
    }

    @Test
    void shouldOverwriteContainerProvidedDockerMetadataWithSanitizedRunnerMetadata() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        String rawToken = "raw-token-from-container";
        ProcessContainerRunner runner = runner(properties, argv -> {
            Path dockerMetaJson = temporaryDirectory.resolve("output/docker-meta.json");
            Files.createDirectories(dockerMetaJson.getParent());
            Files.writeString(dockerMetaJson, "{\"env\":\"" + rawToken + "\"}", StandardCharsets.UTF_8);
            return new ProcessContainerRunner.CommandResult(0, 1, "", "");
        });
        ContainerRunRequest request = request(
                properties,
                Map.of("GITHUB_PAT", rawToken),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        runner.run(request);

        String dockerMetaJson = Files.readString(request.outputDirectory().resolve("docker-meta.json"), StandardCharsets.UTF_8);
        assertFalse(dockerMetaJson.contains(rawToken));
        assertTrue(dockerMetaJson.contains("\"containerName\""));
    }

    @Test
    void shouldOnlyRegisterProcessRunnerWhenDockerExecutionIsEnabled() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DockerRunnerContextConfiguration.class);

        contextRunner.run(context -> assertEquals(0, context.getBeanNamesForType(ProcessContainerRunner.class).length));
        contextRunner
                .withPropertyValues("rd.executor.docker.enabled=false")
                .run(context -> assertEquals(0, context.getBeanNamesForType(ProcessContainerRunner.class).length));
        contextRunner
                .withPropertyValues("rd.executor.docker.enabled=true")
                .run(context -> assertEquals(1, context.getBeanNamesForType(ProcessContainerRunner.class).length));
    }

    private ProcessContainerRunner runner(
            DockerExecutorProperties properties,
            ProcessContainerRunner.CommandLauncher launcher
    ) {
        return new ProcessContainerRunner(properties, launcher);
    }

    private ContainerRunRequest request(
            DockerExecutorProperties properties,
            Map<String, String> env,
            Map<String, String> mounts
    ) {
        DockerClaudeCodeExecutor.Configuration configuration = properties.toExecutorConfiguration();
        return new ContainerRunRequest(
                "repair-task-1001",
                configuration.image(),
                configuration.command(),
                env,
                mounts,
                "/work/repo",
                configuration.networkMode(),
                configuration.removeAfterExit(),
                configuration.allowPrivileged(),
                temporaryDirectory.resolve("output")
        );
    }

    private static final class RecordingLauncher implements ProcessContainerRunner.CommandLauncher {

        private final Path outputDirectory;
        private final int exitCode;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingLauncher(Path outputDirectory, int exitCode) {
            this.outputDirectory = outputDirectory;
            this.exitCode = exitCode;
        }

        @Override
        public ProcessContainerRunner.CommandResult launch(List<String> argv) throws IOException {
            commands.add(List.copyOf(argv));
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("result.json"), "{\"status\":\"FAILED\"}", StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("patch.diff"), "diff --git a/App.java b/App.java", StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("test.log"), "test log", StandardCharsets.UTF_8);
            Files.writeString(outputDirectory.resolve("claude-events.jsonl"), "{\"type\":\"done\"}\n", StandardCharsets.UTF_8);
            return new ProcessContainerRunner.CommandResult(exitCode, 123, "fake stdout", "fake stderr");
        }

        private List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }

    @Configuration
    @Import({DockerExecutorProperties.class, ProcessContainerRunner.class})
    static class DockerRunnerContextConfiguration {
    }
}
