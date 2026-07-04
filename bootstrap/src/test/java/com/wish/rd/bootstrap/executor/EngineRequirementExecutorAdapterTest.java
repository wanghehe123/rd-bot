package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.AgentRole;
import com.wish.rd.engine.requirement.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.RequirementExecutionResult;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairArtifactType;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import com.wish.rd.rag.runtime.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutorAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    void shouldNormalizeLegacySuccessfulQaResultToQaReportProtocol() throws IOException {
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
        assertTrue(result.success());
        assertEquals("PASSED", resultJson.path("status").asText());
        assertTrue(resultJson.path("acceptanceResults").isArray());
        assertEquals(1, resultJson.path("acceptanceResults").size());
        JsonNode acceptanceResult = resultJson.path("acceptanceResults").get(0);
        assertEquals("测试通过", acceptanceResult.path("criteria").asText());
        assertEquals("PASSED", acceptanceResult.path("status").asText());
        assertTrue(acceptanceResult.path("command").asText().contains("test -s"));
        assertTrue(acceptanceResult.path("logArtifactId").asText().startsWith("qa-inline-log-"));
        assertEquals("SUCCESS", resultJson.path("legacyStatus").asText());
    }

    @Test
    void shouldTreatQaFailedReportAsRequirementFailure() throws IOException {
        String agentResultJson = """
                {
                  "status": "FAILED",
                  "summary": "Marker text was not found by real grep command",
                  "acceptanceResults": [
                    {
                      "criteria": "文档必须包含 marker",
                      "command": "grep -q marker docs/example.md",
                      "status": "FAILED",
                      "logArtifactId": "qa-log-1"
                    }
                  ]
                }
                """;
        RepairExecutorPort repairExecutor = ignored -> new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Marker text was not found by real grep command",
                "",
                List.of(),
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

    private RequirementExecutionRequest request() {
        return new RequirementExecutionRequest("task-1001", task(), List.of(), "implement");
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
}
