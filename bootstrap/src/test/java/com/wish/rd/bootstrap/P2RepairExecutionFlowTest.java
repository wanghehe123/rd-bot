package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.bootstrap.executor.EngineBugFixExecutorAdapter;
import com.wish.rd.engine.bugfix.BugFixPromptBuilder;
import com.wish.rd.engine.bugfix.RdBotFixCommand;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.RdBotFixResult;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.PullRequestResult;
import com.wish.rd.exec.repair.docker.ContainerRunRequest;
import com.wish.rd.exec.repair.docker.ContainerRunResult;
import com.wish.rd.exec.repair.docker.ContainerRunnerPort;
import com.wish.rd.exec.repair.docker.DockerClaudeCodeExecutor;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.result.StructuredResultValidator;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P2RepairExecutionFlowTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESULT_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["status", "summary", "changedFiles", "testCommands", "testStatus", "riskLevel", "needHumanAction"]
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void successPathShouldRunDockerValidationCreatePrAndCommitTask() throws Exception {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("https://github.example/acme/order/pull/42");
        FlowFixture fixture = flow(successRunner(), codePlatform);

        RdBotFixResult result = fixture.engine().runBugFix(command());
        RdBugFixTask task = fixture.registry().get(result.taskId());
        JsonNode executionJson = OBJECT_MAPPER.readTree(task.executionResultJson());

        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals(RdTaskStatus.COMMITTED, task.status());
        assertEquals("https://github.example/acme/order/pull/42", task.pullRequestUrl());
        assertEquals(1, codePlatform.commands().size());
        assertEquals("SUCCESS", executionJson.path("status").asText());
        assertEquals("https://github.example/acme/order/pull/42", executionJson.path("pullRequestUrl").asText());
        assertEquals(
                Set.of("result.json", "patch.diff", "test.log", "claude-events.jsonl", "docker-meta.json"),
                artifactNames(executionJson)
        );
    }

    @Test
    void validationFailureShouldNotCreatePullRequestAndShouldReturnBlankPrUrl() throws Exception {
        RecordingCodePlatform codePlatform = new RecordingCodePlatform("https://github.example/acme/order/pull/42");
        FlowFixture fixture = flow(invalidResultRunner(), codePlatform);

        RdBotFixResult result = fixture.engine().runBugFix(command());
        RdBugFixTask task = fixture.registry().get(result.taskId());
        JsonNode executionJson = OBJECT_MAPPER.readTree(task.executionResultJson());

        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals("", task.pullRequestUrl());
        assertTrue(codePlatform.commands().isEmpty());
        assertEquals("FAILED_VALIDATION", executionJson.path("status").asText());
        assertEquals("", executionJson.path("pullRequestUrl").asText());
        assertTrue(executionJson.path("validationErrors").asText().contains("parse error"));
    }

    private FlowFixture flow(ContainerRunnerPort runner, RecordingCodePlatform codePlatform) {
        RagStreamTaskRegistry registry = registry();
        DockerClaudeCodeExecutor dockerExecutor = new DockerClaudeCodeExecutor(
                new RepairWorkspaceFactory(temporaryDirectory, RESULT_SCHEMA_JSON),
                runner,
                new StructuredResultValidator(),
                new DockerClaudeCodeExecutor.Configuration(
                        "rd-bot/claude-code:test",
                        List.of("claude", "-p", "--dangerously-skip-permissions", "--output-format", "stream-json"),
                        "none",
                        true,
                        false
                )
        );
        EngineBugFixExecutorAdapter adapter = new EngineBugFixExecutorAdapter(
                dockerExecutor,
                codePlatform,
                new EngineBugFixExecutorAdapter.RepositoryConfig(
                        "acme",
                        "order",
                        "https://github.com/acme/order",
                        "main",
                        "repair/"
                )
        );
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                adapter
        );
        return new FlowFixture(engine, registry);
    }

    private RagBugFixEngine ragEngine(RagStreamTaskRegistry registry) {
        return new RagBugFixEngine(
                QueryTermMappingRegistry.withDefaults(),
                IntentTreeRegistry.withDefaults(),
                null,
                registry,
                ChatQueueLimiter.passThrough()
        );
    }

    private RagStreamTaskRegistry registry() {
        AtomicLong now = new AtomicLong(1_780_000_000_000L);
        return new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement)
        );
    }

    private RdBotFixCommand command() {
        return new RdBotFixCommand(
                new TicketSnapshot(
                        "FS-3001",
                        "支付系统下单接口 500",
                        "金额为空时 OrderService.create 写入订单失败",
                        List.of("payment", "orders.amount"),
                        Instant.parse("2026-06-21T00:00:00Z")
                ),
                List.of("ERROR orders.amount is null at OrderService.create"),
                false,
                "P2"
        );
    }

    private ContainerRunnerPort successRunner() {
        return new ProtocolRunner(successResultJson());
    }

    private ContainerRunnerPort invalidResultRunner() {
        return new ProtocolRunner("{not-json");
    }

    private Set<String> artifactNames(JsonNode executionJson) {
        return java.util.stream.StreamSupport.stream(executionJson.path("artifacts").spliterator(), false)
                .map(node -> node.path("name").asText())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String successResultJson() {
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

    private static final class ProtocolRunner implements ContainerRunnerPort {

        private final String resultJson;

        private ProtocolRunner(String resultJson) {
            this.resultJson = resultJson;
        }

        @Override
        public ContainerRunResult run(ContainerRunRequest request) throws IOException {
            Files.createDirectories(request.outputDirectory());
            Path result = request.outputDirectory().resolve("result.json");
            Path patch = request.outputDirectory().resolve("patch.diff");
            Path testLog = request.outputDirectory().resolve("test.log");
            Path events = request.outputDirectory().resolve("claude-events.jsonl");
            Path dockerMeta = request.outputDirectory().resolve("docker-meta.json");

            Files.writeString(result, resultJson, StandardCharsets.UTF_8);
            Files.writeString(patch, "diff --git a/src/main/java/example/OrderService.java b/src/main/java/example/OrderService.java\n",
                    StandardCharsets.UTF_8);
            Files.writeString(testLog, "BUILD SUCCESS\n", StandardCharsets.UTF_8);
            Files.writeString(events, "{\"type\":\"done\"}\n", StandardCharsets.UTF_8);
            Files.writeString(dockerMeta, "{\"runner\":\"protocol\"}\n", StandardCharsets.UTF_8);

            return new ContainerRunResult(
                    0,
                    100,
                    "fake runner completed",
                    "",
                    result,
                    patch,
                    testLog,
                    events,
                    dockerMeta,
                    Map.of("runner", "protocol")
            );
        }
    }

    private static final class RecordingCodePlatform implements CodePlatformPort {

        private final String pullRequestUrl;
        private final List<CreatePullRequestCommand> commands = new ArrayList<>();

        private RecordingCodePlatform(String pullRequestUrl) {
            this.pullRequestUrl = pullRequestUrl;
        }

        @Override
        public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
            commands.add(command);
            return new PullRequestResult(
                    pullRequestUrl,
                    "42",
                    Map.of("provider", "mock")
            );
        }

        private List<CreatePullRequestCommand> commands() {
            return List.copyOf(commands);
        }
    }

    private record FlowFixture(
            RdBotFixEngine engine,
            RagStreamTaskRegistry registry
    ) {
    }
}
