package com.wish.rd.exec.repair.docker;

import com.wish.rd.exec.repair.docker.impl.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import com.wish.rd.exec.repair.alert.RepairExecutionWatchdog;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthState;
import com.wish.rd.exec.repair.health.ModelHealthStore;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.exec.repair.security.model.ExecutionAllowlistPolicy;
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
import com.wish.rd.exec.repair.docker.model.ClaudeCodeModelProvider;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.model.ContainerRunResult;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.docker.model.RepairWorkspaceFiles;

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
    void shouldParseQaExecutionTimeoutWithFloorAndFallback() {
        assertEquals(1_200_000L, DockerClaudeCodeExecutor.parseQaExecutionTimeoutMillis(null));
        assertEquals(1_200_000L, DockerClaudeCodeExecutor.parseQaExecutionTimeoutMillis(" "));
        assertEquals(1_200_000L, DockerClaudeCodeExecutor.parseQaExecutionTimeoutMillis("not-a-number"));
        assertEquals(2_400_000L, DockerClaudeCodeExecutor.parseQaExecutionTimeoutMillis("2400000"));
        assertEquals(60_000L, DockerClaudeCodeExecutor.parseQaExecutionTimeoutMillis("1"));
    }

    @Test
    void shouldMountPersistentPackageManagerCache() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(runner);

        executor.execute(command());

        assertEquals(
                "/work/cache",
                runner.request().mounts().get(temporaryDirectory.resolve("task-1001/cache").toString())
        );
        assertEquals("/work/cache/npm", runner.request().env().get("npm_config_cache"));
        assertEquals("/work/cache/pip", runner.request().env().get("PIP_CACHE_DIR"));
        assertEquals("/work/cache/yarn", runner.request().env().get("YARN_CACHE_FOLDER"));
    }

    @Test
    void shouldUseVerifiedProjectRuntimeImageForSelectedRole() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-project-runtime",
                Map.of(
                        "repositoryPublishRequired", "false",
                        "runtimeImage", "rd-bot/project-runtime:verified",
                        "runtimeImageVerified", "true",
                        "runtimeAgentType", "CLAUDE_CODE"
                ),
                Map.of("agentRole", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("rd-bot/project-runtime:verified", runner.request().image());
    }

    @Test
    void shouldClassifyMarkdownHandoffAsAFirstClassArtifact() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withExtraArtifact("handoff/next.md", "# Coding handoff\n\nImplement the plan.");
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-handoff-artifact",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("HANDOFF_MARKDOWN", artifact(result, "handoff/next.md").type().name());
    }

    @Test
    void shouldMountVerifiedHandoffSkillForNonQaDeliveryRoles() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-handoff-skill",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "CODING_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals(
                "/home/rdbot/.claude/skills/role-handoff-document:ro",
                runner.request().mounts().get(temporaryDirectory.resolve("handoff-skill").toString())
        );
        assertEquals("role-handoff-document", runner.request().env().get("RD_HANDOFF_SKILL_ID"));
        assertEquals("1.0.0", runner.request().env().get("RD_HANDOFF_SKILL_VERSION"));
        assertEquals("role-handoff-document", result.dockerMetadataJson().get("handoffSkillId"));
        assertEquals("CODING_AGENT", result.dockerMetadataJson().get("handoffSkillRole"));
        assertEquals(
                temporaryDirectory.resolve("handoff-skill").toString(),
                result.dockerMetadataJson().get("handoffSkillInstallPath")
        );
    }

    @Test
    void shouldValidateQaAgentResultWithQaProtocolInsteadOfCodingSchema() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-passed",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertEquals("QA PASSED with real command evidence", result.summary());
        assertEquals(validQaResultJson("PASSED").strip(), result.rawResultJson().get("__agentResultJson").strip());
        assertEquals("rd-bot/claude-code-qa:test", runner.request().image());
        assertTrue(runner.request().initEnabled());
        assertEquals("1g", runner.request().sharedMemorySize());
        assertEquals(
                "/home/rdbot/.claude/skills/qa-playwright-cli:ro",
                runner.request().mounts().get(temporaryDirectory.resolve("qa-skill").toString())
        );
        assertEquals("qa-playwright-cli", runner.request().env().get("RD_QA_SKILL_ID"));
        assertEquals("1.0.1", runner.request().env().get("RD_QA_SKILL_VERSION"));
        assertEquals("QA_AGENT", runner.request().env().get("RD_AGENT_ROLE"));
        assertEquals("qa-playwright-cli", result.dockerMetadataJson().get("qaSkillId"));
        assertEquals("1.0.1", result.dockerMetadataJson().get("qaSkillVersion"));
        assertEquals("sha256:abc123", result.dockerMetadataJson().get("qaSkillChecksum"));
        assertEquals("QA_AGENT", result.dockerMetadataJson().get("qaSkillRole"));
        assertEquals(
                temporaryDirectory.resolve("qa-skill").toString(),
                result.dockerMetadataJson().get("qaSkillInstallPath")
        );
    }

    @Test
    void shouldReturnFailedWhenQaAgentReportsFailedAcceptance() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("FAILED"))
                .withQaEvidenceArtifacts();
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
    void shouldSurfaceQaContainerStartupFailureBeforeValidatingFallbackResult() {
        CapturingRunner runner = CapturingRunner.withExitCodeAndResult(
                        126,
                        """
                                {
                                  "status":"FAILED",
                                  "summary":"Claude Code did not write result.json.",
                                  "changedFiles":[],
                                  "testCommands":[],
                                  "testStatus":"SKIPPED",
                                  "riskLevel":"HIGH",
                                  "prBody":"",
                                  "needHumanAction":true
                                }
                                """
                )
                .withExtraArtifact(
                        "claude-events.jsonl",
                        "/usr/local/bin/rd-claude-entrypoint: line 65: /usr/local/bin/claude: Argument list too long\n"
                );
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-cli-startup-failed",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("Argument list too long"));
        assertTrue(result.errorMessage().contains("container exited with code 126"));
        assertEquals("QA_INFRASTRUCTURE", result.rawResultJson().get("failureCategory"));
        assertEquals("HUMAN", result.rawResultJson().get("retryRecommendation"));
    }

    @Test
    void shouldPreserveQaContainerFailureWhenRepositoryCleanupAlsoFails() {
        CapturingRunner runner = CapturingRunner.withExitCodeAndResult(124, validQaResultJson("FAILED"))
                .withDurationMillis(1_200_000L)
                .withMetadata("timedOut", "true")
                .without("result.json");
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                new MutatingQaRepositoryPort(),
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-qa-timeout-dirty-repo",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("container timed out after 1200000 ms"));
        assertTrue(result.errorMessage().contains("container exited with code 124"));
        assertTrue(result.errorMessage().contains("QA left repository changes"));
        assertEquals("QA_INFRASTRUCTURE", result.rawResultJson().get("failureCategory"));
        assertEquals("false", result.dockerMetadataJson().get("qaRepositoryClean"));
    }

    @Test
    void shouldRejectQaResultWhenReferencedEvidenceWasNotCollected() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"));
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-missing-evidence",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("does not resolve to a collected artifact"));
    }

    @Test
    void shouldRejectQaPassThatDoesNotCoverTaskAcceptanceCriteria() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-unmapped-criterion",
                Map.of("repositoryPublishRequired", "false"),
                Map.of(
                        "agentRole", "QA_AGENT",
                        "acceptanceCriteriaJson", "[\"unmapped task criterion\"]"
                )
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains(
                "evidence is missing task acceptance criterion: unmapped task criterion"));
    }

    @Test
    void shouldNotCollectTransientQaWorkFilesAsDeliveryArtifacts() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts()
                .withExtraArtifact("qa-work/current-browser.sh", "temporary browser driver");
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-transient-work",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertFalse(result.artifacts().stream()
                .anyMatch(artifact -> artifact.name().startsWith("qa-work/")));
    }

    @Test
    void shouldResolveAndInjectAutoDetectedWebQaProfile() throws Exception {
        CapturingRunner runner = CapturingRunner.withResult(validBrowserQaResultJson())
                .withBrowserQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                new ViteRepositoryPort(),
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-qa-vite",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertEquals("http://127.0.0.1:5173", runner.request().env().get("RD_QA_BASE_URL"));
        assertEquals("/", runner.request().env().get("RD_QA_HEALTH_PATH"));
        assertEquals("127.0.0.1,localhost", runner.request().env().get("RD_QA_ALLOWED_HOSTS"));
        assertEquals(
                "http://127.0.0.1:5173;http://127.0.0.1:*;https://127.0.0.1:*;"
                        + "http://localhost:*;https://localhost:*",
                runner.request().env().get("PLAYWRIGHT_MCP_ALLOWED_ORIGINS")
        );
        assertEquals("/work/output/qa-work/playwright",
                runner.request().env().get("PLAYWRIGHT_MCP_OUTPUT_DIR"));
        assertEquals("300", runner.request().env().get("RD_QA_STARTUP_TIMEOUT_SECONDS"));
        assertEquals("1200000", runner.request().env().get("RD_QA_COMMAND_TIMEOUT_MILLIS"));
        assertEquals(1_200_000L, runner.request().executionTimeoutMillis());
        Path profile = temporaryDirectory.resolve("task-qa-vite/input/qa-profile.json");
        assertTrue(Files.isRegularFile(profile));
        assertTrue(Files.readString(profile).contains("\"decisionSource\":\"AUTO_DETECTION\""));
    }

    @Test
    void shouldInjectExplicitRegressionCommandsAsStructuredQaEnvironment() {
        CapturingRunner runner = CapturingRunner.withResult(
                        validBrowserQaResultJson().replace("AUTO_DETECTION", "TASK_OVERRIDE"))
                .withBrowserQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-qa-explicit-regression",
                Map.of("repositoryPublishRequired", "false"),
                Map.of(
                        "agentRole", "QA_AGENT",
                        "qaTaskOverrideJson", """
                                {"mode":"REQUIRED","baseUrl":"http://127.0.0.1:5173",\
                                "startCommand":"npm run dev -- --host 0.0.0.0",\
                                "healthPath":"/","allowedHosts":["127.0.0.1","localhost"],\
                                "regressionCommands":["npm test","npm run typecheck"]}
                                """
                )
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, result.status(), result.errorMessage());
        assertEquals("[\"npm test\",\"npm run typecheck\"]",
                runner.request().env().get("RD_QA_REGRESSION_COMMANDS_JSON"));
    }

    @Test
    void shouldRejectQaPassThatSkipsRequiredAutoDetectedBrowserValidation() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                new ViteRepositoryPort(),
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-qa-vite-skipped",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED_VALIDATION, result.status());
        assertTrue(result.errorMessage().contains("resolved QA profile requires browser validation"));
    }

    @Test
    void shouldFailQaWhenBrowserAgentChangesTrackedRepositoryFiles() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                new MutatingQaRepositoryPort(),
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-qa-mutated-repo",
                Map.of("repositoryPublishRequired", "false"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("QA left repository changes"));
        assertEquals("QA_INFRASTRUCTURE", result.rawResultJson().get("failureCategory"));
        assertEquals("HUMAN", result.rawResultJson().get("retryRecommendation"));
    }

    @Test
    void shouldAllowPlatformAppliedCandidatePatchButRejectQaChangesAfterThatBaseline() {
        CapturingRunner acceptedRunner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor acceptedExecutor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                acceptedRunner,
                COMMAND,
                new CandidatePatchBaselineRepositoryPort(false),
                null
        );

        RepairExecutionResult accepted = acceptedExecutor.execute(command(
                "task-qa-candidate-baseline",
                Map.of("repositoryPublishRequired", "false", "applyCandidatePatch", "true"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.SUCCESS, accepted.status(), accepted.errorMessage());

        CapturingRunner mutatedRunner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor mutatedExecutor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                mutatedRunner,
                COMMAND,
                new CandidatePatchBaselineRepositoryPort(true),
                null
        );

        RepairExecutionResult mutated = mutatedExecutor.execute(command(
                "task-qa-candidate-baseline-mutated",
                Map.of("repositoryPublishRequired", "false", "applyCandidatePatch", "true"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, mutated.status());
        assertTrue(mutated.errorMessage().contains("QA left repository changes"));
    }

    @Test
    void shouldFailLocalQaBeforeStartingAContainerWhenCandidatePatchWasNotApplied() {
        CapturingRunner runner = CapturingRunner.withResult(validQaResultJson("PASSED"))
                .withQaEvidenceArtifacts();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                RepairWorkspaceRepositoryPort.noop(),
                null
        );

        RepairExecutionResult result = executor.execute(command(
                "task-qa-missing-candidate-patch",
                Map.of("repositoryPublishRequired", "false", "applyCandidatePatch", "true"),
                Map.of("agentRole", "QA_AGENT")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("candidate patch to be applied"));
        assertFalse(runner.wasCalled(), "the provider container must not start without the Coding patch");
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
    void shouldDefaultRepositoryPublicationToLocalOnly() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        RecordingRepositoryPort repositoryPort = new RecordingRepositoryPort();
        DockerClaudeCodeExecutor executor = executor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                COMMAND,
                repositoryPort,
                null
        );

        RepairExecutionResult result = executor.execute(command("task-local-only-default", Map.of()));

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

        RepairExecutionResult result = executor.execute(command(
                "task-1001",
                Map.of("repositoryPublishRequired", "true")
        ));

        assertEquals(RepairExecutionStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("Claude Code API error 402: Insufficient Balance"));
        assertTrue(result.errorMessage().contains("container exited with code 1"));
    }

    @Test
    void shouldPersistFinalClaudeTokenUsageInProviderAttempts() throws Exception {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withExtraArtifact(
                        "claude-events.jsonl",
                        """
                                {"type":"assistant","session_id":"session-1","message":{"id":"message-1","usage":{"input_tokens":11,"output_tokens":12,"cache_creation_input_tokens":13,"cache_read_input_tokens":14}}}
                                {"type":"result","session_id":"session-1","total_cost":0.25}
                                """
                );
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command(
                "task-1001",
                Map.of("repositoryPublishRequired", "true")
        ));
        JsonNode attempts = OBJECT_MAPPER.readTree(result.dockerMetadataJson().get("providerAttemptsJson"));

        assertEquals(1, attempts.size());
        assertEquals(50L, attempts.get(0).path("totalTokens").asLong());
        assertEquals(11L, attempts.get(0).path("inputTokens").asLong());
        assertEquals(12L, attempts.get(0).path("outputTokens").asLong());
        assertEquals("0.25", attempts.get(0).path("estimatedCostUsd").asText());
        assertTrue(attempts.get(0).path("tokenUsageFinalized").asBoolean());
        assertEquals(1, attempts.get(0).path("attempt").asInt());
        assertFalse(attempts.get(0).path("provider").asText().isBlank());
        assertTrue(attempts.get(0).path("startedAtEpochMillis").asLong() > 0L);
        assertTrue(
                attempts.get(0).path("finishedAtEpochMillis").asLong()
                        >= attempts.get(0).path("startedAtEpochMillis").asLong()
        );
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
    void shouldResolveRoutedAuthTokenFromFallbackAndInjectIntoContainerEnv() {
        String envName = "RD_BOT_TEST_AUTH_TOKEN_FROM_FALLBACK_7479799581383987200";
        String secretValue = "test-secret-from-fallback";
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"));
        DockerClaudeCodeExecutor executor = executor(
                runner,
                COMMAND,
                List.of(provider("long-cat", Map.of(
                        "ANTHROPIC_BASE_URL", "https://api.longcat.chat/anthropic",
                        "RD_CLAUDE_AUTH_TOKEN_ENV", envName,
                        envName, ""
                ))),
                new ModelHealthStore(ModelCircuitBreakerPolicy.disabled()),
                name -> envName.equals(name) ? secretValue : ""
        );

        RepairExecutionResult result = executor.execute(command());

        assertEquals(RepairExecutionStatus.SUCCESS, result.status());
        assertTrue(runner.wasCalled());
        assertEquals(secretValue, runner.request().env().get(envName));
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

        RepairExecutionResult result = executor.execute(command(
                "task-1001",
                Map.of("repositoryPublishRequired", "true")
        ));

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
    void shouldPersistOnlyRedactedClaudeTraceInsteadOfRawEventStream() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withExtraArtifact("claude-events.jsonl", """
                        {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"PRIVATE_CHAIN token=super-secret-value"},{"type":"text","text":"Inspecting compiler ordering."},{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"git grep token=super-secret-value"}}]}}
                        {"type":"result","subtype":"success"}
                        """));

        RepairExecutionResult result = executor.execute(command());

        String persistedTrace = artifact(result, "claude-events.jsonl").metadataJson().get("contentPreview");
        assertNotNull(persistedTrace);
        assertTrue(persistedTrace.contains("\"version\":1"));
        assertTrue(persistedTrace.contains("Inspecting compiler ordering."));
        assertTrue(persistedTrace.contains("Bash: git"));
        assertFalse(persistedTrace.contains("PRIVATE_CHAIN"));
        assertFalse(persistedTrace.contains("super-secret-value"));
        assertFalse(persistedTrace.contains("git grep token"));
    }

    @Test
    void shouldCollectNestedQaEvidenceWithHashAndContentType() {
        DockerClaudeCodeExecutor executor = executor(CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withExtraArtifact("qa-evidence/screenshots/current.png", "fake-png-bytes")
                .withExtraArtifact("qa-evidence/browser/tracing-stop.log", "trace stopped"));

        RepairExecutionResult result = executor.execute(command());

        RepairArtifact screenshot = artifact(result, "qa-evidence/screenshots/current.png");
        assertEquals(RepairArtifactType.QA_SCREENSHOT, screenshot.type());
        assertEquals("14", screenshot.metadataJson().get("bytes"));
        assertEquals(64, screenshot.metadataJson().get("sha256").length());
        assertEquals("image/png", screenshot.metadataJson().get("contentType"));
        assertEquals(
                RepairArtifactType.QA_COMMAND_LOG,
                artifact(result, "qa-evidence/browser/tracing-stop.log").type()
        );
    }

    @Test
    void shouldKeepLargeQaManifestAvailableForHostIntegrityValidation() {
        CapturingRunner runner = CapturingRunner.withResult(validResultJson("SUCCESS"))
                .withQaEvidenceArtifacts();
        for (int index = 0; index < 500; index++) {
            runner = runner.withExtraArtifact(
                    "qa-evidence/http/response-%03d.json".formatted(index),
                    "{\"status\":200}"
            );
        }
        DockerClaudeCodeExecutor executor = executor(runner);

        RepairExecutionResult result = executor.execute(command());

        RepairArtifact manifest = artifact(result, "qa-evidence/manifest.json");
        assertTrue(Long.parseLong(manifest.metadataJson().get("bytes")) > 64_000L);
        assertNotNull(manifest.metadataJson().get("contentPreview"));
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

        RepairExecutionResult result = executor.execute(command(
                "task-1001",
                Map.of("repositoryPublishRequired", "true")
        ));

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

        RepairExecutionResult result = executor.execute(command(
                "task-1001",
                Map.of("repositoryPublishRequired", "true")
        ));

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
                new RepairExecutionWatchdog.Policy(1_000L, new BigDecimal("36.00")),
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
        assertEquals("CNY", alertSink.alerts().get(1).metadata().get("currency"));
        assertEquals("52.2000", alertSink.alerts().get(1).metadata().get("estimatedSpendCny"));
        assertEquals("36.00", alertSink.alerts().get(1).metadata().get("thresholdSpendCny"));
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
        Path source = dockerSourceRoot().resolve("impl/DockerClaudeCodeExecutor.java");
        assertTrue(Files.exists(source), "DockerClaudeCodeExecutor source must exist");
        String body = Files.readString(source);

        assertFalse(body.contains("import com.wish.rd.exec.repair.github."));
        assertFalse(body.contains("import com.wish.rd.rag.stream.RagStreamTaskRegistry"));
        assertFalse(body.contains("RagStreamTaskRegistry"));
    }

    @Test
    void publicExecutorShouldHaveJavadoc() throws IOException {
        Path source = dockerSourceRoot().resolve("impl/DockerClaudeCodeExecutor.java");
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
        return executor(runner, command, providers, healthStore, AuthEnvironmentResolverForTests.missing());
    }

    private DockerClaudeCodeExecutor executor(
            ContainerRunnerPort runner,
            List<String> command,
            List<ClaudeCodeModelProvider> providers,
            ModelHealthStore healthStore,
            DockerClaudeCodeExecutor.AuthEnvironmentResolver authEnvironmentResolver
    ) {
        DockerClaudeCodeExecutor.Configuration configuration = new DockerClaudeCodeExecutor.Configuration(
                "rd-bot/claude-code:test",
                "rd-bot/claude-code-qa:test",
                command,
                "none",
                true,
                false,
                providers,
                new DockerClaudeCodeExecutor.QaSkillConfiguration(
                        temporaryDirectory.resolve("qa-skill").toString(),
                        "qa-playwright-cli",
                        "1.0.1",
                        "sha256:abc123",
                        "{\"allowed\":true}"
                )
        ).withHandoffSkill(new DockerClaudeCodeExecutor.HandoffSkillConfiguration(
                temporaryDirectory.resolve("handoff-skill").toString(),
                "role-handoff-document",
                "1.0.0",
                "sha256:handoff123",
                "{\"allowed\":true}"
        ));
        return new DockerClaudeCodeExecutor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                new StructuredResultValidator(),
                configuration,
                RepairWorkspaceRepositoryPort.noop(),
                null,
                healthStore,
                DockerExecutionRegistry.noop(),
                ExecutionAllowlistPolicy.disabled(),
                authEnvironmentResolver
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
                "rd-bot/claude-code-qa:test",
                command,
                "none",
                true,
                false,
                List.of(ClaudeCodeModelProvider.defaultAnthropic()),
                new DockerClaudeCodeExecutor.QaSkillConfiguration(
                        temporaryDirectory.resolve("qa-skill").toString(),
                        "qa-playwright-cli",
                        "1.0.1",
                        "sha256:abc123",
                        "{\"allowed\":true}"
                )
        ).withHandoffSkill(new DockerClaudeCodeExecutor.HandoffSkillConfiguration(
                temporaryDirectory.resolve("handoff-skill").toString(),
                "role-handoff-document",
                "1.0.0",
                "sha256:handoff123",
                "{\"allowed\":true}"
        ));
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
        String failureCategory = "FAILED".equals(status) ? "PRODUCT_DEFECT" : "NONE";
        String retryRecommendation = "FAILED".equals(status) ? "CODING_AGENT" : "NONE";
        return """
                {
                  "status": "%s",
                  "summary": "%s",
                  "failureCategory": "%s",
                  "retryRecommendation": "%s",
                  "browserValidation": {
                    "required": false,
                    "performed": false,
                    "decisionSource": "NOT_APPLICABLE",
                    "baseUrl": "",
                    "browser": "chromium",
                    "viewports": []
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "文档必须包含 marker",
                      "scope": "CURRENT",
                      "command": "grep -q marker docs/example.md",
                      "status": "%s",
                      "exitCode": %s,
                      "durationMillis": 10,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "关键回归测试",
                      "scope": "REGRESSION",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 20,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """.formatted(
                status,
                summary,
                failureCategory,
                retryRecommendation,
                acceptanceStatus,
                "FAILED".equals(status) ? 1 : 0
        );
    }

    private static String validBrowserQaResultJson() {
        return """
                {
                  "status": "PASSED",
                  "summary": "browser current and regression checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
                  "browserValidation": {
                    "required": true,
                    "performed": true,
                    "decisionSource": "AUTO_DETECTION",
                    "baseUrl": "http://127.0.0.1:5173",
                    "browser": "chromium",
                    "viewports": ["desktop-1440x900", "mobile-390x844"]
                  },
                  "acceptanceResults": [
                    {
                      "criteria": "current browser flow",
                      "scope": "CURRENT",
                      "command": "playwright-cli screenshot",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 10,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": [
                        "qa-evidence/screenshots/current-desktop.png",
                        "qa-evidence/screenshots/current-mobile.png",
                        "qa-evidence/traces/current.zip",
                        "qa-evidence/console/current.log",
                        "qa-evidence/network/current.log"
                      ]
                    },
                    {
                      "criteria": "critical regression",
                      "scope": "REGRESSION",
                      "command": "npm test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 20,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
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

    private static final class AuthEnvironmentResolverForTests {

        private AuthEnvironmentResolverForTests() {
        }

        private static DockerClaudeCodeExecutor.AuthEnvironmentResolver missing() {
            return envName -> "";
        }
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

        CapturingRunner withQaEvidenceArtifacts() {
            return withExtraArtifact("qa-evidence/manifest.json", "{}")
                    .withExtraArtifact("qa-evidence/commands/current.log", "current command output")
                    .withExtraArtifact("qa-evidence/commands/regression.log", "regression command output");
        }

        CapturingRunner withBrowserQaEvidenceArtifacts() {
            return withQaEvidenceArtifacts()
                    .withExtraArtifact("qa-evidence/screenshots/current-desktop.png", "fake desktop screenshot")
                    .withExtraArtifact("qa-evidence/screenshots/current-mobile.png", "fake mobile screenshot")
                    .withExtraArtifact("qa-evidence/traces/current.zip", "fake trace")
                    .withExtraArtifact("qa-evidence/console/current.log", "no console errors")
                    .withExtraArtifact("qa-evidence/network/current.log", "GET / 200");
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
                Path extraArtifact = request.outputDirectory().resolve(entry.getKey());
                Files.createDirectories(extraArtifact.getParent());
                Files.writeString(extraArtifact, entry.getValue(), StandardCharsets.UTF_8);
            }
            if (extraArtifacts.containsKey("qa-evidence/manifest.json")) {
                writeEvidenceManifest(request.outputDirectory());
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

        private static void writeEvidenceManifest(Path outputDirectory) throws IOException {
            Path evidenceDirectory = outputDirectory.resolve("qa-evidence");
            Path manifestPath = evidenceDirectory.resolve("manifest.json");
            List<Map<String, Object>> entries;
            try (var files = Files.walk(evidenceDirectory)) {
                entries = files
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.equals(manifestPath))
                        .sorted()
                        .map(path -> {
                            try {
                                byte[] body = Files.readAllBytes(path);
                                return Map.<String, Object>of(
                                        "path", outputDirectory.relativize(path).toString().replace('\\', '/'),
                                        "bytes", body.length,
                                        "sha256", java.util.HexFormat.of().formatHex(
                                                java.security.MessageDigest.getInstance("SHA-256").digest(body)
                                        )
                                );
                            } catch (IOException | java.security.NoSuchAlgorithmException exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                        .toList();
            }
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(manifestPath.toFile(), Map.of("version", 1, "artifacts", entries));
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

    private static final class ViteRepositoryPort implements RepairWorkspaceRepositoryPort {

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) throws IOException {
            Files.writeString(workspace.repoDirectory().resolve("package.json"), """
                    {"scripts":{"dev":"vite"},"devDependencies":{"vite":"latest"}}
                    """, StandardCharsets.UTF_8);
            return new RepositoryOperationResult(Map.of("prepared", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            return new RepositoryOperationResult(Map.of());
        }
    }

    private static final class MutatingQaRepositoryPort implements RepairWorkspaceRepositoryPort {

        private int stateCalls;

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) {
            return new RepositoryOperationResult(Map.of("prepared", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            return new RepositoryOperationResult(Map.of());
        }

        @Override
        public RepositoryState repositoryState(RepairJobCommand command, RepairWorkspace workspace) {
            if (stateCalls++ == 0) {
                return RepositoryState.cleanState();
            }
            return new RepositoryState(true, false, " M src/App.tsx");
        }
    }

    private static final class CandidatePatchBaselineRepositoryPort implements RepairWorkspaceRepositoryPort {

        private final boolean mutateAfterProvider;
        private int stateCalls;

        private CandidatePatchBaselineRepositoryPort(boolean mutateAfterProvider) {
            this.mutateAfterProvider = mutateAfterProvider;
        }

        @Override
        public RepositoryOperationResult prepare(RepairJobCommand command, RepairWorkspace workspace) {
            return new RepositoryOperationResult(Map.of("candidatePatchApplied", "true"));
        }

        @Override
        public RepositoryOperationResult publish(RepairJobCommand command, RepairWorkspace workspace) {
            return new RepositoryOperationResult(Map.of());
        }

        @Override
        public RepositoryState repositoryState(RepairJobCommand command, RepairWorkspace workspace) {
            if (stateCalls++ == 0 || !mutateAfterProvider) {
                return new RepositoryState(true, false, "M  README.md");
            }
            return new RepositoryState(true, false, "M  README.md\n M src/Unexpected.java");
        }
    }
}
