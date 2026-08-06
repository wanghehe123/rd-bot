package com.wish.rd.rag.runtime;

import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagStreamTaskRegistryCompleteCasTest {

    @Test
    void completePersistsPayloadThroughCasAndRejectsCancelRaceLoser() throws Exception {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "complete-cas", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        // Drive to a state that can legally reach COMPLETED or CANCELLED.
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");
        registry.markRequirementMaterialReady(created.taskId(), "ready");
        registry.markRequirementContextBuilding(created.taskId(), "ctx");
        registry.markRequirementContextReady(created.taskId(), "{}");
        registry.markRequirementPlanGenerating(created.taskId(), "plan");
        registry.markRequirementPlanGenerated(created.taskId(), "{}");
        registry.markRequirementWaitingPolicy(created.taskId(), "{}");
        registry.markRequirementExecuting(created.taskId(), "prompt");
        registry.markRequirementValidating(created.taskId(), "{}");
        registry.markRequirementPrCreating(created.taskId(), "{}");
        registry.markRequirementCommitted(created.taskId(), "https://example.com/pr/9", "{\"mid\":\"1\"}");
        registry.markRequirementReporting(created.taskId(), "{\"report\":true}");

        long versionBefore = store.findVersion(created.taskId()).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completeWins = new AtomicInteger();
        AtomicInteger cancelWins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> complete = pool.submit(() -> {
                start.await();
                try {
                    registry.markRequirementCompleted(
                            created.taskId(),
                            "https://example.com/pr/final",
                            "{\"status\":\"SUCCESS\"}"
                    );
                    completeWins.incrementAndGet();
                } catch (RuntimeException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            Future<?> cancel = pool.submit(() -> {
                start.await();
                try {
                    registry.cancelTask(created.taskId(), "operator stop");
                    cancelWins.incrementAndGet();
                } catch (RuntimeException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            complete.get();
            cancel.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, completeWins.get() + cancelWins.get());
        assertEquals(1, losses.get());
        RdTask terminal = registry.getTask(created.taskId());
        assertTrue(terminal.status() == RdTaskStatus.COMPLETED || terminal.status() == RdTaskStatus.CANCELLED);
        assertEquals(versionBefore + 1L, store.findVersion(created.taskId()).orElseThrow());
        if (terminal.status() == RdTaskStatus.COMPLETED) {
            RdRequirementTask completed = (RdRequirementTask) terminal;
            assertEquals("https://example.com/pr/final", completed.pullRequestUrl());
            assertTrue(completed.executionResultJson().contains("SUCCESS"));
        }
    }
}
