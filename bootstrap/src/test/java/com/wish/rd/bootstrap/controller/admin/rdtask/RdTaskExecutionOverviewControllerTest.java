package com.wish.rd.bootstrap.controller.admin.rdtask;

import com.wish.rd.bootstrap.executor.DockerExecutorProperties;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.exec.repair.docker.impl.DockerExecutionRegistry;
import com.wish.rd.exec.repair.docker.model.ContainerRunRequest;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RdTaskExecutionOverviewControllerTest {

    private MockMvc mockMvc;
    private RagStreamTaskRegistry registry;
    private AgentStageRunStore stageRunStore;
    private AgentStageArtifactStore artifactStore;
    private RoleContextPackageStore contextPackageStore;
    private DockerExecutionRegistry executionRegistry;

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
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.setBudgetAlertUsd(new java.math.BigDecimal("7.50"));
        mockMvc = MockMvcBuilders.standaloneSetup(new RdTaskExecutionOverviewController(
                registry,
                stageRunStore,
                artifactStore,
                contextPackageStore,
                executionRegistry,
                properties
        )).build();
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
                        Path.of("/tmp/rd-bot/overview")
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
                .andExpect(jsonPath("$.budget.budgetAlertUsd", is(7.50)))
                .andExpect(jsonPath("$.budget.estimatedSpendUsd", is(0.12)))
                .andExpect(jsonPath("$.stageRuns", hasSize(1)))
                .andExpect(jsonPath("$.stageRuns[0].role", is("CODING_AGENT")))
                .andExpect(jsonPath("$.stageRuns[0].running", is(true)))
                .andExpect(jsonPath("$.stageRuns[0].elapsedMillis", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.stageRuns[0].providerAttempts[0].provider", is("long-cat")))
                .andExpect(jsonPath("$.runningExecutions", hasSize(1)))
                .andExpect(jsonPath("$.runningExecutions[0].containerName", is("rd-bot-repair-test")));
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
}
