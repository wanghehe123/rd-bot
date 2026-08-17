package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutorAdapter;
import com.wish.rd.bootstrap.executor.impl.ObjectStorageQaEvidencePublisher;
import com.wish.rd.bootstrap.executor.impl.ObjectStorageRoleHandoffPublisher;
import com.wish.rd.bootstrap.executor.impl.RoleHandoffAttachmentResolver;
import com.wish.rd.bootstrap.executor.impl.RoleHandoffProperties;
import com.wish.rd.bootstrap.oracle.HostOwnedAssertionGate;
import com.wish.rd.bootstrap.oracle.impl.HttpJsonPathAssertionRunner;
import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.impl.InMemoryHostAssertionBundleStore;
import com.wish.rd.engine.oracle.model.AssertionType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.model.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.model.RequirementExecutionResult;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.oracle.model.HostVerifierWorkspace;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileService;
import com.wish.rd.rag.project.runtime.impl.InMemoryProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfileCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutorAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void shouldPreserveRequirementExecutionEvidenceWithoutCreatingPullRequest() throws IOException {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "需求已实现",
                "",
                List.of(),
                Map.of(
                        "prBody", "## Summary\n- add requirement flow",
                        "changedFiles", "src/App.tsx\nsrc/api.ts"
                ),
                Map.of("image", "rd-bot-claude:latest"),
                Map.of(),
                Map.of("testStatus", "PASSED", "testCommands", "./mvnw test"),
                Map.of("riskLevel", "LOW"),
                ""
        );
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor, codePlatform);

        RequirementExecutionResult result = adapter.execute(request());

        assertTrue(result.success());
        assertEquals("", result.pullRequestUrl());
        assertNull(codePlatform.command());

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertEquals("SUCCESS", resultJson.path("status").asText());
        assertEquals("需求已实现", resultJson.path("summary").asText());
        assertEquals("## Summary\n- add requirement flow", resultJson.path("prBody").asText());
        assertEquals("src/App.tsx\nsrc/api.ts", resultJson.path("changedFiles").asText());
        assertEquals("PASSED", resultJson.path("testMetadata").path("testStatus").asText());
        assertEquals("./mvnw test", resultJson.path("testMetadata").path("testCommands").asText());
        assertEquals("LOW", resultJson.path("riskMetadata").path("riskLevel").asText());
        assertEquals(0, resultJson.path("codePlatformMetadata").size());
    }

    @Test
    void shouldExposeExecutorArtifactsForStagePersistence() throws IOException {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "需求已实现",
                "",
                List.of(
                        new RepairArtifact(
                                RepairArtifactType.PATCH_DIFF,
                                "patch.diff",
                                "rd-artifact://task-1001/coding/patch.diff",
                                "代码补丁",
                                Map.of("contentPreview", "diff --git a/src/App.java b/src/App.java")
                        ),
                        new RepairArtifact(
                                RepairArtifactType.TEST_LOG,
                                "test.log",
                                "rd-artifact://task-1001/coding/test.log",
                                "测试日志",
                                Map.of("contentPreview", "./mvnw test passed")
                        )
                ),
                Map.of(
                        "status", "SUCCESS",
                        "summary", "需求已实现",
                        "prBody", "## Summary\n- done",
                        "changedFiles", "src/App.java",
                        "testCommands", "./mvnw test",
                        "testStatus", "PASSED"
                ),
                Map.of(
                        "image", "rd-bot/claude-code:local",
                        "containerId", "container-123",
                        "workspacePath", "/tmp/rd-bot/work",
                        "exitCode", "0"
                ),
                Map.of(),
                Map.of("testStatus", "PASSED", "testCommands", "./mvnw test", "testsFailed", "0"),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        RequirementExecutionResult result = adapter.execute(request());

        JsonNode stageArtifacts = OBJECT_MAPPER.readTree(result.resultJson()).path("stageArtifacts");
        assertTrue(stageArtifacts.isArray());
        assertEquals("PATCH_DIFF", stageArtifacts.get(0).path("type").asText());
        assertEquals("rd-artifact://task-1001/coding/patch.diff", stageArtifacts.get(0).path("uri").asText());
        assertEquals("diff --git a/src/App.java b/src/App.java",
                stageArtifacts.get(0).path("contentPreview").asText());
        assertEquals("TEST_LOG", stageArtifacts.get(1).path("type").asText());
        assertEquals("DOCKER_METADATA", stageArtifacts.get(2).path("type").asText());
        assertEquals("rd-bot/claude-code:local",
                stageArtifacts.get(2).path("contentPreview").path("image").asText());
    }

    @Test
    void shouldNotCreatePullRequestForReviewOnlyAgentStage() {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "需求评审通过",
                "",
                List.of(),
                Map.of(
                        "decision", "APPROVED",
                        "feasibility", "CAN_DO"
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor, codePlatform);

        RequirementExecutionResult result = adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "review requirement",
                AgentRole.REQUIREMENT_REVIEWER,
                "{\"packageId\":\"ctx-1\"}",
                false,
                "[]"
        ));

        assertTrue(result.success());
        assertEquals("", result.pullRequestUrl());
        assertNull(codePlatform.command());
    }

    @Test
    void shouldExpandCompleteAgentResultJsonFromOpenAiCompatibleExecutor() throws IOException {
        String agentResultJson = """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "risks": ["需要补充回滚说明"],
                  "acceptanceCoverage": ["测试通过"]
                }
                """;
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "需求评审通过",
                "",
                List.of(),
                Map.of("__agentResultJson", agentResultJson),
                Map.of("provider", "minimax", "protocol", "openai-chat-completions"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        RequirementExecutionResult result = adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "review requirement",
                AgentRole.REQUIREMENT_REVIEWER,
                "{\"packageId\":\"ctx-1\"}",
                false,
                "[]"
        ));

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertTrue(result.success());
        assertEquals("APPROVED", resultJson.path("decision").asText());
        assertEquals("CAN_DO", resultJson.path("feasibility").asText());
        assertTrue(resultJson.path("missingInformation").isArray());
        assertEquals("需要补充回滚说明", resultJson.path("risks").get(0).asText());
        assertEquals("openai-chat-completions", resultJson.path("dockerMetadata").path("protocol").asText());
    }

    @Test
    void shouldRejectLegacySuccessfulQaResultInsteadOfFabricatingEvidence() throws IOException {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA verified with real shell commands",
                "",
                List.of(),
                Map.of(
                        "status", "SUCCESS",
                        "summary", "QA verified with real shell commands",
                        "prBody", "## QA\\n- test -s docs/rd-bot-production-smoke.md -> PASSED",
                        "testSummary", "test -s docs/rd-bot-production-smoke.md and grep marker both PASSED"
                ),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of("testStatus", "PASSED", "testCommands", "test -s docs/rd-bot-production-smoke.md"),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        RequirementExecutionResult result = adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "qa",
                AgentRole.QA_AGENT,
                "{}",
                false,
                "[]"
        ));

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("QA evidence protocol invalid"));
        assertEquals("SUCCESS", resultJson.path("status").asText());
        assertFalse(resultJson.has("acceptanceResults"));
        assertFalse(result.resultJson().contains("qa-inline-log-"));
    }

    @Test
    void shouldRejectStrictQaResultWhenEvidenceReferencesAreNotCollected() {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA protocol claims evidence that does not exist",
                "",
                List.of(),
                Map.of("__agentResultJson", strictQaResultJson()),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );

        RequirementExecutionResult result = new EngineRequirementExecutorAdapter(repairExecutor).execute(
                qaRequest()
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("QA evidence bundle invalid"));
        assertTrue(result.errorMessage().contains("does not resolve to a collected artifact"));
    }

    @Test
    void shouldAcceptStrictQaResultWhenEveryEvidenceReferenceIsCollected() {
        List<RepairArtifact> artifacts = qaArtifacts();
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA current and regression checks passed",
                "",
                artifacts,
                Map.of("__agentResultJson", strictQaResultJson()),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );

        RequirementExecutionResult result = new EngineRequirementExecutorAdapter(repairExecutor).execute(
                qaRequest()
        );

        assertTrue(result.success(), result::errorMessage);
    }

    @Test
    void shouldFreezeHostAssertionsBeforeQaAndExposeOnlyOpaqueContracts() throws Exception {
        Files.writeString(tempDirectory.resolve("current.txt"), "current");
        Files.writeString(tempDirectory.resolve("regression.txt"), "regression");
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(AssertionType.FILE_EXISTS, new FileAssertionRunner())),
                store,
                (command, frozen) -> new HostVerifierWorkspace(tempDirectory, "", Map.of())
        );
        List<RepairJobCommand> commands = new java.util.ArrayList<>();
        RepairExecutorPort repairExecutor = command -> {
            commands.add(command);
            String agentJson = command.contextJson().containsKey("hostAssertionContracts")
                    ? strictQaResultJsonWithHostAssertionEchoes(command.contextJson().get("hostAssertionContracts"))
                    : strictQaResultJson();
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    "QA current and regression checks passed",
                    "",
                    qaArtifacts(),
                    Map.of("__agentResultJson", agentJson),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        };

        RequirementExecutionResult result = new EngineRequirementExecutorAdapter(repairExecutor, gate).execute(
                new RequirementExecutionRequest(
                        "task-1001",
                        taskWithHostAssertionBundle(hostAssertionDefinitionsJson()),
                        List.of(),
                        "qa",
                        AgentRole.QA_AGENT,
                        "{}",
                        false,
                        "[]",
                        "stage-host-assertions",
                        ""
                )
        );

        assertTrue(result.success(), result::errorMessage);
        assertEquals(1, commands.size());
        RepairJobCommand command = commands.getFirst();
        assertTrue(command.contextJson().containsKey("hostAssertionContracts"));
        JsonNode contracts = OBJECT_MAPPER.readTree(command.contextJson().get("hostAssertionContracts"));
        assertEquals(2, contracts.size());
        assertEquals("CURRENT", contracts.get(0).path("scope").asText());
        assertEquals("REGRESSION", contracts.get(1).path("scope").asText());
        assertEquals(1L, contracts.get(0).path("version").asLong());
        assertFalse(contracts.get(0).has("specs"));
        assertEquals("[]", command.contextJson().get("acceptanceCriteriaJson"));
    }

    @Test
    void shouldRejectAgentControlledHostAssertionFields() {
        String qaResult = strictQaResultJson().strip();
        String agentJson = qaResult.substring(0, qaResult.length() - 1) + """
                  ,
                  "hostAssertionBundle": {"specs": []}
                }
                """;
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA current and regression checks passed",
                "",
                qaArtifacts(),
                Map.of("__agentResultJson", agentJson),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );

        RequirementExecutionResult result = new EngineRequirementExecutorAdapter(repairExecutor).execute(qaRequest());

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("QA evidence protocol invalid"), result::errorMessage);
        assertTrue(result.errorMessage().contains("hostAssertionBundle is not accepted"), result::errorMessage);
    }

    @Test
    void shouldFailGreenQaWhenFrozenHttpJsonPathAssertionFails() {
        InMemoryHostAssertionBundleStore store = new InMemoryHostAssertionBundleStore();
        HostOwnedAssertionGate gate = new HostOwnedAssertionGate(
                new HostAssertionOracle(Map.of(
                        AssertionType.HTTP_JSONPATH,
                        new HttpJsonPathAssertionRunner((uri, timeout) -> "{\"ok\":false}")
                )),
                store,
                (command, frozen) -> new HostVerifierWorkspace(tempDirectory, "http://127.0.0.1:9", Map.of())
        );
        RepairExecutorPort repairExecutor = command -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA current and regression checks passed",
                "",
                qaArtifacts(),
                Map.of("__agentResultJson", strictQaResultJsonWithHostAssertionEchoes(
                        command.contextJson().get("hostAssertionContracts")
                )),
                Map.of("provider", "long-cat", "exitCode", "0"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );

        RequirementExecutionResult result = new EngineRequirementExecutorAdapter(repairExecutor, gate).execute(
                new RequirementExecutionRequest(
                        "task-1001",
                        taskWithHostAssertionBundle(hostHttpJsonPathDefinitionsJson()),
                        List.of(),
                        "qa",
                        AgentRole.QA_AGENT,
                        "{}",
                        false,
                        "[]",
                        "stage-host-jsonpath",
                        ""
                )
        );

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Host-owned assertion failed"), result::errorMessage);
        assertTrue(result.errorMessage().contains("JSONPath"), result::errorMessage);
    }

    @Test
    void shouldFailClosedBeforeQaWhenExplicitHostAssertionsCannotFreeze() {
        AtomicInteger executorInvocations = new AtomicInteger();
        RepairExecutorPort repairExecutor = ignored -> {
            executorInvocations.incrementAndGet();
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    "unexpected QA execution",
                    "",
                    qaArtifacts(),
                    Map.of("__agentResultJson", strictQaResultJson()),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        };

        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                new EngineRequirementExecutorAdapter(repairExecutor).execute(
                        new RequirementExecutionRequest(
                                "task-1001",
                                taskWithHostAssertionBundle(hostAssertionDefinitionsJson()),
                                List.of(),
                                "qa",
                                AgentRole.QA_AGENT,
                                "{}",
                                false,
                                "[]",
                                "stage-host-assertions-unavailable",
                                ""
                        )
                )
        );

        assertTrue(exception.getMessage().contains("Host assertion bundle store"), exception::getMessage);
        assertEquals(0, executorInvocations.get());
    }

    @Test
    void shouldPublishQaEvidenceBeforeReturningStageArtifacts() throws Exception {
        List<RepairArtifact> artifacts = localQaArtifacts();
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "QA current and regression checks passed",
                "",
                artifacts,
                Map.of("__agentResultJson", strictQaResultJson()),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                repairExecutor,
                null,
                null,
                null,
                new ObjectStorageQaEvidencePublisher(new InMemoryObjectStorageService())
        );

        RequirementExecutionResult result = adapter.execute(qaRequest());

        assertTrue(result.success(), result::errorMessage);
        JsonNode stageArtifacts = OBJECT_MAPPER.readTree(result.resultJson()).path("stageArtifacts");
        assertTrue(stageArtifacts.get(0).path("uri").asText().startsWith("s3://rd-qa-evidence/"));
        assertEquals("qa-evidence/manifest.json",
                stageArtifacts.get(0).path("metadataJson").path("artifactName").asText());
    }

    @Test
    void shouldTreatQaFailedReportAsRequirementFailure() throws IOException {
        String agentResultJson = strictFailedQaResultJson();
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Marker text was not found by real grep command",
                "",
                qaArtifacts(),
                Map.of("__agentResultJson", agentResultJson),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        RequirementExecutionResult result = adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "qa",
                AgentRole.QA_AGENT,
                "{}",
                false,
                "[]"
        ));

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("QA_AGENT failed"));
        assertEquals("FAILED", resultJson.path("status").asText());
        assertEquals("FAILED", resultJson.path("acceptanceResults").get(0).path("status").asText());
        assertEquals("grep -q marker docs/example.md",
                resultJson.path("acceptanceResults").get(0).path("command").asText());
    }

    @Test
    void shouldNormalizeLegacySuccessfulSolutionResultToSolutionPlanProtocol() throws IOException {
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Plan created from legacy executor output",
                "",
                List.of(),
                Map.of(
                        "status", "SUCCESS",
                        "summary", "Plan created from legacy executor output",
                        "changedFiles", "docs/rd-bot-production-smoke.md",
                        "prBody", "## Plan\\n- test -s docs/rd-bot-production-smoke.md"
                ),
                Map.of("provider", "long-cat"),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        );
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        RequirementExecutionResult result = adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "solution",
                AgentRole.SOLUTION_ARCHITECT,
                "{}",
                false,
                "[]"
        ));

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertTrue(result.success());
        assertEquals("docs/rd-bot-production-smoke.md", resultJson.path("affectedFiles").get(0).asText());
        assertTrue(resultJson.path("implementationSteps").isArray());
        assertTrue(resultJson.path("implementationSteps").size() > 0);
        assertEquals("测试通过", resultJson.path("acceptanceMapping").get(0).path("criteria").asText());
        assertTrue(resultJson.path("testPlan").get(0).path("command").asText().contains("manual verification"));
    }

    @Test
    void shouldRequireExplicitPullRequestPermissionBeforePublishingCodingRepository() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor();
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(repairExecutor);

        adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "review requirement",
                AgentRole.REQUIREMENT_REVIEWER,
                "{}",
                false,
                "[]"
        ));
        adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "write code",
                AgentRole.CODING_AGENT,
                "{}",
                false,
                "[]"
        ));
        adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "write code and publish",
                AgentRole.CODING_AGENT,
                "{}",
                true,
                "[]"
        ));
        adapter.execute(new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "qa",
                AgentRole.QA_AGENT,
                "{}",
                false,
                "[]"
        ));

        assertEquals("false", repairExecutor.commands().get(0).policyJson().get("repositoryPublishRequired"));
        assertEquals("false", repairExecutor.commands().get(1).policyJson().get("repositoryPublishRequired"));
        assertEquals("true", repairExecutor.commands().get(2).policyJson().get("repositoryPublishRequired"));
        assertEquals("false", repairExecutor.commands().get(3).policyJson().get("repositoryPublishRequired"));
        assertEquals("task-1001-requirement_reviewer", repairExecutor.commands().get(0).taskId());
        assertEquals("task-1001", repairExecutor.commands().get(1).taskId());
        assertEquals("task-1001", repairExecutor.commands().get(2).taskId());
        assertEquals("task-1001-qa_agent", repairExecutor.commands().get(3).taskId());
        assertEquals("task-1001", repairExecutor.commands().get(0).contextJson().get("taskId"));
        assertEquals("task-1001", repairExecutor.commands().get(3).contextJson().get("taskId"));
    }

    @Test
    void shouldInjectPersistedTaskQaOverrideIntoDockerContext() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor();
        QaValidationProfileService profileService = new QaValidationProfileService(new InMemoryQaProfileStore());
        profileService.updateTask("task-1001", new QaValidationProfileCommand(
                "REQUIRED",
                "http://127.0.0.1:4173",
                "npm run preview -- --host 0.0.0.0",
                "/health",
                List.of("127.0.0.1", "localhost"),
                List.of("npm test"),
                null,
                null
        ));
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                repairExecutor,
                null,
                null,
                profileService
        );

        adapter.execute(qaRequest());

        String profileJson = repairExecutor.commands().getFirst().contextJson().get("qaTaskOverrideJson");
        assertTrue(profileJson.contains("\"mode\":\"REQUIRED\""));
        assertTrue(profileJson.contains("\"baseUrl\":\"http://127.0.0.1:4173\""));
        assertTrue(profileJson.contains("\"regressionCommands\":[\"npm test\"]"));
    }

    @Test
    void shouldInjectOnlyVerifiedProjectRuntimeImageIntoExecutorPolicy() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor();
        ProjectRuntimeProfileService runtimeProfiles = new ProjectRuntimeProfileService(
                new InMemoryProjectRuntimeProfileStore()
        );
        runtimeProfiles.save(new ProjectRuntimeProfileCommand(
                "7486000000000000005",
                "CODING_AGENT",
                "CLAUDE_CODE",
                "rd-bot/project-7486000000000000005-coding:verified",
                "s3://rd-project-runtime-dockerfiles/coding.Dockerfile",
                "c".repeat(64),
                "Dockerfile",
                ProjectRuntimeProfileService.RUNTIME_PROFILE_CONTRACT_MARKER + "; claude runtime smoke verified"
        ));
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                repairExecutor,
                null,
                null,
                null,
                null,
                runtimeProfiles
        );

        adapter.execute(new RequirementExecutionRequest(
                "task-project-runtime",
                projectTask(),
                List.of(),
                "implement",
                AgentRole.CODING_AGENT,
                "{}",
                false,
                "[]"
        ));

        Map<String, String> policy = repairExecutor.commands().getFirst().policyJson();
        assertEquals("rd-bot/project-7486000000000000005-coding:verified", policy.get("runtimeImage"));
        assertEquals("true", policy.get("runtimeImageVerified"));
        assertEquals("CLAUDE_CODE", policy.get("runtimeAgentType"));
    }

    @Test
    void shouldPersistAndRematerializePrivateMarkdownHandoffWithoutPassingRawRoleJson() throws Exception {
        byte[] handoffBytes = """
                # Implementation Plan

                Update the serializer and run the focused regression test.
                """.getBytes(StandardCharsets.UTF_8);
        Path handoffPath = tempDirectory.resolve("handoff").resolve("next.md");
        Files.createDirectories(handoffPath.getParent());
        Files.write(handoffPath, handoffBytes);
        RepairArtifact handoffArtifact = new RepairArtifact(
                RepairArtifactType.HANDOFF_MARKDOWN,
                "handoff/next.md",
                handoffPath.toUri().toString(),
                "Architecture plan for coding",
                Map.of(
                        "bytes", String.valueOf(handoffBytes.length),
                        "sha256", sha256(handoffBytes),
                        "contentType", "text/markdown"
                )
        );
        List<RepairJobCommand> commands = new java.util.ArrayList<>();
        RepairExecutorPort repairExecutor = command -> {
            commands.add(command);
            if ("SOLUTION_ARCHITECT".equals(command.contextJson().get("agentRole"))) {
                return new RepairExecutionResult(
                        RepairExecutionStatus.SUCCESS,
                        "Architecture is ready",
                        "",
                        List.of(handoffArtifact),
                        Map.of("__agentResultJson", """
                                {
                                  "summary": "Architecture is ready",
                                  "next_prompt": {
                                    "targetRole": "CODING_AGENT",
                                    "summary": "Use the verified Markdown plan",
                                    "handoffArtifact": "handoff/next.md"
                                  }
                                }
                                """),
                        Map.of("containerId", "raw-container-metadata"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        ""
                );
            }
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    "Coding is ready",
                    "",
                    List.of(),
                    Map.of("status", "SUCCESS"),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        };
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        RoleHandoffProperties handoffProperties = new RoleHandoffProperties();
        handoffProperties.setMaxTokens(512);
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                repairExecutor,
                null,
                null,
                null,
                null,
                null,
                new ObjectStorageRoleHandoffPublisher(storage, handoffProperties),
                new RoleHandoffAttachmentResolver(storage, handoffProperties)
        );

        RequirementExecutionResult architectResult = adapter.execute(new RequirementExecutionRequest(
                "task-handoff",
                task(),
                List.of(),
                "design",
                AgentRole.SOLUTION_ARCHITECT,
                "{}",
                false,
                "{\"version\":1,\"stages\":[]}"
        ));

        assertTrue(architectResult.success(), architectResult::errorMessage);
        JsonNode handoff = OBJECT_MAPPER.readTree(architectResult.resultJson()).path("roleHandoff");
        assertEquals("CODING_AGENT", handoff.path("targetRole").asText());
        assertTrue(handoff.path("artifactUri").asText().startsWith("s3://rd-role-handoffs/"));
        String upstream = "{\"version\":1,\"stages\":[{\"role\":\"SOLUTION_ARCHITECT\",\"handoff\":"
                + handoff + "}]}";

        adapter.execute(new RequirementExecutionRequest(
                "task-handoff",
                task(),
                List.of(),
                "implement",
                AgentRole.CODING_AGENT,
                "{}",
                false,
                upstream
        ));

        RepairJobCommand codingCommand = commands.get(1);
        assertEquals(1, codingCommand.attachments().size());
        assertEquals("handoff-solution_architect.md", codingCommand.attachments().getFirst().filename());
        assertEquals(new String(handoffBytes, StandardCharsets.UTF_8),
                new String(codingCommand.attachments().getFirst().content(), StandardCharsets.UTF_8));
        String localManifest = codingCommand.contextJson().get("upstreamHandoffManifestJson");
        assertTrue(localManifest.contains("/work/input/attachments/handoff-solution_architect.md"));
        assertFalse(localManifest.contains("s3://"));
        assertEquals("512", codingCommand.contextJson().get("roleHandoffMaxTokens"));
        assertFalse(codingCommand.contextJson().containsKey("upstreamResultJson"));
    }

    @Test
    void shouldAttachAndApplyVerifiedCodingPatchForLocalQa() throws Exception {
        byte[] patchBytes = """
                diff --git a/README.md b/README.md
                index 0000000..1111111 100644
                --- a/README.md
                +++ b/README.md
                @@ -1 +1 @@
                -before
                +after
                """.getBytes(StandardCharsets.UTF_8);
        Path patchPath = tempDirectory.resolve("coding").resolve("patch.diff");
        Files.createDirectories(patchPath.getParent());
        Files.write(patchPath, patchBytes);
        RepairArtifact candidatePatch = new RepairArtifact(
                RepairArtifactType.PATCH_DIFF,
                "patch.diff",
                patchPath.toUri().toString(),
                "Candidate coding patch",
                Map.of(
                        "bytes", String.valueOf(patchBytes.length),
                        "sha256", sha256(patchBytes),
                        "contentType", "text/x-diff"
                )
        );
        List<RepairJobCommand> commands = new java.util.ArrayList<>();
        RepairExecutorPort repairExecutor = command -> {
            commands.add(command);
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    "stage complete",
                    "",
                    "CODING_AGENT".equals(command.contextJson().get("agentRole"))
                            ? List.of(candidatePatch)
                            : List.of(),
                    Map.of("__agentResultJson", """
                            {"status":"SUCCESS","summary":"stage complete","changedFiles":[],"testCommands":[],
                            "testStatus":"PASSED","riskLevel":"LOW","prBody":"","needHumanAction":false}
                            """),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        };
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        RoleHandoffProperties handoffProperties = new RoleHandoffProperties();
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                repairExecutor,
                null,
                null,
                null,
                null,
                null,
                new ObjectStorageRoleHandoffPublisher(storage, handoffProperties),
                new RoleHandoffAttachmentResolver(storage, handoffProperties)
        );

        RequirementExecutionResult codingResult = adapter.execute(new RequirementExecutionRequest(
                "task-local-qa-patch",
                task(),
                List.of(),
                "implement",
                AgentRole.CODING_AGENT,
                "{}",
                false,
                "{\"version\":1,\"stages\":[]}"
        ));

        JsonNode candidate = null;
        for (JsonNode artifact : OBJECT_MAPPER.readTree(codingResult.resultJson()).path("stageArtifacts")) {
            if ("patch.diff".equals(artifact.path("name").asText())) {
                candidate = artifact;
                break;
            }
        }
        assertTrue(candidate != null);
        String upstream = """
                {"version":1,"stages":[{"role":"CODING_AGENT","candidatePatch":{
                "sourceRole":"CODING_AGENT","targetRole":"QA_AGENT","artifactName":"patch.diff",
                "artifactUri":"%s","sha256":"%s","bytes":%d}}]}
                """.formatted(
                candidate.path("uri").asText(),
                candidate.path("metadataJson").path("sha256").asText(),
                candidate.path("metadataJson").path("bytes").asLong()
        );

        adapter.execute(new RequirementExecutionRequest(
                "task-local-qa-patch",
                task(),
                List.of(),
                "verify",
                AgentRole.QA_AGENT,
                "{}",
                false,
                upstream
        ));

        RepairJobCommand qaCommand = commands.get(1);
        assertEquals("true", qaCommand.policyJson().get("applyCandidatePatch"));
        assertEquals(1, qaCommand.attachments().size());
        assertEquals("candidate-patch.diff", qaCommand.attachments().getFirst().filename());
        assertEquals(new String(patchBytes, StandardCharsets.UTF_8),
                new String(qaCommand.attachments().getFirst().content(), StandardCharsets.UTF_8));
        assertTrue(qaCommand.contextJson().get("upstreamHandoffManifestJson")
                .contains("/work/input/attachments/candidate-patch.diff"));
        assertFalse(qaCommand.contextJson().get("upstreamHandoffManifestJson").contains("s3://"));
    }

    @Test
    void shouldRejectLocalQaBeforeExecutionWhenCodingStageHasNoVerifiedCandidatePatch() {
        AtomicInteger executorInvocations = new AtomicInteger();
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        RoleHandoffProperties handoffProperties = new RoleHandoffProperties();
        EngineRequirementExecutorAdapter adapter = new EngineRequirementExecutorAdapter(
                ignored -> {
                    executorInvocations.incrementAndGet();
                    return new RepairExecutionResult(
                            RepairExecutionStatus.SUCCESS, "unexpected execution", "", List.of(),
                            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), ""
                    );
                },
                null,
                null,
                null,
                null,
                null,
                new ObjectStorageRoleHandoffPublisher(storage, handoffProperties),
                new RoleHandoffAttachmentResolver(storage, handoffProperties)
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> adapter.execute(
                new RequirementExecutionRequest(
                        "task-local-qa-without-patch",
                        task(),
                        List.of(),
                        "verify the implementation",
                        AgentRole.QA_AGENT,
                        "{}",
                        false,
                        "{\"version\":1,\"stages\":[{\"role\":\"CODING_AGENT\"}]}"
                )
        ));

        assertEquals("local QA requires a verified candidate patch from the successful CODING_AGENT stage",
                exception.getMessage());
        assertEquals(0, executorInvocations.get());
    }

    private RequirementExecutionRequest request() {
        return new RequirementExecutionRequest("task-1001", task(), List.of(), "implement");
    }

    private RequirementExecutionRequest qaRequest() {
        return new RequirementExecutionRequest(
                "task-1001",
                task(),
                List.of(),
                "qa",
                AgentRole.QA_AGENT,
                "{}",
                false,
                "[]"
        );
    }

    private static List<RepairArtifact> qaArtifacts() {
        RepairArtifact current = qaArtifact(
                RepairArtifactType.QA_COMMAND_LOG,
                "qa-evidence/commands/current.log",
                "current"
        );
        RepairArtifact regression = qaArtifact(
                RepairArtifactType.QA_COMMAND_LOG,
                "qa-evidence/commands/regression.log",
                "regression"
        );
        RepairArtifact manifest = qaArtifact(
                RepairArtifactType.QA_EVIDENCE_MANIFEST,
                "qa-evidence/manifest.json",
                manifestJson(List.of(current, regression))
        );
        return List.of(manifest, current, regression);
    }

    private static RepairArtifact qaArtifact(RepairArtifactType type, String name, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RepairArtifact(
                type,
                name,
                "file:///tmp/" + name,
                "QA evidence",
                Map.of(
                        "bytes", String.valueOf(bytes.length),
                        "sha256", sha256(bytes),
                        "contentType", "text/plain",
                        "contentPreview", body
                )
        );
    }

    private List<RepairArtifact> localQaArtifacts() throws Exception {
        RepairArtifact current = localQaArtifact(
                RepairArtifactType.QA_COMMAND_LOG,
                "qa-evidence/commands/current.log",
                "current"
        );
        RepairArtifact regression = localQaArtifact(
                RepairArtifactType.QA_COMMAND_LOG,
                "qa-evidence/commands/regression.log",
                "regression"
        );
        RepairArtifact manifest = localQaArtifact(
                RepairArtifactType.QA_EVIDENCE_MANIFEST,
                "qa-evidence/manifest.json",
                manifestJson(List.of(current, regression))
        );
        return List.of(manifest, current, regression);
    }

    private RepairArtifact localQaArtifact(RepairArtifactType type, String name, String content) throws Exception {
        Path path = tempDirectory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return new RepairArtifact(
                type,
                name,
                path.toUri().toString(),
                "QA evidence",
                Map.of(
                        "bytes", Long.toString(Files.size(path)),
                        "sha256", sha256,
                        "contentType", "text/plain",
                        "contentPreview", content
                )
        );
    }

    private static String manifestJson(List<RepairArtifact> artifacts) {
        String entries = artifacts.stream()
                .map(artifact -> """
                        {"path":"%s","bytes":%s,"sha256":"%s"}
                        """.formatted(
                                artifact.name(),
                                artifact.metadataJson().get("bytes"),
                                artifact.metadataJson().get("sha256")
                        ).strip())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return "{\"version\":1,\"artifacts\":[" + entries + "]}";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String strictQaResultJson() {
        return """
                {
                  "status": "PASSED",
                  "summary": "current and regression checks passed",
                  "failureCategory": "NONE",
                  "retryRecommendation": "NONE",
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
                      "criteria": "测试通过",
                      "scope": "CURRENT",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "critical regression",
                      "scope": "REGRESSION",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
    }

    private static String strictFailedQaResultJson() {
        return """
                {
                  "status": "FAILED",
                  "summary": "Marker text was not found by real grep command",
                  "failureCategory": "PRODUCT_DEFECT",
                  "retryRecommendation": "CODING_AGENT",
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
                      "criteria": "测试通过",
                      "scope": "CURRENT",
                      "command": "grep -q marker docs/example.md",
                      "status": "FAILED",
                      "exitCode": 1,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/current.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                    },
                    {
                      "criteria": "critical regression",
                      "scope": "REGRESSION",
                      "command": "./mvnw test",
                      "status": "PASSED",
                      "exitCode": 0,
                      "durationMillis": 100,
                      "logArtifactId": "qa-evidence/commands/regression.log",
                      "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                    }
                  ],
                  "evidenceManifestArtifactId": "qa-evidence/manifest.json"
                }
                """;
    }

    private static String strictQaResultJsonWithHostAssertionEchoes(String contractsJson) {
        try {
            JsonNode contracts = OBJECT_MAPPER.readTree(contractsJson);
            String currentHash = hostContractHash(contracts, "CURRENT");
            String regressionHash = hostContractHash(contracts, "REGRESSION");
            String base = strictQaResultJson().strip();
            String suffix = """
                      ,
                      "hostAssertionResults": [
                        {
                          "scope": "CURRENT",
                          "contentHash": "%s",
                          "evidenceArtifactIds": ["qa-evidence/commands/current.log"]
                        },
                        {
                          "scope": "REGRESSION",
                          "contentHash": "%s",
                          "evidenceArtifactIds": ["qa-evidence/commands/regression.log"]
                        }
                      ]
                    }
                    """.formatted(currentHash, regressionHash);
            return base.substring(0, base.length() - 1) + suffix;
        } catch (IOException exception) {
            throw new AssertionError("host assertion contracts must be JSON", exception);
        }
    }

    private static String hostContractHash(JsonNode contracts, String scope) {
        for (JsonNode contract : contracts) {
            if (scope.equals(contract.path("scope").asText())) {
                return contract.path("contentHash").asText();
            }
        }
        throw new AssertionError("missing " + scope + " Host assertion contract");
    }

    private static String hostAssertionDefinitionsJson() {
        return """
                {
                  "assertions": [
                    {
                      "scope": "CURRENT",
                      "id": "current-file",
                      "assertionType": "FILE_EXISTS",
                      "target": "current.txt",
                      "operator": "exists"
                    },
                    {
                      "scope": "REGRESSION",
                      "id": "regression-file",
                      "assertionType": "FILE_EXISTS",
                      "target": "regression.txt",
                      "operator": "exists"
                    }
                  ]
                }
                """;
    }

    private static String hostHttpJsonPathDefinitionsJson() {
        return """
                {
                  "assertions": [
                    {
                      "scope": "CURRENT",
                      "id": "current-json",
                      "assertionType": "HTTP_JSONPATH",
                      "action": "GET /health",
                      "target": "$.ok",
                      "operator": "eq",
                      "expected": "true"
                    },
                    {
                      "scope": "REGRESSION",
                      "id": "regression-json",
                      "assertionType": "HTTP_JSONPATH",
                      "action": "GET /health",
                      "target": "$.ok",
                      "operator": "eq",
                      "expected": "true"
                    }
                  ]
                }
                """;
    }

    private RdRequirementTask taskWithHostAssertionBundle(String hostAssertionBundleJson) {
        RdRequirementTask base = task();
        return new RdRequirementTask(
                base.taskId(),
                base.taskType(),
                base.sourceType(),
                base.sourceId(),
                base.sourceUrl(),
                base.priority(),
                base.status(),
                base.title(),
                base.projectId(),
                base.projectKey(),
                base.projectName(),
                base.repositoryUrl(),
                base.repoOwner(),
                base.repoName(),
                base.baseBranch(),
                base.workBranch(),
                base.expectedResult(),
                base.acceptanceCriteriaJson(),
                base.promptSnapshot(),
                base.executionResultJson(),
                base.pullRequestUrl(),
                base.errorMessage(),
                base.createTimeEpochMillis(),
                base.updateTimeEpochMillis(),
                base.paused(),
                base.tokenBudgetOverride(),
                base.version(),
                base.fencingToken(),
                readHostAssertionBundle(hostAssertionBundleJson)
        );
    }

    private static JsonNode readHostAssertionBundle(String source) {
        try {
            return OBJECT_MAPPER.readTree(source);
        } catch (IOException exception) {
            throw new AssertionError("Host assertion test definition must be valid JSON", exception);
        }
    }

    private RdRequirementTask task() {
        CreateRequirementTaskCommand command = new CreateRequirementTaskCommand(
                "需求交付",
                "P1",
                "https://github.com/acme/order.git",
                "",
                "",
                "main",
                "完成需求",
                List.of("测试通过"),
                false
        );
        return RdRequirementTask.created("task-1001", command, 1000L);
    }

    private RdRequirementTask projectTask() {
        return RdRequirementTask.created("task-project-runtime", new CreateRequirementTaskCommand(
                "项目运行时任务",
                "P1",
                "ADMIN",
                "",
                "",
                "7486000000000000005",
                "runtime-test",
                "Runtime Test",
                "https://github.com/acme/order.git",
                "acme",
                "order",
                "main",
                "完成需求",
                List.of("测试通过"),
                List.of(),
                false
        ), 1000L);
    }

    private static final class RecordingCodePlatform implements CodePlatformPort {

        private CreatePullRequestCommand command;

        @Override
        public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
            this.command = command;
            return new PullRequestResult(
                    "https://github.com/acme/order/pull/42",
                    "42",
                    Map.of("provider", "real-test")
            );
        }

        private CreatePullRequestCommand command() {
            return command;
        }
    }

    private static final class RecordingRepairExecutor implements RepairExecutorPort {

        private final List<RepairJobCommand> commands = new java.util.ArrayList<>();

        @Override
        public RepairExecutionResult execute(RepairJobCommand command) {
            commands.add(command);
            return new RepairExecutionResult(
                    RepairExecutionStatus.SUCCESS,
                    "ok",
                    "",
                    List.of(),
                    Map.of("status", "SUCCESS"),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    ""
            );
        }

        private List<RepairJobCommand> commands() {
            return List.copyOf(commands);
        }
    }

    private static final class InMemoryQaProfileStore implements QaValidationProfileStore {
        private final Map<String, QaValidationProfile> values = new HashMap<>();

        @Override
        public QaValidationProfile save(QaValidationProfile profile) {
            values.put(profile.scopeType() + ":" + profile.scopeId(), profile);
            return profile;
        }

        @Override
        public Optional<QaValidationProfile> find(String scopeType, String scopeId) {
            return Optional.ofNullable(values.get(scopeType + ":" + scopeId));
        }
    }
}
