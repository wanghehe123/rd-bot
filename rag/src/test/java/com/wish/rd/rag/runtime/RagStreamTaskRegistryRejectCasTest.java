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

class RagStreamTaskRegistryRejectCasTest {

    @Test
    void rejectPersistsPayloadThroughCasAndRejectsCancelRaceLoser() throws Exception {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "reject-cas", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");
        registry.markRequirementMaterialReady(created.taskId(), "ready");
        registry.markRequirementContextBuilding(created.taskId(), "ctx");
        registry.markRequirementContextReady(created.taskId(), "{}");
        registry.markRequirementPlanGenerating(created.taskId(), "plan");
        registry.markRequirementPlanGenerated(created.taskId(), "{}");
        registry.markRequirementWaitingPolicy(created.taskId(), "{}");
        registry.markRequirementExecuting(created.taskId(), "prompt");

        long versionBefore = store.findVersion(created.taskId()).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejectWins = new AtomicInteger();
        AtomicInteger cancelWins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reject = pool.submit(() -> {
                start.await();
                try {
                    registry.markRequirementRejected(
                            created.taskId(),
                            "delivery rejected",
                            "{\"status\":\"FAILED\"}"
                    );
                    rejectWins.incrementAndGet();
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
            reject.get();
            cancel.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, rejectWins.get() + cancelWins.get());
        assertEquals(1, losses.get());
        RdTask terminal = registry.getTask(created.taskId());
        assertTrue(terminal.status() == RdTaskStatus.REJECTED || terminal.status() == RdTaskStatus.CANCELLED);
        assertEquals(versionBefore + 1L, store.findVersion(created.taskId()).orElseThrow());
        if (terminal.status() == RdTaskStatus.REJECTED) {
            RdRequirementTask rejected = (RdRequirementTask) terminal;
            assertEquals("delivery rejected", rejected.errorMessage());
            assertTrue(rejected.executionResultJson().contains("FAILED"));
        }
    }

    @Test
    void failedNeedsHumanIsIdempotentUnderCas() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "needs-human-cas", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");
        registry.markRequirementMaterialReady(created.taskId(), "ready");
        registry.markRequirementContextBuilding(created.taskId(), "ctx");
        registry.markRequirementContextReady(created.taskId(), "{}");
        registry.markRequirementPlanGenerating(created.taskId(), "plan");
        registry.markRequirementPlanGenerated(created.taskId(), "{}");
        registry.markRequirementWaitingPolicy(created.taskId(), "{}");
        registry.markRequirementExecuting(created.taskId(), "prompt");

        RdRequirementTask first = registry.markRequirementFailedNeedsHuman(
                created.taskId(),
                "WAITING_POLICY",
                "{\"aggregateStatus\":\"WAITING_POLICY\"}"
        );
        long versionAfterFirst = store.findVersion(created.taskId()).orElseThrow();
        RdRequirementTask second = registry.markRequirementFailedNeedsHuman(
                created.taskId(),
                "duplicate",
                "{\"aggregateStatus\":\"WAITING_POLICY\"}"
        );

        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, first.status());
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, second.status());
        assertEquals("WAITING_POLICY", second.errorMessage());
        assertEquals(versionAfterFirst, store.findVersion(created.taskId()).orElseThrow());
    }
}
