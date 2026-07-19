package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementExecutorAdapter;
import com.wish.rd.bootstrap.executor.impl.ObjectStorageQaEvidencePublisher;

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
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.qa.QaValidationProfileService;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void shouldRequestRepositoryPublicationOnlyForCodingAgent() {
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
                "qa",
                AgentRole.QA_AGENT,
                "{}",
                false,
                "[]"
        ));

        assertEquals("false", repairExecutor.commands().get(0).policyJson().get("repositoryPublishRequired"));
        assertEquals("true", repairExecutor.commands().get(1).policyJson().get("repositoryPublishRequired"));
        assertEquals("false", repairExecutor.commands().get(2).policyJson().get("repositoryPublishRequired"));
        assertEquals("task-1001-requirement_reviewer", repairExecutor.commands().get(0).taskId());
        assertEquals("task-1001", repairExecutor.commands().get(1).taskId());
        assertEquals("task-1001-qa_agent", repairExecutor.commands().get(2).taskId());
        assertEquals("task-1001", repairExecutor.commands().get(0).contextJson().get("taskId"));
        assertEquals("task-1001", repairExecutor.commands().get(2).contextJson().get("taskId"));
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
                List.of("npm test")
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
