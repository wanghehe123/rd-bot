package com.wish.rd.engine;

import com.wish.rd.adapter.TicketSnapshot;
import com.wish.rd.engine.bugfix.BugFixExecutionRequest;
import com.wish.rd.engine.bugfix.BugFixExecutionResult;
import com.wish.rd.engine.bugfix.BugFixExecutor;
import com.wish.rd.engine.bugfix.BugFixPromptBuilder;
import com.wish.rd.engine.bugfix.RdBotFixCommand;
import com.wish.rd.engine.bugfix.RdBotFixEngine;
import com.wish.rd.engine.bugfix.RdBotFixResult;
import com.wish.rd.engine.rag.ChatQueueLimiter;
import com.wish.rd.engine.rag.RagBugFixEngine;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.intent.IntentTreeRegistry;
import com.wish.rd.rag.rewrite.QueryTermMappingRegistry;
import com.wish.rd.rag.runtime.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.RdBugFixTask;
import com.wish.rd.rag.runtime.RdTaskStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdBotFixEngineTest {

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
        assertTrue(executionRequest.get().prompt().contains("OrderService.create"));
        assertEquals(RdTaskStatus.COMMITTED, result.status());
        assertEquals(RdTaskStatus.COMMITTED, task.status());
        assertEquals("https://github.example/rd/pr/200", task.pullRequestUrl());
        assertTrue(task.promptSnapshot().contains("研发修复任务"));
        assertFalse(result.ragMessage().retrievedChunks().isEmpty());
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
