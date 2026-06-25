package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.bugfix.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.bugfix.acceptance.AcceptanceAssertion;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanStatus;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanStep;
import com.wish.rd.engine.rag.BugFixMessage;
import com.wish.rd.bootstrap.executor.EngineBugFixExecutorAdapter;
import com.wish.rd.bootstrap.executor.EngineBugFixExecutorConfiguration;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.execution.RepairArtifact;
import com.wish.rd.exec.repair.execution.RepairArtifactType;
import com.wish.rd.exec.repair.execution.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineBugFixExecutorAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldCreatePullRequestForSuccessfulExecutionAndReturnPrUrl() throws IOException {
        RepairArtifact patchArtifact = new RepairArtifact(
                RepairArtifactType.PATCH_DIFF,
                "patch.diff",
                "file:///tmp/patch.diff",
                "patch",
                Map.of("bytes", "128")
        );
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor(new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "Added null amount guard and regression test.",
                "",
                List.of(patchArtifact),
                Map.of(
                        "status", "SUCCESS",
                        "summary", "patched",
                        "prBody", "Claude generated PR body."
                ),
                Map.of("exitCode", "0"),
                Map.of(),
                Map.of("testsPassed", "true"),
                Map.of("riskLevel", "LOW"),
                ""
        ));
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineBugFixExecutorAdapter adapter = adapter(repairExecutor, codePlatform);

        BugFixExecutionResult result = adapter.execute(request());

        assertEquals("task-1001", result.taskId());
        assertEquals("OrderService.create 500", result.bugDescription());
        assertEquals("Added null amount guard and regression test.", result.solution());
        assertEquals("https://github.example.local/acme/order/pull/repair-task-1001", result.pullRequestUrl());
        assertTrue(result.resultJson().contains("\"status\":\"SUCCESS\""));

        assertEquals(1, repairExecutor.commands().size());
        RepairJobCommand repairCommand = repairExecutor.commands().getFirst();
        assertEquals("task-1001", repairCommand.repairRecordId());
        assertEquals("task-1001", repairCommand.taskId());
        assertEquals("FS-1001", repairCommand.ticketId());
        assertEquals("OrderService.create 500", repairCommand.ticketTitle());
        assertEquals("Fix prompt", repairCommand.prompt());
        assertEquals("https://github.example.local/acme/order", repairCommand.repositoryUrl());
        assertEquals("acme", repairCommand.repoOwner());
        assertEquals("order", repairCommand.repoName());
        assertEquals("main", repairCommand.baseBranch());
        assertEquals("repair/task-1001", repairCommand.workBranch());
        assertEquals("amount null", repairCommand.contextJson().get("ticketDescription"));

        assertEquals(1, codePlatform.commands().size());
        CreatePullRequestCommand pullRequestCommand = codePlatform.commands().getFirst();
        assertEquals(List.of(patchArtifact), pullRequestCommand.artifactReferences());
        assertEquals("Claude generated PR body.", pullRequestCommand.prBody());
        assertEquals("task-1001", pullRequestCommand.metadata().get("taskId"));
        JsonNode resultJson = OBJECT_MAPPER.readTree(result.resultJson());
        assertEquals("0", resultJson.path("dockerMetadata").path("exitCode").asText());
        assertEquals("true", resultJson.path("testMetadata").path("testsPassed").asText());
        assertEquals("LOW", resultJson.path("riskMetadata").path("riskLevel").asText());
        assertEquals("PATCH_DIFF", resultJson.path("artifacts").get(0).path("type").asText());
        assertEquals("file:///tmp/patch.diff", resultJson.path("artifacts").get(0).path("uri").asText());
    }

    @Test
    void shouldPassAcceptancePlanIntoRepairCommandAndPullRequestMetadata() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor(new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "patched",
                "",
                List.of(new RepairArtifact(
                        RepairArtifactType.PATCH_DIFF,
                        "patch.diff",
                        "file:///tmp/patch.diff",
                        "patch",
                        Map.of()
                )),
                Map.of("status", "SUCCESS", "summary", "patched", "prBody", "body"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        ));
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineBugFixExecutorAdapter adapter = adapter(repairExecutor, codePlatform);

        adapter.execute(requestWithAcceptancePlan());

        RepairJobCommand repairCommand = repairExecutor.commands().getFirst();
        assertEquals("READY", repairCommand.contextJson().get("acceptancePlanStatus"));
        assertTrue(repairCommand.contextJson().get("acceptancePlanJson").contains("POST /api/orders"));
        assertEquals("READY", codePlatform.commands().getFirst().metadata().get("acceptancePlanStatus"));
        assertTrue(codePlatform.commands().getFirst().metadata().get("acceptancePlanJson").contains("POST /api/orders"));
    }

    @Test
    void shouldNotCreatePullRequestForValidationFailureAndReturnValidationErrors() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor(new RepairExecutionResult(
                RepairExecutionStatus.FAILED_VALIDATION,
                "result.json failed validation",
                "",
                List.of(),
                Map.of("status", "FAILED_VALIDATION", "validationErrors", "missing patch.diff"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                "missing patch.diff"
        ));
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineBugFixExecutorAdapter adapter = adapter(repairExecutor, codePlatform);

        BugFixExecutionResult result = adapter.execute(request());

        assertEquals("", result.pullRequestUrl());
        assertTrue(result.resultJson().contains("\"validationErrors\":\"missing patch.diff\""));
        assertTrue(result.resultJson().contains("\"pullRequestUrl\":\"\""));
        assertTrue(codePlatform.commands().isEmpty());
    }

    @Test
    void shouldNotCreatePullRequestForUnsafeExecutionResult() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor(new RepairExecutionResult(
                RepairExecutionStatus.UNSAFE,
                "unsafe changes detected",
                "",
                List.of(),
                Map.of("status", "UNSAFE", "risk", "destructive command"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("riskLevel", "HIGH"),
                "unsafe changes detected"
        ));
        RecordingCodePlatform codePlatform = new RecordingCodePlatform();
        EngineBugFixExecutorAdapter adapter = adapter(repairExecutor, codePlatform);

        BugFixExecutionResult result = adapter.execute(request());

        assertEquals("", result.pullRequestUrl());
        assertTrue(result.resultJson().contains("\"status\":\"UNSAFE\""));
        assertTrue(codePlatform.commands().isEmpty());
    }

    @Test
    void shouldRejectBlankPullRequestUrlForSuccessfulExecution() {
        RecordingRepairExecutor repairExecutor = new RecordingRepairExecutor(new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                "patched",
                "",
                List.of(),
                Map.of("status", "SUCCESS", "prBody", "body"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                ""
        ));
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("");
        EngineBugFixExecutorAdapter adapter = adapter(repairExecutor, codePlatform);

        assertThrows(IllegalStateException.class, () -> adapter.execute(request()));
        assertEquals(1, codePlatform.commands().size());
    }

    @Test
    void shouldRejectNullBugFixExecutionRequest() {
        EngineBugFixExecutorAdapter adapter = adapter(
                new RecordingRepairExecutor(new RepairExecutionResult(
                        RepairExecutionStatus.SUCCESS,
                        "patched",
                        "",
                        List.of(),
                        Map.of("status", "SUCCESS", "prBody", "body"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        ""
                )),
                new RecordingCodePlatform()
        );

        assertThrows(IllegalArgumentException.class, () -> adapter.execute(null));
    }

    @Test
    void shouldKeepBridgeInBootstrapWithoutChangingEnginePortShape() throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        String adapterSource = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/executor/EngineBugFixExecutorAdapter.java"
        ));
        String enginePort = Files.readString(projectRoot.resolve(
                "engine/src/main/java/com/wish/rd/engine/bugfix/BugFixExecutor.java"
        ));

        assertTrue(adapterSource.contains("implements BugFixExecutor"));
        assertTrue(adapterSource.contains("RepairExecutorPort"));
        assertTrue(adapterSource.contains("CodePlatformPort"));
        assertFalse(enginePort.contains("com.wish.rd.exec"));
    }

    @Test
    void shouldRegisterBridgeWhenExecutionAndCodePlatformPortsExistWithoutRepositoryProperties() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(BridgeContextConfiguration.class);

        contextRunner.run(context -> {
            assertEquals(1, context.getBeanNamesForType(BugFixExecutor.class).length);
            assertTrue(context.getBean(BugFixExecutor.class) instanceof EngineBugFixExecutorAdapter);
        });
    }

    private EngineBugFixExecutorAdapter adapter(
            RepairExecutorPort repairExecutor,
            CodePlatformPort codePlatform
    ) {
        return new EngineBugFixExecutorAdapter(
                repairExecutor,
                codePlatform,
                new EngineBugFixExecutorAdapter.RepositoryConfig(
                        "acme",
                        "order",
                        "https://github.example.local/acme/order",
                        "main",
                        "repair/"
                )
        );
    }

    private BugFixExecutionRequest request() {
        return new BugFixExecutionRequest("task-1001", "Fix prompt", new BugFixMessage(
                "FS-1001",
                "OrderService.create 500",
                "amount null",
                List.of("orders"),
                "task-1001",
                false,
                "repair",
                "Bug Fix",
                "repair",
                "Fix safely",
                List.of("knowledge"),
                List.of(),
                "context summary",
                "system",
                "user",
                List.of("prompt"),
                List.of("chunk-1"),
                "answer",
                false
        ));
    }

    private BugFixExecutionRequest requestWithAcceptancePlan() {
        AcceptancePlan plan = new AcceptancePlan(
                "task-1001",
                "FS-1001",
                AcceptancePlanStatus.READY,
                "docker-claude-planner",
                "",
                List.of(new AcceptancePlanStep("execute", "http", "POST /api/orders", "{}")),
                List.of(new AcceptanceAssertion("status", "http.status", "eq", "200")),
                List.of("chunk-1")
        );
        return new BugFixExecutionRequest("task-1001", "Fix prompt", request().ragMessage(), plan);
    }

    private static final class RecordingRepairExecutor implements RepairExecutorPort {

        private final RepairExecutionResult result;
        private final List<RepairJobCommand> commands = new ArrayList<>();

        private RecordingRepairExecutor(RepairExecutionResult result) {
            this.result = result;
        }

        @Override
        public RepairExecutionResult execute(RepairJobCommand command) {
            commands.add(command);
            return result;
        }

        private List<RepairJobCommand> commands() {
            return List.copyOf(commands);
        }
    }

    private static final class RecordingCodePlatform implements CodePlatformPort {

        private final List<CreatePullRequestCommand> commands = new ArrayList<>();
        private final String pullRequestUrl;

        private RecordingCodePlatform() {
            this("https://github.example.local/acme/order/pull/repair-task-1001");
        }

        private RecordingCodePlatform(String pullRequestUrl) {
            this.pullRequestUrl = pullRequestUrl;
        }

        @Override
        public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
            commands.add(command);
            String pullRequestNumber = command.workBranch().replace('/', '-');
            return new PullRequestResult(
                    pullRequestUrl,
                    pullRequestNumber,
                    Map.of("repository", command.repoOwner() + "/" + command.repoName())
            );
        }

        private List<CreatePullRequestCommand> commands() {
            return List.copyOf(commands);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(EngineBugFixExecutorConfiguration.class)
    static class BridgeContextConfiguration {

        @Bean
        RepairExecutorPort repairExecutorPort() {
            return command -> new RepairExecutionResult(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "validation failed",
                    "",
                    List.of(),
                    Map.of("status", "FAILED_VALIDATION"),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    "validation failed"
            );
        }

        @Bean
        CodePlatformPort codePlatformPort() {
            return command -> new PullRequestResult(
                    "https://github.example.local/local/repository/pull/mock",
                    "mock",
                    Map.of("provider", "test")
            );
        }
    }
}
