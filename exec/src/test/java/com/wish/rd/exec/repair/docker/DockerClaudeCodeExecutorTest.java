package com.wish.rd.exec.repair.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.RepairAlertType;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairArtifactType;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthState;
import com.wish.rd.exec.repair.model.ModelHealthStore;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.ExecutionAllowlistPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerClaudeCodeExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESULT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["status", "summary", "changedFiles", "testCommands", "testStatus", "riskLevel", "needHumanAction"]
            }
            """;
    private static final List<String> COMMAND = List.of("claude", "--dangerously-skip-permissions", "--print");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReturnSuccessWhenRunnerProducesValidSuccessResult() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("Structured result for SUCCESS", result.summary());
        assertEquals("", result.pullRequestUrl());
        assertTrue(Files.exists(temporaryDirectory.resolve("task-1001/input/prompt.md")));
        assertTrue(Files.exists(temporaryDirectory.resolve("task-1001/input/context.json")));
        assertTrue(Files.exists(temporaryDirectory.resolve("task-1001/input/result.schema.json")));
        assertEquals("rd-bot/claude-code:test", runner.request().image());
        assertEquals(COMMAND, runner.request().command());
    }

    @Test
    void shouldValidateQaAgentResultWithQaProtocolInsteadOfCodingSchema() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-passed",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("QA PASSED with real command evidence", result.summary());
        assertEquals(validQaResultJson("PASSED").strip(), result.rawResultJson().get("__agentResultJson").strip());
    }

    @Test
    void shouldReturnFailedWhenQaAgentReportsFailedAcceptance() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("FAILED"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-failed",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("QA_AGENT failed acceptance"));
        assertEquals(validQaResultJson("FAILED").strip(), result.rawResultJson().get("__agentResultJson").strip());
    }

    @Test
    void shouldSkipRepositoryPublishWhenPolicyDisablesPublication() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        RecordingRepositoryPort repositoryPort = new RecordingRepositoryPort();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                repositoryPort,
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-1001-review",
                Map.of("repositoryPublishRequired", "false")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(1, repositoryPort.prepared().size());
        assertEquals(0, repositoryPort.published().size());
        assertEquals("true", result.dockerMetadataJson().get("repositoryPublishSkipped"));
    }

    @Test
    void shouldReturnFailedValidationWhenResultJsonIsMissing() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult(validResultJson("SUCCESS"))
                .without("result.json"));

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("result.json is missing"));
        assertEquals("", result.pullRequestUrl());
        assertFalse(hasArtifact(result, "result.json"));
    }

    @Test
    void shouldReturnFailedValidationWhenSuccessArtifactsAreMissing() {
        for (String artifactName : List.of("patch.diff", "test.log", "claude-events.jsonl")) {
            DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult(validResultJson("SUCCESS"))
                    .without(artifactName));

            RepairExecutionResult result = executor.execute(command("task-" + artifactName.replace(".", "-")));

            assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status(), artifactName);
            assertTrue(result.errorMessage().contains(artifactName + " is missing"), artifactName);
            assertEquals("", result.pullRequestUrl());
        }
    }

    @Test
    void shouldReturnFailedValidationWhenResultJsonIsInvalid() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult("{not-json"));

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("parse error"));
        assertEquals("", result.pullRequestUrl());
    }

    @Test
    void shouldReturnFailedForNonZeroExitUnlessResultNeedsInfo() {
        DockerClaudeCodeExecutor failedExecutor = executor(CapturingRunner.withExitCodeAndResult(
                17,
                validResultJson("SUCCESS")
        ));
        DockerClaudeCodeExecutor needInfoExecutor = executor(CapturingRunner.withExitCodeAndResult(
                17,
                validNeedInfoJson()
        ));

        RepairExecutionResult failed = failedExecutor.execute(command());
        RepairExecutionResult needInfo = needInfoExecutor.execute(command("task-1002"));

        assertEquals(RepairExecutionStatus.FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("container exited with code 17"));
        assertEquals(RepairExecutionStatus.NEED_INFO, needInfo.status());
        assertEquals("Need repository access", needInfo.summary());
    }

    @Test
    void shouldSurfaceClaudeApiErrorFromEventStreamWhenContainerExits() {
        CapturingRunner runner = CapturingRunner.withExitCodeAndResult(1, validResultJson("FAILED"))
                .withExtraArtifact(
                        "claude-events.jsonl",
                        """
                                {"type":"assistant","message":{"content":[{"type":"text","text":"API Error: 402 {\\"error\\":{\\"message\\":\\"Insufficient Balance\\",\\"type\\":\\"unknown_error\\"}}"}]}}
                                """
                );
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("Claude Code API error 402: Insufficient Balance"));
        assertTrue(result.errorMessage().contains("container exited with code 1"));
    }

    @Test
    void shouldReturnFailedWhenRunnerReturnsNullResult() {
        DockerClaudeCodeExecutor executor = executor(request -> null);

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("container runner returned null result"));
    }

    @Test
    void shouldRejectExecutionBeforeContainerWhenAllowlistDoesNotMatch() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor.Configuration configuration = new DockerClaudeCodeExecutor.Configuration(
                "rd-bot/claude-code:test",
                COMMAND,
                "none",
                true,
                false
        );
        DockerClaudeCodeExecutor executor = new DockerClaudeCodeExecutor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                new StructuredResultValidator(),
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                null,
                new ModelHealthStore(ModelCircuitBreakerPolicy.disabled()),
                DockerExecutionRegistry.noop(),
                new ExecutionAllowlistPolicy(true, List.of(), List.of("example/allowed"), List.of("main"), List.of("repair/*"))
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.UNSAFE, result.status());
        assertTrue(result.errorMessage().contains("repository is not allowlisted"));
        assertEquals("true", result.dockerMetadataJson().get("securityPolicyRejected"));
        assertFalse(runner.wasCalled());
    }

    @Test
    void shouldReturnFailedValidationBeforeContainerWhenRoutedAuthTokenEnvIsMissing() {
        String missingEnvName = "RD_BOT_TEST_MISSING_AUTH_TOKEN_7476858891402350592";
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(provider("deepseek", Map.of(
                        "ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic",
                        "RD_CLAUDE_AUTH_TOKEN_ENV", missingEnvName,
                        missingEnvName, ""
                )))
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("deepseek"));
        assertTrue(result.errorMessage().contains(missingEnvName));
        assertFalse(runner.wasCalled());
        assertEquals("deepseek", result.dockerMetadataJson().get("provider"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"FAILED_VALIDATION\""));
    }

    @Test
    void shouldNotOpenProviderCircuitWhenRoutedAuthTokenEnvIsMissing() {
        String missingEnvName = "RD_BOT_TEST_MISSING_AUTH_TOKEN_7477228820605571072";
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        ModelHealthStore healthStore = enabledHealthStore(1);
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(provider("deepseek", Map.of(
                        "ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic",
                        "RD_CLAUDE_AUTH_TOKEN_ENV", missingEnvName,
                        missingEnvName, ""
                ))),
                healthStore
        );

        RepairExecutionResult first = executor.execute(command());
        RepairExecutionResult second = executor.execute(command("task-1001-second"));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, first.status());
        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, second.status());
        assertTrue(second.errorMessage().contains(missingEnvName));
        assertFalse(second.errorMessage().contains("all Claude Code providers are unavailable by circuit breaker"));
        assertEquals(ModelHealthState.CLOSED, healthStore.snapshot("deepseek").state());
        assertFalse(runner.wasCalled());
    }

    @Test
    void shouldFallbackToNextProviderWhenAttemptFailsValidation() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(
                Attempt.missingResult(),
                Attempt.success()
        ));
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(
                        provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")),
                        provider("anthropic", Map.of("ANTHROPIC_API_KEY", ""))
                )
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(2, runner.requests().size());
        assertEquals("deepseek", runner.requests().get(0).env().get("RD_CLAUDE_PROVIDER_NAME"));
        assertEquals("anthropic", runner.requests().get(1).env().get("RD_CLAUDE_PROVIDER_NAME"));
        assertEquals("anthropic", result.dockerMetadataJson().get("provider"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"deepseek\""));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"FAILED_VALIDATION\""));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"anthropic\""));
    }

    @Test
    void shouldNotFallbackWhenProviderReturnsBusinessNeedInfo() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(
                Attempt.needInfo(),
                Attempt.success()
        ));
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")),
                        provider("anthropic", Map.of("ANTHROPIC_API_KEY", "")))
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.NEED_INFO, result.status());
        assertEquals("Need repository access", result.summary());
        assertEquals(1, runner.requests().size());
        assertEquals("deepseek", result.dockerMetadataJson().get("provider"));
    }

    @Test
    void shouldSkipOpenProviderAndRunNextProvider() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(Attempt.success()));
        ModelHealthStore healthStore = enabledHealthStore(1);
        healthStore.markFailure("deepseek");
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(
                        provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")),
                        provider("anthropic", Map.of("ANTHROPIC_API_KEY", ""))
                ),
                healthStore
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(1, runner.requests().size());
        assertEquals("anthropic", runner.requests().getFirst().env().get("RD_CLAUDE_PROVIDER_NAME"));
        assertEquals("anthropic", result.dockerMetadataJson().get("provider"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("SKIPPED_CIRCUIT_OPEN"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"deepseek\""));
    }

    @Test
    void shouldProbeFirstProviderWhenAllProvidersAreCircuitOpen() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(Attempt.success()));
        ModelHealthStore healthStore = enabledHealthStore(2);
        open(healthStore, "deepseek", 2);
        open(healthStore, "anthropic", 2);
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(
                        provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")),
                        provider("anthropic", Map.of("ANTHROPIC_API_KEY", ""))
                ),
                healthStore
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(1, runner.requests().size());
        assertEquals("deepseek", runner.requests().getFirst().env().get("RD_CLAUDE_PROVIDER_NAME"));
        assertFalse(result.errorMessage().contains("all Claude Code providers are unavailable by circuit breaker"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("SKIPPED_CIRCUIT_OPEN"));
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"SUCCESS\""));
    }

    @Test
    void shouldOpenProviderCircuitAfterFallbackFailureThreshold() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(
                Attempt.missingResult(),
                Attempt.success()
        ));
        ModelHealthStore healthStore = enabledHealthStore(1);
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(
                        provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")),
                        provider("anthropic", Map.of("ANTHROPIC_API_KEY", ""))
                ),
                healthStore
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(ModelHealthState.OPEN, healthStore.snapshot("deepseek").state());
        assertEquals(ModelHealthState.CLOSED, healthStore.snapshot("anthropic").state());
    }

    @Test
    void shouldNotOpenProviderCircuitForNeedInfo() {
        MultiAttemptRunner runner = new MultiAttemptRunner(List.of(Attempt.needInfo()));
        ModelHealthStore healthStore = enabledHealthStore(1);
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(provider("deepseek", Map.of("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic"))),
                healthStore
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.NEED_INFO, result.status());
        assertEquals(ModelHealthState.CLOSED, healthStore.snapshot("deepseek").state());
        assertEquals(1, runner.requests().size());
    }

    @Test
    void shouldNotOpenProviderCircuitWhenRepositoryPublishFails() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        RecordingRepositoryPort repositoryPort = new RecordingRepositoryPort(true);
        ModelHealthStore healthStore = enabledHealthStore(1);
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                repositoryPort,
                null,
                healthStore
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertEquals("Repository publish failed.", result.summary());
        assertEquals(ModelHealthState.CLOSED, healthStore.snapshot("anthropic").state());
        assertTrue(result.dockerMetadataJson().get("providerAttemptsJson").contains("\"status\":\"SUCCESS\""));
    }

    @Test
    void shouldNeverIncludePullRequestUrlWhenValidationFails() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult("""
                {
                  "status": "SUCCESS",
                  "summary": "Missing required fields",
                  "prBody": "Would create PR later",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "PASSED",
                  "riskLevel": "LOW",
                  "needHumanAction": false
                """));

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertEquals("", result.pullRequestUrl());
    }

    @Test
    void shouldIncludeAllProducedOutputArtifacts() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withExtraArtifact("notes.md", "operator note"));

        RepairExecutionResult result = executor.execute(command());

        assertEquals(
                Set.of("result.json", "patch.diff", "test.log", "claude-events.jsonl", "docker-meta.json", "notes.md"),
                artifactNames(result)
        );
        assertEquals(RepairArtifactType.RESULT_JSON, artifact(result, "result.json").type());
        assertEquals(RepairArtifactType.PATCH_DIFF, artifact(result, "patch.diff").type());
        assertEquals(RepairArtifactType.TEST_LOG, artifact(result, "test.log").type());
        assertEquals(RepairArtifactType.CLAUDE_EVENTS, artifact(result, "claude-events.jsonl").type());
        assertEquals(RepairArtifactType.DOCKER_METADATA, artifact(result, "docker-meta.json").type());
        assertEquals(RepairArtifactType.OTHER, artifact(result, "notes.md").type());
    }

    @Test
    void shouldPrepareAndPublishRepositoryAroundSuccessfulExecution() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        RecordingRepositoryPort repositoryPort = new RecordingRepositoryPort();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                repositoryPort,
                null
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(1, repositoryPort.prepared().size());
        assertEquals(1, repositoryPort.published().size());
        assertEquals("true", result.githubMetadataJson().get("repository.prepared"));
        assertEquals("true", result.githubMetadataJson().get("repository.pushed"));
        assertEquals("abc123", result.githubMetadataJson().get("repository.commitSha"));
        assertEquals(runner.request().mounts().keySet().stream()
                .filter(path -> path.endsWith("/repo"))
                .findFirst()
                .orElseThrow(), repositoryPort.prepared().getFirst().repoDirectory().toString());
    }

    @Test
    void shouldFailExecutionWhenRepositoryPublishFails() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        RecordingRepositoryPort repositoryPort = new RecordingRepositoryPort(true);
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                repositoryPort,
                null
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertEquals("Repository publish failed.", result.summary());
        assertTrue(result.errorMessage().contains("push failed"));
        assertEquals(1, repositoryPort.prepared().size());
        assertEquals(1, repositoryPort.published().size());
        assertEquals("true", result.githubMetadataJson().get("repository.prepared"));
    }

    @Test
    void shouldIncludeDockerMetadataInExecutionResult() throws IOException {
        List<String> command = List.of("claude", "--append-system-prompt", "line 1\nline 2\twith tab");
        CapturingRunner runner = CapturingRunner.withExitCodeAndResult(0, validResultJson("UNSAFE"));
        DockerClaudeCodeExecutor executor = executor(runner, command);

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.UNSAFE, result.status());
        assertEquals("rd-bot/claude-code:test", result.dockerMetadataJson().get("image"));
        assertEquals("rd-bot-repair-task-1001", result.dockerMetadataJson().get("containerName"));
        JsonNode commandJson = OBJECT_MAPPER.readTree(result.dockerMetadataJson().get("commandJson"));
        assertEquals("claude", commandJson.get(0).asText());
        assertEquals("--append-system-prompt", commandJson.get(1).asText());
        assertEquals("line 1\nline 2\twith tab", commandJson.get(2).asText());
        assertEquals(command, runner.request().command());
        assertEquals("0", result.dockerMetadataJson().get("exitCode"));
        assertEquals("1234", result.dockerMetadataJson().get("durationMillis"));
        assertTrue(result.dockerMetadataJson().get("outputArtifactPaths").contains("result.json"));
        assertTrue(result.dockerMetadataJson().get("outputArtifactPaths").contains("docker-meta.json"));
    }

    @Test
    void shouldEmitTimeoutAndBudgetAlertsWithoutStoppingExecution() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withDurationMillis(2_500L)
                .withMetadata("estimatedSpend", "7.25");
        RecordingAlertSink alertSink = new RecordingAlertSink();
        RepairExecutionWatchdog watchdog = new RepairExecutionWatchdog(
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("5.00")),
                alertSink
        );
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                watchdog
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(2, alertSink.alerts().size());
        assertEquals(RepairAlertType.TIMEOUT_WARNING, alertSink.alerts().get(0).type());
        assertEquals("2500", alertSink.alerts().get(0).metadata().get("elapsedMillis"));
        assertEquals(RepairAlertType.BUDGET_WARNING, alertSink.alerts().get(1).type());
        assertEquals("7.25", alertSink.alerts().get(1).metadata().get("estimatedSpend"));
    }

    @Test
    void shouldBuildSafeContainerNameFromUnsafeTaskId() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(permissiveWorkspaceFactory(), runner, COMMAND);

        RepairExecutionResult result = executor.execute(command("task 1001/with:unsafe"));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("rd-bot-repair-task-1001-with-unsafe", runner.request().containerName());
        assertFalse(runner.request().containerName().contains("/"));
        assertFalse(runner.request().containerName().contains(":"));
        assertFalse(runner.request().containerName().contains(" "));
    }

    @Test
    void shouldNotImportGitHubOrRagStreamTaskRegistry() throws IOException {
        Path source = dockerSourceRoot().resolve("DockerClaudeCodeExecutor.java");
        assertTrue(Files.exists(source), "DockerClaudeCodeExecutor source must exist");
        String body = Files.readString(source);

        assertFalse(body.contains("import com.wish.rd.exec.repair.github."));
        assertFalse(body.contains("import com.wish.rd.rag.stream.RagStreamTaskRegistry"));
        assertFalse(body.contains("RagStreamTaskRegistry"));
    }

    @Test
    void publicExecutorShouldHaveJavadoc() throws IOException {
        Path source = dockerSourceRoot().resolve("DockerClaudeCodeExecutor.java");
        assertTrue(Files.exists(source), "DockerClaudeCodeExecutor source must exist");
        String body = Files.readString(source);

        Pattern declarationWithJavadoc = Pattern.compile(
                "/\\*\\*.*?\\*/\\s*public\\s+class\\s+DockerClaudeCodeExecutor\\b",
                Pattern.DOTALL
        );
        assertTrue(declarationWithJavadoc.matcher(body).find(), "DockerClaudeCodeExecutor must have JavaDoc");
    }

    private DockerClaudeCodeExecutor executor(CapturingRunner runner) {
        return executor(runner, COMMAND);
    }

    private DockerClaudeCodeExecutor executor(ContainerRunnerPort runner) {
        return executor(runner, COMMAND);
    }

    private DockerClaudeCodeExecutor executor(ContainerRunnerPort runner, List<String> command) {
        return executor(new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON), runner, command);
    }

    private DockerClaudeCodeExecutor executor(
            ContainerRunnerPort runner,
            List<String> command,
            List<ClaudeCodeModelProvider> providers
    ) {
        return executor(runner, command, providers, new ModelHealthStore(ModelCircuitBreakerPolicy.disabled()));
    }

    private DockerClaudeCodeExecutor executor(
            ContainerRunnerPort runner,
            List<String> command,
            List<ClaudeCodeModelProvider> providers,
            ModelHealthStore healthStore
    ) {
        DockerClaudeCodeExecutor.Configuration configuration = new DockerClaudeCodeExecutor.Configuration(
                "rd-bot/claude-code:test",
                command,
                "none",
                true,
                false,
                providers
        );
        return new DockerClaudeCodeExecutor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                new StructuredResultValidator(),
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                null,
                healthStore
        );
    }

    private DockerClaudeCodeExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort runner,
            List<String> command
    ) {
        return executor(workspaceFactory, runner, command, null);
    }

    private DockerClaudeCodeExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort runner,
            List<String> command,
            RepairExecutionWatchdog watchdog
    ) {
        return executor(workspaceFactory, runner, command, RepairWorkspaceRepositoryPort.noop(), watchdog);
    }

    private DockerClaudeCodeExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort runner,
            List<String> command,
            RepairWorkspaceRepositoryPort repositoryPort,
            RepairExecutionWatchdog watchdog
    ) {
        return executor(workspaceFactory, runner, command, repositoryPort, watchdog,
                new ModelHealthStore(ModelCircuitBreakerPolicy.disabled()));
    }

    private DockerClaudeCodeExecutor executor(
            RepairWorkspaceFactory workspaceFactory,
            ContainerRunnerPort runner,
            List<String> command,
            RepairWorkspaceRepositoryPort repositoryPort,
            RepairExecutionWatchdog watchdog,
            ModelHealthStore healthStore
    ) {
        DockerClaudeCodeExecutor.Configuration configuration = new DockerClaudeCodeExecutor.Configuration(
                "rd-bot/claude-code:test",
                command,
                "none",
                true,
                false
        );
        return new DockerClaudeCodeExecutor(
                workspaceFactory,
                runner,
                new StructuredResultValidator(),
                configuration,
                repositoryPort,
                watchdog,
                healthStore
        );
    }

    private RepairWorkspaceFactory permissiveWorkspaceFactory() {
        return new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON) {
            @Override
            public RepairWorkspace create(RepairJobCommand command) throws IOException {
                Path root = temporaryDirectory.resolve("permissive-workspace").toAbsolutePath().normalize();
                Path input = root.resolve("input");
                Path repo = root.resolve("repo");
                Path output = root.resolve("output");
                Files.createDirectories(input);
                Files.createDirectories(repo);
                Files.createDirectories(output);
                RepairWorkspaceFiles files = new RepairWorkspaceFiles(
                        input.resolve("prompt.md"),
                        input.resolve("context.json"),
                        input.resolve("result.schema.json"),
                        output.resolve("result.json"),
                        output.resolve("patch.diff"),
                        output.resolve("test.log"),
                        output.resolve("claude-events.jsonl"),
                        output.resolve("docker-meta.json")
                );
                return new RepairWorkspace(root, input, repo, output, files);
            }
        };
    }

    private static RepairJobCommand command() {
        return command("task-1001");
    }

    private static RepairJobCommand command(String taskId) {
        return command(taskId, Map.of("yolo", "true"));
    }

    private static RepairJobCommand command(String taskId, Map<String, String> policyJson) {
        return command(taskId, policyJson, Map.of("ragSummary", "retrieved relevant chunks"));
    }

    private static RepairJobCommand command(
            String taskId,
            Map<String, String> policyJson,
            Map<String, String> contextJson
    ) {
        return new RepairJobCommand(
                "repair-1001",
                taskId,
                "FS-1001",
                "Order service fails",
                "Fix the order service regression.",
                "https://github.com/example/order",
                "example",
                "order",
                "main",
                "repair/FS-1001",
                contextJson,
                policyJson
        );
    }

    private static boolean hasArtifact(RepairExecutionResult result, String name) {
        return result.artifacts().stream().anyMatch(artifact -> name.equals(artifact.name()));
    }

    private static Set<String> artifactNames(RepairExecutionResult result) {
        return result.artifacts().stream()
                .map(RepairArtifact::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static RepairArtifact artifact(RepairExecutionResult result, String name) {
        return result.artifacts().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow();
    }

    private static String validResultJson(String status) {
        boolean needHumanAction = "UNSAFE".equals(status);
        String testStatus = "FAILED".equals(status) ? "FAILED" : "PASSED";
        if ("FAILED".equals(testStatus)) {
            needHumanAction = true;
        }
        return """
                {
                  "status": "%s",
                  "summary": "Structured result for %s",
                  "prBody": "Task 5 never creates a PR URL.",
                  "changedFiles": ["src/main/java/App.java"],
                  "testCommands": ["./mvnw test"],
                  "testStatus": "%s",
                  "riskLevel": "LOW",
                  "needHumanAction": %s
                }
                """.formatted(status, status, testStatus, needHumanAction);
    }

    private static String validNeedInfoJson() {
        return """
                {
                  "status": "NEED_INFO",
                  "summary": "Need repository access",
                  "prBody": "",
                  "changedFiles": [],
                  "testCommands": [],
                  "testStatus": "SKIPPED",
                  "riskLevel": "LOW",
                  "needHumanAction": true
                }
                """;
    }

    private static String validQaResultJson(String status) {
        String acceptanceStatus = "FAILED".equals(status) ? "FAILED" : "PASSED";
        String summary = "FAILED".equals(status)
                ? "QA FAILED with real command evidence"
                : "QA PASSED with real command evidence";
        return """
                {
                  "status": "%s",
                  "summary": "%s",
                  "acceptanceResults": [
                    {
                      "criteria": "文档必须包含 marker",
                      "command": "grep -q marker docs/example.md",
                      "status": "%s",
                      "logArtifactId": "qa-log-1"
                    }
                  ]
                }
                """.formatted(status, summary, acceptanceStatus);
    }

    private static ClaudeCodeModelProvider provider(String name, Map<String, String> env) {
        return new ClaudeCodeModelProvider(name, env);
    }

    private static ModelHealthStore enabledHealthStore(int failureThreshold) {
        return new ModelHealthStore(new ModelCircuitBreakerPolicy(true, failureThreshold, 60_000L));
    }

    private static void open(ModelHealthStore healthStore, String provider, int failureThreshold) {
        for (int index = 0; index < failureThreshold; index++) {
            healthStore.markFailure(provider);
        }
    }

    private static Path dockerSourceRoot() {
        Path currentDirectory = Path.of("").toAbsolutePath();
        Path moduleRelative = currentDirectory.resolve("src/main/java/com/wish/rd/exec/repair/docker");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path reactorRelative = currentDirectory.resolve("exec/src/main/java/com/wish/rd/exec/repair/docker");
        if (Files.isDirectory(reactorRelative)) {
            return reactorRelative;
        }
        throw new IllegalStateException("Cannot find docker source root from " + currentDirectory);
    }

    private static final class CapturingRunner implements ContainerRunnerPort {

        private final int exitCode;
        private final String resultJson;
        private final Set<String> missingArtifacts;
        private final Map<String, String> extraArtifacts;
        private final long durationMillis;
        private final Map<String, String> metadata;
        private ContainerRunRequest request;

        private CapturingRunner(
                int exitCode,
                String resultJson,
                Set<String> missingArtifacts,
                Map<String, String> extraArtifacts,
                long durationMillis,
                Map<String, String> metadata
        ) {
            this.exitCode = exitCode;
            this.resultJson = resultJson;
            this.missingArtifacts = Set.copyOf(missingArtifacts);
            this.extraArtifacts = Map.copyOf(extraArtifacts);
            this.durationMillis = durationMillis;
            this.metadata = Map.copyOf(metadata);
        }

        static CapturingRunner withResult(String resultJson) {
            return new CapturingRunner(0, resultJson, Set.of(), Map.of(), 1_234L, Map.of("runner", "capturing"));
        }

        static CapturingRunner withExitCodeAndResult(int exitCode, String resultJson) {
            return new CapturingRunner(exitCode, resultJson, Set.of(), Map.of(), 1_234L, Map.of("runner", "capturing"));
        }

        CapturingRunner without(String artifactName) {
            Set<String> nextMissing = new java.util.LinkedHashSet<>(missingArtifacts);
            nextMissing.add(artifactName);
            return new CapturingRunner(exitCode, resultJson, nextMissing, extraArtifacts, durationMillis, metadata);
        }

        CapturingRunner withExtraArtifact(String artifactName, String body) {
            Map<String, String> nextExtra = new LinkedHashMap<>(extraArtifacts);
            nextExtra.put(artifactName, body);
            return new CapturingRunner(exitCode, resultJson, missingArtifacts, nextExtra, durationMillis, metadata);
        }

        CapturingRunner withDurationMillis(long nextDurationMillis) {
            return new CapturingRunner(exitCode, resultJson, missingArtifacts, extraArtifacts, nextDurationMillis, metadata);
        }

        CapturingRunner withMetadata(String key, String value) {
            Map<String, String> nextMetadata = new LinkedHashMap<>(metadata);
            nextMetadata.put(key, value);
            return new CapturingRunner(exitCode, resultJson, missingArtifacts, extraArtifacts, durationMillis, nextMetadata);
        }

        ContainerRunRequest request() {
            assertNotNull(request);
            return request;
        }

        boolean wasCalled() {
            return request != null;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request) throws IOException {
            this.request = request;
            Files.createDirectories(request.outputDirectory());
            Path resultPath = request.outputDirectory().resolve("result.json");
            Path patchPath = request.outputDirectory().resolve("patch.diff");
            Path testLogPath = request.outputDirectory().resolve("test.log");
            Path eventsPath = request.outputDirectory().resolve("claude-events.jsonl");
            Path dockerMetaPath = request.outputDirectory().resolve("docker-meta.json");

            writeIfPresent(resultPath, resultJson, "result.json");
            writeIfPresent(patchPath, "diff --git a/src/main/java/App.java b/src/main/java/App.java\n", "patch.diff");
            writeIfPresent(testLogPath, "BUILD SUCCESS\n", "test.log");
            writeIfPresent(eventsPath, "{\"type\":\"done\"}\n", "claude-events.jsonl");
            writeIfPresent(dockerMetaPath, "{\"runner\":\"fake\"}\n", "docker-meta.json");
            for (Map.Entry<String, String> entry : extraArtifacts.entrySet()) {
                Files.writeString(request.outputDirectory().resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
            }

            return new ContainerRunResult(
                    exitCode,
                    durationMillis,
                    "stdout",
                    exitCode == 0 ? "" : "stderr",
                    missingArtifacts.contains("result.json") ? null : resultPath,
                    missingArtifacts.contains("patch.diff") ? null : patchPath,
                    missingArtifacts.contains("test.log") ? null : testLogPath,
                    missingArtifacts.contains("claude-events.jsonl") ? null : eventsPath,
                    missingArtifacts.contains("docker-meta.json") ? null : dockerMetaPath,
                    metadata
            );
        }

        private void writeIfPresent(Path path, String body, String artifactName) throws IOException {
            if (!missingArtifacts.contains(artifactName)) {
                Files.writeString(path, body, StandardCharsets.UTF_8);
            }
        }
    }

    private record Attempt(int exitCode, String resultJson, Set<String> missingArtifacts) {

        private static Attempt success() {
            return new Attempt(0, validResultJson("SUCCESS"), Set.of());
        }

        private static Attempt needInfo() {
            return new Attempt(0, validNeedInfoJson(), Set.of());
        }

        private static Attempt missingResult() {
            return new Attempt(0, validResultJson("SUCCESS"), Set.of("result.json"));
        }
    }

    private static final class MultiAttemptRunner implements ContainerRunnerPort {

        private final List<Attempt> attempts;
        private final List<ContainerRunRequest> requests = new ArrayList<>();

        private MultiAttemptRunner(List<Attempt> attempts) {
            this.attempts = List.copyOf(attempts);
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request) throws IOException {
            requests.add(request);
            Attempt attempt = attempts.get(Math.min(requests.size() - 1, attempts.size() - 1));
            Files.createDirectories(request.outputDirectory());
            Path resultPath = request.outputDirectory().resolve("result.json");
            Path patchPath = request.outputDirectory().resolve("patch.diff");
            Path testLogPath = request.outputDirectory().resolve("test.log");
            Path eventsPath = request.outputDirectory().resolve("claude-events.jsonl");
            Path dockerMetaPath = request.outputDirectory().resolve("docker-meta.json");

            if (!attempt.missingArtifacts().contains("result.json")) {
                Files.writeString(resultPath, attempt.resultJson(), StandardCharsets.UTF_8);
            }
            if (!attempt.missingArtifacts().contains("patch.diff")) {
                Files.writeString(patchPath, "diff --git a/src/main/java/App.java b/src/main/java/App.java\n",
                        StandardCharsets.UTF_8);
            }
            if (!attempt.missingArtifacts().contains("test.log")) {
                Files.writeString(testLogPath, "BUILD SUCCESS\n", StandardCharsets.UTF_8);
            }
            if (!attempt.missingArtifacts().contains("claude-events.jsonl")) {
                Files.writeString(eventsPath, "{\"type\":\"done\"}\n", StandardCharsets.UTF_8);
            }
            if (!attempt.missingArtifacts().contains("docker-meta.json")) {
                Files.writeString(dockerMetaPath, "{\"runner\":\"fake\"}\n", StandardCharsets.UTF_8);
            }

            return new ContainerRunResult(
                    attempt.exitCode(),
                    1_000L + requests.size(),
                    "stdout",
                    attempt.exitCode() == 0 ? "" : "stderr",
                    attempt.missingArtifacts().contains("result.json") ? null : resultPath,
                    attempt.missingArtifacts().contains("patch.diff") ? null : patchPath,
                    attempt.missingArtifacts().contains("test.log") ? null : testLogPath,
                    attempt.missingArtifacts().contains("claude-events.jsonl") ? null : eventsPath,
                    attempt.missingArtifacts().contains("docker-meta.json") ? null : dockerMetaPath,
                    Map.of("runner", "multi-attempt")
            );
        }

        private List<ContainerRunRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private static final class RecordingAlertSink implements RepairAlertSinkPort {

        private final List<RepairAlert> alerts = new ArrayList<>();

        @Override
        public void publish(RepairAlert alert) {
            alerts.add(alert);
        }

        private List<RepairAlert> alerts() {
            return List.copyOf(alerts);
        }
    }

    private static final class RecordingRepositoryPort implements RepairWorkspaceRepositoryPort {

        private final boolean failPublish;
        private final List<RepairWorkspace> prepared = new ArrayList<>();
        private final List<RepairWorkspace> published = new ArrayList<>();

        private RecordingRepositoryPort() {
            this(false);
        }

        private RecordingRepositoryPort(boolean failPublish) {
            this.failPublish = failPublish;
        }

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) {
            prepared.add(workspace);
            return new RepositoryOperationResult(Map.of("prepared", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
            published.add(workspace);
            if (failPublish) {
                throw new IOException("push failed");
            }
            return new RepositoryOperationResult(Map.of("pushed", "true", "commitSha", "abc123"));
        }

        private List<RepairWorkspace> prepared() {
            return List.copyOf(prepared);
        }

        private List<RepairWorkspace> published() {
            return List.copyOf(published);
        }
    }
}
