package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.executor.impl.ProcessContainerRunner;
import com.wish.rd.exec.repair.docker.ContainerOutputListener;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.ContainerNetworkPlan;
import com.wish.rd.exec.repair.docker.model.ContainerSecurityPolicy;
import com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessContainerRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExposeRequiredDockerExecutorDefaults() {
        DockerExecutorProperties properties = new DockerExecutorProperties();

        assertFalse(properties.isEnabled());
        assertEquals("rd-bot/claude-code:local", properties.getImage());
        assertEquals("rd-bot/claude-code-qa:local", properties.getQaImage());
        assertEquals(Path.of("/tmp/rd-bot/repair-workspaces"), properties.getWorkspaceRoot());
        assertEquals("claude", properties.getCommand());
        assertEquals("--dangerously-skip-permissions", properties.getYoloFlag());
        assertEquals("stream-json", properties.getOutputFormat());
        assertEquals("bridge", properties.getNetworkMode());
        assertTrue(properties.isRemoveAfterExit());
        assertEquals(1_800_000L, properties.getTimeoutAlertMillis());
        assertEquals(0, new BigDecimal("36.00").compareTo(properties.getBudgetAlertCny()));
        assertEquals(
                List.of("claude", "-p", "--dangerously-skip-permissions", "--output-format", "stream-json", "--verbose"),
                properties.claudeCommand()
        );

        DockerClaudeCodeExecutor.Configuration configuration = properties.toExecutorConfiguration();

        assertEquals("rd-bot/claude-code:local", configuration.image());
        assertEquals("rd-bot/claude-code-qa:local", configuration.qaImage());
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
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
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
    void shouldEnableInitAndSharedMemoryForBrowserQaContainers() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        ContainerRunRequest request = new ContainerRunRequest(
                "repair-task-qa",
                "rd-bot/claude-code-qa:local",
                properties.claudeCommand(),
                Map.of(),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work"),
                "/work/repo",
                "bridge",
                true,
                false,
                temporaryDirectory.resolve("output"),
                true,
                "1g"
        );

        List<String> argv = runner.buildCommand(request);

        assertTrue(argv.contains("--init"));
        assertTrue(argv.contains("--shm-size=1g"));
        assertTrue(argv.indexOf("--init") < argv.indexOf("rd-bot/claude-code-qa:local"));
    }

    @Test
    void shouldOverrideImageEntrypointBeforeTheImageName() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        ContainerRunRequest request = new ContainerRunRequest(
                "rd-pi-qa-deps",
                "rd-bot/pi-agent-qa:local",
                List.of("-c", "npm install --include=dev"),
                Map.of("NODE_ENV", "development"),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work/repo"),
                "/work/repo",
                "bridge",
                true,
                false,
                temporaryDirectory.resolve("output"),
                false,
                "",
                60_000L,
                ContainerSecurityPolicy.disabled(),
                null,
                "sh"
        );

        List<String> argv = runner.buildCommand(request);

        int entrypoint = argv.indexOf("--entrypoint");
        int image = argv.indexOf("rd-bot/pi-agent-qa:local");
        assertTrue(entrypoint >= 0, argv.toString());
        assertEquals("sh", argv.get(entrypoint + 1));
        assertTrue(entrypoint < image, argv.toString());
        assertFalse(argv.subList(0, image).contains("node"));
    }

    @Test
    void shouldRenderHardenedContainerSecurityPolicyInStableOrder() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
        ContainerSecurityPolicy policy = new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "8g",
                "4",
                512,
                "1000:1000",
                Map.of(
                        "/work/pi-agent", "rw,exec,size=256m,uid=1000,gid=1000",
                        "/tmp", "rw,noexec,nosuid,size=1g,uid=1000,gid=1000",
                        "/home/node", "rw,noexec,nosuid,size=256m,uid=1000,gid=1000"
                )
        );
        ContainerRunRequest request = new ContainerRunRequest(
                "repair-task-pi",
                "rd-bot/pi-agent:local",
                List.of("node", "bridge.mjs"),
                Map.of(),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work/repo"),
                "/work/repo",
                "bridge",
                true,
                false,
                temporaryDirectory.resolve("output"),
                false,
                "",
                60_000L,
                policy
        );

        List<String> argv = runner.buildCommand(request);

        List<String> expectedSecurityPrefix = List.of(
                "docker", "run", "--rm",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--memory", "8g",
                "--cpus", "4",
                "--pids-limit", "512",
                "--user", "1000:1000",
                "--tmpfs", "/home/node:rw,noexec,nosuid,size=256m,uid=1000,gid=1000",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=1g,uid=1000,gid=1000",
                "--tmpfs", "/work/pi-agent:rw,exec,size=256m,uid=1000,gid=1000"
        );
        assertEquals(expectedSecurityPrefix, argv.subList(0, expectedSecurityPrefix.size()));
        assertFalse(argv.contains("--privileged"));
    }

    @Test
    void shouldPassProviderEnvValuesButOnlyReferenceSecretEnvNames() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
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
    void shouldPassSecretValuesToDockerCliProcessEnvironmentWithoutArgvLeak() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        RecordingLauncher launcher = new RecordingLauncher(temporaryDirectory.resolve("output"), 0);
        ProcessContainerRunner runner = runner(properties, launcher);
        ContainerRunRequest request = request(
                properties,
                Map.of(
                        "RD_CLAUDE_AUTH_TOKEN_ENV", "LONGCAT_API_KEY",
                        "LONGCAT_API_KEY", "test-longcat-secret-from-process-env",
                        "VISIBLE_FLAG", "visible"
                ),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        ContainerRunResult result = runner.run(request);
        String argv = String.join(" ", launcher.commands().getFirst());

        assertFalse(argv.contains("test-longcat-secret-from-process-env"));
        assertFalse(result.metadata().toString().contains("test-longcat-secret-from-process-env"));
        assertEquals(
                "test-longcat-secret-from-process-env",
                launcher.environments().getFirst().get("LONGCAT_API_KEY")
        );
        assertEquals("visible", launcher.environments().getFirst().get("VISIBLE_FLAG"));
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
        ProcessContainerRunner runner = runner(properties,
                (argv, environment) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""));
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
        ProcessContainerRunner runner = runner(properties, (argv, environment) -> {
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
    void shouldUseAnInternalTaskNetworkForTheRelaySidecarAndRemoveItAfterPiStops() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        String opaqueLease = "pcl_opaque-lease-must-not-appear-in-argv";
        RecordingLauncher launcher = new RecordingLauncher(temporaryDirectory.resolve("output"), 0);
        ProcessContainerRunner runner = runner(properties, launcher);
        ContainerSecurityPolicy relayPolicy = new ContainerSecurityPolicy(
                true,
                true,
                true,
                true,
                "256m",
                "1",
                128,
                "1000:1000",
                Map.of("/tmp", "rw,noexec,nosuid,size=32m,uid=1000,gid=1000")
        );
        ContainerNetworkPlan networkPlan = new ContainerNetworkPlan(
                "rd-pi-network-task-1",
                new ContainerNetworkPlan.Sidecar(
                        "rd-pi-relay-task-1",
                        "rd-pi-relay",
                        "rd-bot/pi-agent:test",
                        "node",
                        List.of("/opt/rd-pi-bridge/src/rd-pi-relay-sidecar.mjs"),
                        Map.of(
                                "RD_PI_RELAY_HOST_URL", "http://host.docker.internal:18080/internal/pi/credential-relay/proxy",
                                "RD_PI_RELAY_TASK_ID", "task-1",
                                "RD_PI_RELAY_STAGE_RUN_ID", "stage-1",
                                "RD_PI_RELAY_PROVIDER_ID", "provider-1"
                        ),
                        "bridge",
                        "http://127.0.0.1:8787/healthz",
                        2_000L,
                        relayPolicy
                )
        );
        ContainerRunRequest request = new ContainerRunRequest(
                "rd-pi-task-1",
                "rd-bot/pi-agent:test",
                List.of("node", "agent.mjs"),
                Map.of("RD_PI_CREDENTIAL_LEASE", opaqueLease),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work"),
                "/work",
                networkPlan.internalNetworkName(),
                true,
                false,
                temporaryDirectory.resolve("output"),
                false,
                "",
                60_000L,
                ContainerSecurityPolicy.disabled(),
                networkPlan
        );

        ContainerRunResult result = runner.run(request);

        assertEquals(0, result.exitCode());
        List<List<String>> commands = launcher.commands();
        int networkCreate = commandIndex(commands, List.of("docker", "network", "create"));
        int sidecarRun = commands.stream()
                .filter(command -> command.contains("-d") && command.contains("rd-pi-relay-task-1"))
                .findFirst()
                .map(commands::indexOf)
                .orElse(-1);
        int bridgeConnect = commandIndex(commands, List.of(
                "docker", "network", "connect", "bridge", "rd-pi-relay-task-1"
        ));
        int healthCheck = commands.stream()
                .filter(command -> command.size() > 3
                        && "docker".equals(command.getFirst())
                        && "exec".equals(command.get(1))
                        && "rd-pi-relay-task-1".equals(command.get(2)))
                .findFirst()
                .map(commands::indexOf)
                .orElse(-1);
        int agentRun = commands.stream()
                .filter(command -> command.contains("agent.mjs"))
                .findFirst()
                .map(commands::indexOf)
                .orElse(-1);
        int sidecarCleanup = commandIndex(commands, List.of("docker", "rm", "-f", "rd-pi-relay-task-1"));
        int networkCleanup = commandIndex(commands, List.of("docker", "network", "rm", "rd-pi-network-task-1"));

        assertTrue(networkCreate >= 0);
        assertTrue(commands.get(networkCreate).contains("--internal"));
        assertTrue(sidecarRun > networkCreate);
        assertTrue(commands.get(sidecarRun).contains("--network-alias"));
        assertTrue(commands.get(sidecarRun).contains("rd-pi-relay"));
        assertTrue(commands.get(sidecarRun).contains("--read-only"));
        List<String> sidecarCommand = commands.get(sidecarRun);
        int sidecarImage = sidecarCommand.indexOf("rd-bot/pi-agent:test");
        assertTrue(sidecarImage >= 2);
        assertEquals(
                List.of(
                        "--entrypoint",
                        "node",
                        "rd-bot/pi-agent:test",
                        "/opt/rd-pi-bridge/src/rd-pi-relay-sidecar.mjs"
                ),
                sidecarCommand.subList(sidecarImage - 2, sidecarImage + 2)
        );
        assertTrue(bridgeConnect > sidecarRun);
        assertTrue(healthCheck > bridgeConnect);
        assertTrue(agentRun > healthCheck);
        assertTrue(commands.get(agentRun).contains("rd-pi-network-task-1"));
        assertTrue(sidecarCleanup > agentRun);
        assertTrue(networkCleanup > sidecarCleanup);
        assertFalse(commands.toString().contains(opaqueLease));
        assertFalse(Files.readString(request.outputDirectory().resolve("docker-meta.json"), StandardCharsets.UTF_8)
                .contains(opaqueLease));
    }

    @Test
    void shouldPassRequestHardTimeoutToDockerProcessLauncher() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        AtomicLong capturedTimeout = new AtomicLong(-1L);
        ProcessContainerRunner runner = new ProcessContainerRunner(
                properties,
                (argv, environment, timeoutMillis) -> {
                    capturedTimeout.set(timeoutMillis);
                    return new ProcessContainerRunner.CommandResult(0, 1, "", "");
                }
        );
        ContainerRunRequest base = request(
                properties,
                Map.of(),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );
        ContainerRunRequest request = new ContainerRunRequest(
                base.containerName(),
                base.image(),
                base.command(),
                base.env(),
                base.mounts(),
                base.workingDirectory(),
                base.networkMode(),
                base.removeAfterExit(),
                base.allowPrivileged(),
                base.outputDirectory(),
                true,
                "1g",
                1_200_000L
        );

        runner.run(request);

        assertEquals(1_200_000L, capturedTimeout.get());
    }

    @Test
    void shouldStreamContainerOutputBeforeTheRunnerReturns() throws IOException {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        StringBuilder streamedStdout = new StringBuilder();
        StringBuilder streamedStderr = new StringBuilder();
        ProcessContainerRunner runner = new ProcessContainerRunner(
                properties,
                (argv, environment, timeoutMillis) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""),
                (argv, environment, timeoutMillis, listener) -> {
                    listener.onStdout("{\"protocol\":\"rd-agent-event/v1\"}");
                    listener.onStdout("\n");
                    listener.onStderr("diagnostic\n");
                    Files.createDirectories(temporaryDirectory.resolve("output"));
                    Files.writeString(
                            temporaryDirectory.resolve("output/result.json"),
                            "{\"status\":\"FAILED\",\"summary\":\"done\"}",
                            StandardCharsets.UTF_8
                    );
                    return new ProcessContainerRunner.CommandResult(0, 1, "all stdout", "all stderr");
                }
        );
        ContainerRunRequest request = request(
                properties,
                Map.of(),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        ContainerRunResult result = runner.run(request, new ContainerOutputListener() {
            @Override
            public void onStdout(String chunk) {
                streamedStdout.append(chunk);
            }

            @Override
            public void onStderr(String chunk) {
                streamedStderr.append(chunk);
            }
        });

        assertEquals(0, result.exitCode());
        assertEquals("{\"protocol\":\"rd-agent-event/v1\"}\n", streamedStdout.toString());
        assertEquals("diagnostic\n", streamedStderr.toString());
        assertEquals("all stdout", result.stdout());
        assertEquals("all stderr", result.stderr());
    }

    @Test
    void shouldPropagateStreamingListenerFailureAsAnIoFailure() {
        DockerExecutorProperties properties = new DockerExecutorProperties();
        ProcessContainerRunner runner = new ProcessContainerRunner(
                properties,
                (argv, environment, timeoutMillis) -> new ProcessContainerRunner.CommandResult(0, 1, "", ""),
                (argv, environment, timeoutMillis, listener) -> {
                    listener.onStdout("bad event");
                    return new ProcessContainerRunner.CommandResult(0, 1, "", "");
                }
        );
        ContainerRunRequest request = request(
                properties,
                Map.of(),
                Map.of(temporaryDirectory.resolve("workspace").toString(), "/work")
        );

        assertThrows(IOException.class, () -> runner.run(request, new ContainerOutputListener() {
            @Override
            public void onStdout(String chunk) {
                throw new IllegalArgumentException("invalid event");
            }
        }));
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

    private static int commandIndex(List<List<String>> commands, List<String> expected) {
        for (int index = 0; index < commands.size(); index++) {
            List<String> command = commands.get(index);
            if (command.size() >= expected.size()
                    && command.subList(0, expected.size()).equals(expected)) {
                return index;
            }
        }
        return -1;
    }

    private static final class RecordingLauncher implements ProcessContainerRunner.CommandLauncher {

        private final Path outputDirectory;
        private final int exitCode;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Map<String, String>> environments = new ArrayList<>();

        private RecordingLauncher(Path outputDirectory, int exitCode) {
            this.outputDirectory = outputDirectory;
            this.exitCode = exitCode;
        }

        @Override
        public ProcessContainerRunner.CommandResult launch(
                List<String> argv,
                Map<String, String> environment
        ) throws IOException {
            commands.add(List.copyOf(argv));
            environments.add(environment == null ? Map.of() : Map.copyOf(environment));
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

        private List<Map<String, String>> environments() {
            return List.copyOf(environments);
        }
    }

    @Configuration
    @Import({DockerExecutorProperties.class, ProcessContainerRunner.class})
    static class DockerRunnerContextConfiguration {
    }
}
