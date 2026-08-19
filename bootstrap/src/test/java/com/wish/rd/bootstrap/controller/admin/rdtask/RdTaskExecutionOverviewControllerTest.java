package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.bootstrap.financial.FinancialProperties;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.bugfix.observability.BugFixStageRecorder;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.runtime.impl.InMemoryAgentExecutionEventStore;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.impl.InMemoryAgentStageStateProjectionStore;
import com.wish.rd.rag.project.agent.model.AgentContextInjectionProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.AgentStageStateIdentity;
import com.wish.rd.rag.project.agent.model.AgentStateProjectionUpdate;
import com.wish.rd.rag.project.agent.model.AgentStateV2Codec;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskExecutionOverviewControllerTest {

    private MockMvc mockMvc;
    private RagStreamTaskRegistry registry;
    private AgentStageRunStore stageRunStore;
    private AgentStageArtifactStore artifactStore;
    private RoleContextPackageStore contextPackageStore;
    private DockerExecutionRegistry executionRegistry;
    private InMemoryAgentExecutionEventStore eventStore;
    private InMemoryAgentExecutionProfileSnapshotStore profileSnapshotStore;
    private InMemoryAgentStageStateProjectionStore stateProjectionStore;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(),
                new InMemoryRdTaskStatusEventStore(),
                generator());
        stageRunStore = new InMemoryAgentStageRunStore();
        artifactStore = new InMemoryAgentStageArtifactStore();
        contextPackageStore = new InMemoryRoleContextPackageStore();
        executionRegistry = DockerExecutionRegistry.noop();
        eventStore = new InMemoryAgentExecutionEventStore();
        profileSnapshotStore = new InMemoryAgentExecutionProfileSnapshotStore();
        stateProjectionStore = new InMemoryAgentStageStateProjectionStore();
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.setBudgetAlertCny(new java.math.BigDecimal("54.00"));
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskExecutionOverviewController(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore,
                executionRegistry,
                eventStore,
                properties,
                new FinancialProperties().toBudgetCurrencyConverter(),
                profileSnapshotStore,
                stateProjectionStore,
                () -> 1_783_000_100_000L
        )).build();
    }

    @Test
    void shouldExposeIndependentLatestStateAndInjectionProvenance() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "独立状态与注入序列", "P1", "https://github.com/example/repo.git", "example", "repo", "main",
                "overview 展示状态与注入", List.of("injection-only refresh 可见"), false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-overview-state-v2", task.taskId(), AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING, 1, task.taskId() + ":CODING_AGENT:1", "", "", "",
                "pi", "[]", "{}", "", "", 1_783_000_000_000L, 1_783_000_090_000L,
                1_783_000_000_000L, 0L
        ));
        savePiV2Profile(stage);
        AgentStageStateIdentity identity = new AgentStageStateIdentity(
                task.taskId(), stage.stageRunId(), stage.role().name(), stage.attemptNo()
        );
        String state = overviewStateJson(task.taskId(), stage.stageRunId(), 5);
        String stateHash = hashState(state);
        String promptHash = sha256("prompt");
        stateProjectionStore.projectState(new AgentStateProjectionUpdate(
                identity, 5, stateHash, state, 1_783_000_095_000L
        ));
        String blockOne = "<state>one</state>";
        stateProjectionStore.projectInjection(new AgentContextInjectionProjectionUpdate(
                identity, 1, 5, stateHash, promptHash, sha256(blockOne), blockOne,
                sha256(stage.stageRunId() + ":1:" + sha256(blockOne)), 1_783_000_096_000L
        ));
        String blockTwo = "<state>two</state>";
        stateProjectionStore.projectInjection(new AgentContextInjectionProjectionUpdate(
                identity, 2, 5, stateHash, promptHash, sha256(blockTwo), blockTwo,
                sha256(stage.stageRunId() + ":2:" + sha256(blockTwo)), 1_783_000_097_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageRuns[0].runtimeType", is("PI")))
                .andExpect(jsonPath("$.stageRuns[0].agentStateSource", is("LIVE_PROJECTION")))
                .andExpect(jsonPath("$.stageRuns[0].agentStateSequence", is(5)))
                .andExpect(jsonPath("$.stageRuns[0].agentLastInjectionSequence", is(2)))
                .andExpect(jsonPath("$.stageRuns[0].agentLastInjectedStateSequence", is(5)))
                .andExpect(jsonPath("$.stageRuns[0].agentLastInjectedBlockHash", is(sha256(blockTwo))))
                .andExpect(jsonPath("$.stageRuns[0].agentLastInjectedPromptHash", is(promptHash)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateStale", is(false)))
                .andExpect(jsonPath("$.stageRuns[0].agentLatestStateNotInjected", is(false)));
    }

    @Test
    void shouldExposeRuntimeNeutralPiEventsWithReplayCursor() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Pi 事件接口测试",
                "P1",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "展示 Pi 生命周期事件",
                List.of("事件可以从 Java 侧回放"),
                false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-pi-events-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "", "", "", "pi", "[]", "{}", "", "",
                1L, 1L, 1L, 0L
        ));
        eventStore.onEvent("rd-bot-pi-test", event(1, task.taskId(), stage.stageRunId(), "RUNTIME_READY"));
        eventStore.onEvent("rd-bot-pi-test", event(2, task.taskId(), stage.stageRunId(), "AGENT_SETTLED"));

        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-events",
                        task.taskId(), stage.stageRunId())
                        .param("after", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("LIVE")))
                .andExpect(jsonPath("$.finalized", is(true)))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].sequence", is(2)))
                .andExpect(jsonPath("$.events[0].eventType", is("AGENT_SETTLED")));
    }

    @Test
    void shouldReturnStageResultPreviewWhenResultArtifactExists() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "结果可视化测试",
                "P2",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "展示需求评审结果",
                List.of("页面能查看阶段结果"),
                false
        ));
        AgentStageRun stageRun = stageRunStore.save(new AgentStageRun(
                "stage-review-1001",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                AgentStageStatus.SUCCEEDED,
                8,
                task.taskId() + ":REQUIREMENT_REVIEWER:8",
                "context-review-1001",
                "prompt-review-1001",
                "result-review-1001",
                "long-cat",
                "[{\"provider\":\"long-cat\",\"status\":\"SUCCESS\",\"durationMillis\":198000}]",
                "{}",
                "",
                "",
                1_783_000_000_000L,
                1_783_000_198_000L,
                1_783_000_000_000L,
                1_783_000_198_000L
        ));
        artifactStore.save(new AgentStageArtifact(
                "result-review-1001",
                stageRun.stageRunId(),
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                "RESULT_JSON",
                "rd-artifact://stage-review-1001/result",
                "REQUIREMENT_REVIEWER result json",
                "{\"status\":\"APPROVED\",\"summary\":\"需求清晰，可进入方案设计\"}",
                "sha256:test",
                "{}",
                1_783_000_198_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageRuns[0].resultAvailable", is(true)))
                .andExpect(jsonPath("$.stageRuns[0].resultSummary", is("REQUIREMENT_REVIEWER result json")))
                .andExpect(jsonPath("$.stageRuns[0].resultPreview").value(org.hamcrest.Matchers.containsString("需求清晰")));
    }

    @Test
    void shouldExposeAgentStateSummaryWithoutFullLedger() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Agent state 概览测试",
                "P2",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "展示 agent state 摘要",
                List.of("overview 返回脱敏 state 计数"),
                false
        ));
        AgentStageRun stageRun = stageRunStore.save(new AgentStageRun(
                "stage-state-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "",
                "",
                "",
                "pi",
                "[]",
                "{}",
                "",
                "",
                1_783_000_000_000L,
                1_783_000_000_000L,
                1_783_000_000_000L,
                0L
        ));
        String agentStatePreview = """
                {
                  "protocol": "rd-agent-state/v1",
                  "sequence": 7,
                  "generatedAt": "2026-08-01T00:00:00Z",
                  "taskId": "%s",
                  "stageRunId": "stage-state-1001",
                  "role": "CODING_AGENT",
                  "attemptNo": 1,
                  "todos": [
                    {"todoId":"t1","title":"task one","status":"PENDING"},
                    {"todoId":"t2","title":"task two","status":"IN_PROGRESS"},
                    {"todoId":"t3","title":"task three","status":"BLOCKED"},
                    {"todoId":"t4","title":"task four","status":"DONE"}
                  ]
                }""".formatted(task.taskId());
        artifactStore.save(new AgentStageArtifact(
                "agent-state-1001",
                stageRun.stageRunId(),
                task.taskId(),
                AgentRole.CODING_AGENT,
                "AGENT_STATE_SNAPSHOT",
                "rd-artifact://stage-state-1001/agent-state",
                "agent state snapshot",
                agentStatePreview,
                "sha256:agent-state",
                "{\"contentLength\":" + agentStatePreview.length() + "}",
                1_783_000_100_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageRuns[0].agentStateAvailable", is(true)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateSequence", is(7)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateSchemaVersion", is("rd-agent-state/v1")))
                .andExpect(jsonPath("$.stageRuns[0].agentStateTodoPending", is(1)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateTodoInProgress", is(1)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateTodoBlocked", is(1)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateTodoDone", is(1)))
                .andExpect(jsonPath("$.stageRuns[0].agentStatePreviewTruncated", is(false)))
                .andExpect(jsonPath("$.stageRuns[0].agentStateContentHash", is("sha256:agent-state")))
                .andExpect(jsonPath("$.stageRuns[0].resultPreview", not(containsString("verifiedFactSummaries"))));
    }

    @Test
    void shouldReturnExecutionOverviewWithStagesBudgetAndRunningExecutions() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "执行观测测试",
                "P1",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "展示执行进度",
                List.of("API 返回执行概览"),
                false
        ));
        registry.markRequirementMaterialCollecting(task.taskId(), "collecting");
        registry.markRequirementMaterialReady(task.taskId(), "ready");
        registry.markRequirementContextBuilding(task.taskId(), "context");
        registry.markRequirementContextReady(task.taskId(), "{}");
        registry.markRequirementPlanGenerating(task.taskId(), "plan");
        registry.markRequirementPlanGenerated(task.taskId(), "{}");
        registry.markRequirementWaitingPolicy(task.taskId(), "{}");
        registry.markRequirementExecuting(task.taskId(), "prompt");
        stageRunStore.save(new AgentStageRun(
                "stage-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "context-1001",
                "prompt-1001",
                "",
                "long-cat",
                "[{\"provider\":\"long-cat\",\"status\":\"RUNNING\",\"durationMillis\":40,\"estimatedSpendUsd\":\"0.12\"}]",
                "{}",
                "",
                "",
                1_783_000_000_000L,
                1_783_000_000_050L,
                1_783_000_000_010L,
                0L
        ));
        contextPackageStore.save(new RoleContextPackage(
                "context-1001",
                task.taskId(),
                "CODING_AGENT",
                1,
                List.of(),
                List.of("API 返回执行概览"),
                List.of("预算不足时要可见"),
                1000,
                400,
                List.of("material-omitted"),
                1_783_000_000_000L
        ));
        Path outputDirectory = temporaryDirectory.resolve("overview-output");
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("claude-events.jsonl"), """
                {"type":"assistant","message":{"id":"message-1","usage":{"input_tokens":7,"output_tokens":8}}}
                {"type":"result","total_cost":0.42}
                """);
        executionRegistry.register(
                new RepairJobCommand(
                        "repair-1001",
                        task.taskId(),
                        "ticket-1",
                        "执行观测测试",
                        "prompt",
                        "https://github.com/example/repo.git",
                        "example",
                        "repo",
                        "main",
                        "rd-bot/test",
                        Map.of(),
                        Map.of()
                ),
                "long-cat",
                new ContainerRunRequest(
                        "rd-bot-repair-test",
                        "rd-bot/claude-code:local",
                        List.of("claude"),
                        Map.of(),
                        Map.of("/tmp/repo", "/workspace"),
                        "/workspace",
                        "bridge",
                        true,
                        false,
                        outputDirectory
                )
        );

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId", is(task.taskId())))
                .andExpect(jsonPath("$.status", is("EXECUTING")))
                .andExpect(jsonPath("$.elapsedMillis", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.progressTotal", is(24)))
                .andExpect(jsonPath("$.currentRole", is("CODING_AGENT")))
                .andExpect(jsonPath("$.currentStageStatus", is("RUNNING")))
                .andExpect(jsonPath("$.budget.contextUsedChars", is(400)))
                .andExpect(jsonPath("$.budget.contextMaxChars", is(1000)))
                .andExpect(jsonPath("$.budget.budgetAlertCny", is(54.00)))
                .andExpect(jsonPath("$.budget.estimatedSpendCny", is(0.8640)))
                .andExpect(jsonPath("$.stageRuns", hasSize(1)))
                .andExpect(jsonPath("$.stageRuns[0].role", is("CODING_AGENT")))
                .andExpect(jsonPath("$.stageRuns[0].running", is(true)))
                .andExpect(jsonPath("$.stageRuns[0].elapsedMillis").isNumber())
                .andExpect(jsonPath("$.stageRuns[0].providerAttempts[0].provider", is("long-cat")))
                .andExpect(jsonPath("$.stageRuns[0].providerAttempts[0].estimatedSpendCny", is(0.8640)))
                .andExpect(jsonPath("$.stageRuns[0].providerAttempts[0].estimatedSpendUsd").doesNotExist())
                .andExpect(jsonPath("$.stageRuns[0].providerAttemptsJson", not(containsString("estimatedSpendUsd"))))
                .andExpect(jsonPath("$.runningExecutions", hasSize(1)))
                .andExpect(jsonPath("$.runningExecutions[0].containerName", is("rd-bot-repair-test")))
                .andExpect(jsonPath("$.runningExecutions[0].tokenUsage.estimatedSpendCny", is(3.0240)))
                .andExpect(jsonPath("$.runningExecutions[0].tokenUsage.estimatedCostUsd").doesNotExist());
    }

    @Test
    void shouldExposeOnlyRedactedVisibleEventsForTheCurrentContainerStage() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "执行轨迹测试",
                "P1",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "显示正在执行的步骤",
                List.of("轨迹不泄露隐私推理"),
                false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-trace-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "", "", "", "claude-code", "[]", "{}", "", "",
                1L, 1L, 1L, 0L
        ));
        Path outputDirectory = temporaryDirectory.resolve("trace-output");
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("claude-events.jsonl"), """
                {"type":"system","subtype":"init","session_id":"session-1"}
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"PRIVATE_CHAIN api_key=super-secret-value"},{"type":"text","text":"Inspecting SQLCompiler.get_order_by()."},{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"git grep SECRET=super-secret-value"}}]}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"token=super-secret-value","is_error":false}]}}
                {"type":"result","subtype":"success","num_turns":3,"total_cost_usd":0.12}
                """);
        executionRegistry.register(
                new RepairJobCommand(
                        "repair-trace-1001",
                        task.taskId(),
                        "ticket-trace-1001",
                        "执行轨迹测试",
                        "prompt",
                        "https://github.com/example/repo.git",
                        "example",
                        "repo",
                        "main",
                        "rd-bot/test",
                        Map.of("workflowTaskId", task.taskId(), "stageRunId", stage.stageRunId()),
                        Map.of()
                ),
                "claude-code",
                new ContainerRunRequest(
                        "rd-bot-trace-test",
                        "rd-bot/claude-code:local",
                        List.of("claude"),
                        Map.of(),
                        Map.of("/tmp/repo", "/workspace"),
                        "/workspace",
                        "bridge",
                        true,
                        false,
                        outputDirectory
                )
        );

        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-trace",
                        task.taskId(), stage.stageRunId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("LIVE")))
                .andExpect(jsonPath("$.available", is(true)))
                .andExpect(jsonPath("$.finalized", is(true)))
                .andExpect(jsonPath("$.entries[*].kind", org.hamcrest.Matchers.hasItem("ASSISTANT_TEXT")))
                .andExpect(jsonPath("$.entries[*].detail", org.hamcrest.Matchers.hasItem("Inspecting SQLCompiler.get_order_by().")))
                .andExpect(jsonPath("$.entries[*].detail", org.hamcrest.Matchers.hasItem("Bash: git")))
                .andExpect(content().string(not(containsString("PRIVATE_CHAIN"))))
                .andExpect(content().string(not(containsString("super-secret-value"))))
                .andExpect(content().string(not(containsString("git grep SECRET"))));
    }

    @Test
    void shouldKeepPollingTheLiveTraceBeforeClaudeWritesItsFirstVisibleEvent() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "实时轨迹启动测试",
                "P1",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "容器刚启动时也保持实时轨迹通道",
                List.of("不把运行中的空轨迹误判为归档轨迹"),
                false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-trace-starting-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.RUNNING,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "", "", "", "claude-code", "[]", "{}", "", "",
                1L, 1L, 1L, 0L
        ));
        Path outputDirectory = temporaryDirectory.resolve("trace-starting-output");
        Files.createDirectories(outputDirectory);
        executionRegistry.register(
                new RepairJobCommand(
                        "repair-trace-starting-1001",
                        task.taskId(),
                        "ticket-trace-starting-1001",
                        "实时轨迹启动测试",
                        "prompt",
                        "https://github.com/example/repo.git",
                        "example",
                        "repo",
                        "main",
                        "rd-bot/test",
                        Map.of("workflowTaskId", task.taskId(), "stageRunId", stage.stageRunId()),
                        Map.of()
                ),
                "claude-code",
                new ContainerRunRequest(
                        "rd-bot-trace-starting-test",
                        "rd-bot/claude-code:local",
                        List.of("claude"),
                        Map.of(),
                        Map.of("/tmp/repo", "/workspace"),
                        "/workspace",
                        "bridge",
                        true,
                        false,
                        outputDirectory
                )
        );

        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-trace",
                        task.taskId(), stage.stageRunId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("LIVE")))
                .andExpect(jsonPath("$.available", is(false)))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void shouldReturnArchivedSafeTraceWhenContainerIsNoLongerRunning() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "已归档轨迹测试",
                "P1",
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "展示已归档的执行步骤",
                List.of("不返回原始事件流"),
                false
        ));
        AgentStageRun stage = stageRunStore.save(new AgentStageRun(
                "stage-trace-archive-1001",
                task.taskId(),
                AgentRole.CODING_AGENT,
                AgentStageStatus.SUCCEEDED,
                1,
                task.taskId() + ":CODING_AGENT:1",
                "", "", "", "claude-code", "[]", "{}", "", "",
                1L, 2L, 1L, 2L
        ));
        artifactStore.save(new AgentStageArtifact(
                "trace-archive-1001",
                stage.stageRunId(),
                task.taskId(),
                AgentRole.CODING_AGENT,
                "CLAUDE_EVENTS",
                "file:///private/never-return-raw-events.jsonl",
                "Claude Code event stream.",
                """
                        {"version":1,"source":"ARCHIVED","available":true,"finalized":true,"truncated":false,"hasMore":false,"nextSequence":9001,"entries":[{"sequence":9001,"kind":"TOOL_COMPLETED","label":"Tool completed","detail":"Bash: git completed","error":false}]}
                        """,
                "sha256:test",
                "{}",
                3L
        ));

        mockMvc.perform(get(
                        "/admin/rd-tasks/{taskId}/stage-runs/{stageRunId}/execution-trace",
                        task.taskId(), stage.stageRunId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("ARCHIVED")))
                .andExpect(jsonPath("$.finalized", is(true)))
                .andExpect(jsonPath("$.entries[0].detail", is("Bash: git completed")))
                .andExpect(content().string(not(containsString("never-return-raw-events.jsonl"))));
    }

    @Test
    void shouldExposeTokenBudgetEstimateAndFinalActualUsage() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Token 预算测试", "P1", "https://github.com/example/repo.git", "example", "repo", "main",
                "展示 token 预算", List.of("API 返回预算"), false
        ));
        stageRunStore.save(new AgentStageRun(
                "stage-review-budget", task.taskId(), AgentRole.REQUIREMENT_REVIEWER, AgentStageStatus.SUCCEEDED, 1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1", "", "", "", "long-cat", "[]", """
                {"tokenBudget":{"effectiveTokenBudget":1000,"initialTokens":700,"retryReserveTokens":200,
                "estimatedTotalTokens":900,"confidence":"MEDIUM","basis":"模型参考脱敏历史样本",
                "overBudget":false,"excessTokens":0,"historicalSamples":[{"scope":"SAME_PROJECT","actualTotalTokens":850}]}}
                """, "", "", 1L, 2L, 1L, 2L
        ));
        stageRunStore.save(new AgentStageRun(
                "stage-coding-budget", task.taskId(), AgentRole.CODING_AGENT, AgentStageStatus.SUCCEEDED, 1,
                task.taskId() + ":CODING_AGENT:1", "", "", "", "long-cat",
                "[{\"provider\":\"long-cat\",\"status\":\"SUCCESS\",\"totalTokens\":880,\"tokenUsageFinalized\":true}]",
                "{}", "", "", 1L, 2L, 1L, 2L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenBudget.effectiveTokenBudget", is(1000)))
                .andExpect(jsonPath("$.tokenBudget.estimatedTotalTokens", is(900)))
                .andExpect(jsonPath("$.tokenBudget.finalActualTokens", is(880)))
                .andExpect(jsonPath("$.tokenBudget.actualAvailable", is(true)))
                .andExpect(jsonPath("$.tokenBudget.historicalSamples[0].scope", is("SAME_PROJECT")));
    }

    @Test
    void shouldIncludeFailedAttemptUsageFromAgentEventsArtifact() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "失败 attempt token 入账", "P1", "https://github.com/example/repo.git", "example", "repo", "main",
                "失败 attempt 也要计入 token", List.of("API 返回预算"), false
        ));
        AgentStageRun failedStage = stageRunStore.save(new AgentStageRun(
                "stage-coding-failed", task.taskId(), AgentRole.CODING_AGENT, AgentStageStatus.FAILED_RETRYABLE, 1,
                task.taskId() + ":CODING_AGENT:1", "", "", "", "pi",
                "[{\"provider\":\"pi\",\"status\":\"FAILED\",\"errorCategory\":\"ORCHESTRATION_INTERRUPTED\"}]",
                "{}", "ORCHESTRATION_INTERRUPTED", "previous role attempt was interrupted", 1L, 2L, 1L, 2L
        ));
        String agentEvents = """
                {"protocol":"rd-agent-event/v1","eventType":"TURN_STARTED","sourceSequence":1}
                {"protocol":"rd-agent-event/v1","eventType":"ASSISTANT_TEXT_COMPLETED","sourceSequence":2,"payload":{"usage":{"input":120,"output":80,"cacheRead":0,"cacheWrite":0}}}
                {"protocol":"rd-agent-event/v1","eventType":"TURN_COMPLETED","sourceSequence":3,"payload":{"usage":{"input":120,"output":80,"cacheRead":0,"cacheWrite":0}}}
                {"protocol":"rd-agent-event/v1","eventType":"RUNTIME_STOPPED","sourceSequence":4}
                """;
        artifactStore.save(new AgentStageArtifact(
                "agent-events-failed",
                failedStage.stageRunId(),
                task.taskId(),
                AgentRole.CODING_AGENT,
                "AGENT_EVENTS",
                "rd-artifact://stage-coding-failed/agent-events",
                "Pi agent events",
                agentEvents,
                "sha256:agent-events-failed",
                "{}",
                1_783_000_100_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenBudget.actualAvailable", is(true)))
                .andExpect(jsonPath("$.tokenBudget.finalActualTokens", is(200)))
                .andExpect(jsonPath("$.tokenBudget.actualAccumulatedTokens", is(200)));
    }

    @Test
    void shouldMarkActualTokenUsageUnavailableWithoutMeasuredProviderAttempts() throws Exception {
        RdRequirementTask task = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "Token 不可用测试", "P1", "https://github.com/example/repo.git", "example", "repo", "main",
                "展示 token 不可用", List.of("API 返回预算"), false
        ));
        stageRunStore.save(new AgentStageRun(
                "stage-coding-unmeasured", task.taskId(), AgentRole.CODING_AGENT, AgentStageStatus.SUCCEEDED, 1,
                task.taskId() + ":CODING_AGENT:1", "", "", "", "long-cat",
                "[{\"provider\":\"long-cat\",\"status\":\"SUCCESS\"}]",
                "{}", "", "", 1L, 2L, 1L, 2L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenBudget.actualAvailable", is(false)))
                .andExpect(jsonPath("$.tokenBudget.finalActualTokens", is(0)));
    }

    @Test
    void shouldUseBugFixStageOrderAndProgressForBugFixTask() throws Exception {
        RdBugFixTask task = registry.createTaskManually(
                "ticket-bug-overview",
                "注册接口返回 HTML",
                "修复注册接口",
                "P1",
                "prompt"
        );
        BugFixStageRecorder recorder = new BugFixStageRecorder(stageRunStore, artifactStore, generator());
        for (AgentRole role : AgentRole.bugFixOrder()) {
            AgentStageRun running = recorder.start(task.taskId(), role, role.name() + " input");
            recorder.succeed(running, role.name() + " result", "", "[]");
        }

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType", is("BUG_FIX")))
                .andExpect(jsonPath("$.progressCompleted", is(24)))
                .andExpect(jsonPath("$.progressTotal", is(24)))
                .andExpect(jsonPath("$.stageRuns", hasSize(4)))
                .andExpect(jsonPath("$.stageRuns[0].role", is("BUG_EVIDENCE_COLLECTOR")))
                .andExpect(jsonPath("$.stageRuns[1].role", is("BUG_RAG_RETRIEVER")))
                .andExpect(jsonPath("$.stageRuns[2].role", is("BUG_ACCEPTANCE_PLANNER")))
                .andExpect(jsonPath("$.stageRuns[3].role", is("BUG_CODING_AGENT")));
    }

    @Test
    void shouldExcludeRequirementRolesFromBugFixOverview() throws Exception {
        RdBugFixTask task = registry.createTaskManually(
                "ticket-bug-isolation", "Bug 角色隔离", "修复角色隔离", "P1", "prompt");
        BugFixStageRecorder recorder = new BugFixStageRecorder(stageRunStore, artifactStore, generator());
        AgentStageRun bugStage = recorder.start(task.taskId(), AgentRole.BUG_EVIDENCE_COLLECTOR, "evidence");
        recorder.succeed(bugStage, "collected", "", "[]");
        stageRunStore.save(AgentStageRun.pending(
                "stage-requirement-leak",
                task.taskId(),
                AgentRole.REQUIREMENT_REVIEWER,
                1,
                task.taskId() + ":REQUIREMENT_REVIEWER:1",
                1_783_000_000_000L
        ));

        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", task.taskId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageRuns", hasSize(1)))
                .andExpect(jsonPath("$.stageRuns[0].role", is("BUG_EVIDENCE_COLLECTOR")));
    }

    @Test
    void shouldReturn404WhenTaskMissing() throws Exception {
        mockMvc.perform(get("/admin/rd-tasks/{taskId}/execution-overview", "9999999999999999"))
                .andExpect(status().isNotFound());
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_783_000_000_000L);
        return new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
    }

    private static com.fasterxml.jackson.databind.JsonNode event(
            long sourceSequence,
            String taskId,
            String stageRunId,
            String eventType
    ) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"protocol":"rd-agent-event/v1","sourceSequence":%d,"taskId":"%s","stageRunId":"%s","role":"CODING_AGENT","eventType":"%s"}
                """.formatted(sourceSequence, taskId, stageRunId, eventType));
    }

    private void savePiV2Profile(AgentStageRun stage) {
        String profileJson = "{\"capabilities\":[\"PI_AGENT_STATE_V2\"],\"runtimeType\":\"PI\"}";
        profileSnapshotStore.saveIfAbsent(new AgentExecutionProfileSnapshot(
                "profile-" + stage.stageRunId(), stage.stageRunId(), stage.taskId(), stage.role().name(),
                stage.attemptNo(), AgentRuntimeType.PI, profileJson,
                AgentExecutionProfileSnapshot.sha256(profileJson), 1_783_000_000_000L
        ));
    }

    private static String overviewStateJson(String taskId, String stageRunId, long sequence) {
        try {
            return AgentStateV2Codec.canonicalize(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {
                      "protocol":"rd-agent-state/v2",
                      "sequence":%d,
                      "taskId":"%s",
                      "stageRunId":"%s",
                      "role":"CODING_AGENT",
                      "attemptNo":1,
                      "runtimeType":"PI",
                      "profileSnapshotId":"profile-%s",
                      "currentGoal":"验证 overview",
                      "taskStartedAtEpochMillis":1783000000000,
                      "stageStartedAtEpochMillis":1783000000000,
                      "phase":"EXECUTING",
                      "budget":{"availability":"UNKNOWN","model":"","estimatorVersion":""},
                      "todos":[],
                      "generatedAtEpochMillis":1783000095000
                    }
                    """.formatted(sequence, taskId, stageRunId, stageRunId)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hashState(String stateJson) {
        try {
            return AgentStateV2Codec.hash(new com.fasterxml.jackson.databind.ObjectMapper().readTree(stateJson));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        return "sha256:" + AgentExecutionProfileSnapshot.sha256(value);
    }
}
