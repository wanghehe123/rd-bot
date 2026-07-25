package com.wish.rd.engine;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.engine.bugfix.model.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.model.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.bugfix.BugFixPromptBuilder;
import com.wish.rd.engine.bugfix.model.RdBotFixCommand;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.model.RdBotFixResult;
import com.wish.rd.engine.bugfix.observability.BugFixStageRecorder;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptanceAssertion;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlan;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationCommand;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanGenerationResult;
import com.wish.rd.engine.bugfix.acceptance.AcceptancePlanGeneratorPort;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanStatus;
import com.wish.rd.engine.bugfix.acceptance.model.AcceptancePlanStep;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdBotFixEngineTest {

    @Test
    void shouldRecordFourRealBugFixStagesWhenExecutionSucceeds() {
        RagStreamTaskRegistry registry = registry();
        InMemoryAgentStageRunStore runStore = new InMemoryAgentStageRunStore();
        BugFixStageRecorder recorder = new BugFixStageRecorder(
                runStore,
                new InMemoryAgentStageArtifactStore(),
                new SnowflakeIdGenerator(2, 3, new AtomicLong(1_784_100_000_000L)::getAndIncrement)
        );
        BugFixExecutor executor = request -> new BugFixExecutionResult(
                request.taskId(),
                "bug",
                "solution",
                "https://github.example/rd/pr/300",
                "{\"status\":\"SUCCESS\",\"providerName\":\"long-cat\",\"providerAttempts\":[{\"provider\":\"long-cat\",\"status\":\"SUCCESS\"}]}"
        );
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor,
                AcceptancePlanGeneratorPort.disabled(),
                recorder
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of("stack trace"), false, "P1"));

        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals(AgentRole.bugFixOrder(), runStore.listByTask(result.taskId()).stream()
                .map(stage -> stage.role())
                .toList());
        assertTrue(runStore.listByTask(result.taskId()).stream()
                .allMatch(stage -> stage.status() == AgentStageStatus.SUCCEEDED));
    }

    @Test
    void shouldRunBugFixThroughQueueRagPromptExecutorAndCommittedState() {
        AtomicReference<ChatQueueLimiter.ChatQueueRequest> queueRequest = new AtomicReference<>();
        AtomicReference<BugFixExecutionRequest> executionRequest = new AtomicReference<>();
        ChatQueueLimiter limiter = new ChatQueueLimiter() {
            @Override
            public <T> T enqueue(ChatQueueRequest request, Supplier<T> onAcquire, Supplier<T> onTimeout) {
                queueRequest.set(request);
                return onAcquire.get();
            }
        };
        BugFixExecutor executor = request -> {
            executionRequest.set(request);
            return new BugFixExecutionResult(
                    request.taskId(),
                    "OrderService.create 缺少金额校验",
                    "在保存订单前校验 amount，并补充空金额单测",
                    "https://github.example/rd/pr/200",
                    "{\"status\":\"success\"}"
            );
        };
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                limiter,
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(
                ticket(),
                List.of("ERROR orders.amount is null at OrderService.create"),
                false,
                "P0"
        ));

        RdBugFixTask task = registry.get(result.taskId());
        assertNotNull(queueRequest.get());
        assertEquals(result.taskId(), queueRequest.get().taskId());
        assertEquals("P0", queueRequest.get().priority());
        assertNotNull(executionRequest.get());
        assertTrue(executionRequest.get().prompt().contains("研发修复任务"));
        assertTrue(executionRequest.get().prompt().contains("## 执行边界"));
        assertTrue(executionRequest.get().prompt().contains("已验证的项目运行时镜像"));
        assertTrue(executionRequest.get().prompt().contains("不要在任务期间运行 sudo、apt-get"));
        assertTrue(executionRequest.get().prompt().contains("项目运行时 Dockerfile"));
        assertFalse(executionRequest.get().prompt().contains("sudo apt-get update"));
        assertFalse(executionRequest.get().prompt().contains("sudo apt-get install"));
        assertTrue(executionRequest.get().prompt().contains("\"pullRequestUrl\""));
        assertTrue(executionRequest.get().prompt().contains("OrderService.create"));
        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals(RdTaskStatus.COMMITTED, task.status());
        assertEquals("https://github.example/rd/pr/200", task.pullRequestUrl());
        assertTrue(task.promptSnapshot().contains("研发修复任务"));
        assertFalse(result.ragMessage().retrievedChunks().isEmpty());
    }

    @Test
    void shouldGenerateValidateAndPassAcceptancePlanToExecutor() {
        AtomicReference<AcceptancePlanGenerationCommand> planCommand = new AtomicReference<>();
        AtomicReference<BugFixExecutionRequest> executionRequest = new AtomicReference<>();
        AcceptancePlan plan = new AcceptancePlan(
                "placeholder",
                "FS-2001",
                AcceptancePlanStatus.READY,
                "docker-claude-planner",
                "",
                List.of(new AcceptancePlanStep("execute", "http", "POST /api/orders", "{}")),
                List.of(new AcceptanceAssertion("status", "http.status", "eq", "200")),
                List.of("chunk-1")
        );
        AcceptancePlanGeneratorPort generator = command -> {
            planCommand.set(command);
            return new AcceptancePlanGenerationResult(new AcceptancePlan(
                    command.taskId(),
                    command.ticketId(),
                    plan.status(),
                    plan.source(),
                    plan.reason(),
                    plan.steps(),
                    plan.assertions(),
                    plan.evidenceChunkIds()
            ));
        };
        BugFixExecutor executor = request -> {
            executionRequest.set(request);
            return new BugFixExecutionResult(
                    request.taskId(),
                    "bug",
                    "solution",
                    "https://github.example/rd/pr/201",
                    "{\"status\":\"success\"}"
            );
        };
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor,
                generator
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of(), false, "P1"));

        assertNotNull(planCommand.get());
        assertEquals(result.taskId(), planCommand.get().taskId());
        assertEquals("FS-2001", planCommand.get().ticketId());
        assertNotNull(executionRequest.get());
        assertEquals(AcceptancePlanStatus.READY, executionRequest.get().acceptancePlan().status());
        assertTrue(executionRequest.get().prompt().contains("## 验收计划"));
        assertTrue(executionRequest.get().prompt().contains("docker-claude-planner"));
        assertTrue(executionRequest.get().prompt().contains("POST /api/orders"));
        assertEquals(AcceptancePlanStatus.READY, result.acceptancePlan().status());
    }

    @Test
    void shouldRejectTaskWhenExecutorReturnsFailedStatus() {
        String failedResultJson = """
                {"status":"FAILED","errorMessage":"git command failed exitCode=128"}
                """.strip();
        BugFixExecutor executor = request -> new BugFixExecutionResult(
                request.taskId(),
                "Docker Claude Code execution failed.",
                "",
                "",
                failedResultJson
        );
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of(), false, "P1"));

        RdBugFixTask task = registry.get(result.taskId());
        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertEquals(RdTaskStatus.REJECTED, task.status());
        assertTrue(result.rejected());
        assertEquals(failedResultJson, task.executionResultJson());
        assertTrue(task.errorMessage().contains("status=FAILED"));
        assertTrue(task.errorMessage().contains("git command failed exitCode=128"));
        assertEquals("", task.pullRequestUrl());
    }

    @Test
    void shouldReuseRejectedTaskForSameTicketRetry() {
        AtomicLong executions = new AtomicLong();
        BugFixExecutor executor = request -> {
            long attempt = executions.incrementAndGet();
            if (attempt == 1) {
                return new BugFixExecutionResult(
                        request.taskId(),
                        "Docker Claude Code execution failed.",
                        "",
                        "",
                        "{\"status\":\"FAILED\",\"errorMessage\":\"git clone failed\"}"
                );
            }
            return new BugFixExecutionResult(
                    request.taskId(),
                    "OrderService.create 缺少金额校验",
                    "fixed",
                    "https://github.example/rd/pr/203",
                    "{\"status\":\"SUCCESS\"}"
            );
        };
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor
        );
        RdBotFixCommand command = new RdBotFixCommand(ticket(), List.of(), false, "P1");

        RdBotFixResult first = engine.runBugFix(command);
        RdBotFixResult retry = engine.runBugFix(command);

        assertEquals(first.taskId(), retry.taskId());
        assertEquals(1, registry.listBugFixTasks().size());
        assertEquals(RdTaskStatus.COMMITTED, retry.status());
        assertEquals("https://github.example/rd/pr/203", registry.get(retry.taskId()).pullRequestUrl());
        assertEquals(2, executions.get());
    }

    @Test
    void shouldCreateNewStageAttemptsWhenRetryingAfterAcceptancePlannerFailure() {
        RagStreamTaskRegistry registry = registry();
        InMemoryAgentStageRunStore runStore = new InMemoryAgentStageRunStore();
        BugFixStageRecorder recorder = new BugFixStageRecorder(
                runStore,
                new InMemoryAgentStageArtifactStore(),
                new SnowflakeIdGenerator(2, 3, new AtomicLong(1_784_100_000_000L)::getAndIncrement)
        );
        AtomicLong plannerCalls = new AtomicLong();
        AcceptancePlanGeneratorPort generator = command -> {
            if (plannerCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("planner unavailable");
            }
            return new AcceptancePlanGenerationResult(null);
        };
        BugFixExecutor executor = request -> new BugFixExecutionResult(
                request.taskId(), "bug", "fixed", "https://github.example/rd/pr/304", "{\"status\":\"SUCCESS\"}");
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(), ragEngine(registry), registry,
                BugFixPromptBuilder.defaultBuilder(), executor, generator, recorder);
        RdBotFixCommand command = new RdBotFixCommand(ticket(), List.of(), false, "P1");

        assertThrows(IllegalStateException.class, () -> engine.runBugFix(command));
        RdBugFixTask failed = registry.listBugFixTasks().get(0);
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, failed.status());

        RdBotFixResult retry = engine.runBugFix(command);

        assertEquals(failed.taskId(), retry.taskId());
        assertEquals(RdTaskStatus.COMMITTED, retry.status());
        assertEquals(List.of(1, 2), runStore.listByTask(retry.taskId()).stream()
                .filter(stage -> stage.role() == AgentRole.BUG_ACCEPTANCE_PLANNER)
                .map(stage -> stage.attemptNo())
                .toList());
    }

    @Test
    void shouldRejectSuccessfulStatusWithoutPullRequestUrl() {
        BugFixExecutor executor = request -> new BugFixExecutionResult(
                request.taskId(),
                "bug",
                "solution",
                "",
                "{\"status\":\"SUCCESS\"}"
        );
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of(), false, "P1"));

        RdBugFixTask task = registry.get(result.taskId());
        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertEquals(RdTaskStatus.REJECTED, task.status());
        assertTrue(task.errorMessage().contains("未生成 PR 链接"));
    }

    @Test
    void shouldAttachCurrentTaskAndTicketWhenPlannerReturnsNoPlan() {
        BugFixExecutor executor = request -> new BugFixExecutionResult(
                request.taskId(),
                "bug",
                "solution",
                "https://github.example/rd/pr/202",
                "{\"status\":\"success\"}"
        );
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                ChatQueueLimiter.passThrough(),
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor,
                command -> new AcceptancePlanGenerationResult(null)
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of(), false, "P1"));

        assertEquals(AcceptancePlanStatus.DISABLED, result.acceptancePlan().status());
        assertEquals(result.taskId(), result.acceptancePlan().taskId());
        assertEquals("FS-2001", result.acceptancePlan().ticketId());
        assertTrue(result.promptSnapshot().contains("RD-Bot 是最终验收裁判"));
    }

    @Test
    void shouldReturnRejectedWhenQueueLimiterTimesOutBeforeRagAndExecutor() {
        AtomicBoolean executorCalled = new AtomicBoolean(false);
        ChatQueueLimiter limiter = new ChatQueueLimiter() {
            @Override
            public <T> T enqueue(ChatQueueRequest request, Supplier<T> onAcquire, Supplier<T> onTimeout) {
                return onTimeout.get();
            }
        };
        BugFixExecutor executor = request -> {
            executorCalled.set(true);
            return BugFixExecutionResult.empty(request.taskId());
        };
        RagStreamTaskRegistry registry = registry();
        RdBotFixEngine engine = new RdBotFixEngine(
                limiter,
                ragEngine(registry),
                registry,
                BugFixPromptBuilder.defaultBuilder(),
                executor
        );

        RdBotFixResult result = engine.runBugFix(new RdBotFixCommand(ticket(), List.of(), false, "P2"));

        RdBugFixTask task = registry.get(result.taskId());
        assertEquals(RdTaskStatus.REJECTED, result.status());
        assertEquals(RdTaskStatus.REJECTED, task.status());
        assertEquals("系统繁忙，请稍后再试", task.errorMessage());
        assertTrue(result.rejected());
        assertFalse(executorCalled.get());
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

    private TicketSnapshot ticket() {
        return new TicketSnapshot(
                "FS-2001",
                "支付系统下单接口 500",
                "金额为空时 OrderService.create 写入订单失败",
                List.of("payment", "orders.amount"),
                Instant.parse("2026-06-21T00:00:00Z")
        );
    }
}
