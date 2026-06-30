package com.wish.rd.bootstrap.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.RequirementExecutionRequest;
import com.wish.rd.engine.requirement.RequirementExecutionResult;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.rag.runtime.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.RdRequirementTask;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementExecutorAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldCreatePullRequestAndPreserveRequirementExecutionEvidence() throws IOException {
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
        assertEquals("https://github.com/acme/order/pull/42", result.pullRequestUrl());
        assertEquals("RD-Bot requirement: 需求交付", codePlatform.command().title());
        assertEquals("requirement/task-1001", codePlatform.command().workBranch());
        assertTrue(codePlatform.command().prBody().contains("add requirement flow"));

        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertEquals("SUCCESS", resultJson.path("status").asText());
        assertEquals("需求已实现", resultJson.path("summary").asText());
        assertEquals("## Summary\n- add requirement flow", resultJson.path("prBody").asText());
        assertEquals("src/App.tsx\nsrc/api.ts", resultJson.path("changedFiles").asText());
        assertEquals("PASSED", resultJson.path("testMetadata").path("testStatus").asText());
        assertEquals("./mvnw test", resultJson.path("testMetadata").path("testCommands").asText());
        assertEquals("LOW", resultJson.path("riskMetadata").path("riskLevel").asText());
        assertEquals("real-test", resultJson.path("codePlatformMetadata").path("provider").asText());
    }

    private RequirementExecutionRequest request() {
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
        RdRequirementTask task = RdRequirementTask.created("task-1001", command, 1000L);
        return new RequirementExecutionRequest("task-1001", task, List.of(), "implement");
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
}
