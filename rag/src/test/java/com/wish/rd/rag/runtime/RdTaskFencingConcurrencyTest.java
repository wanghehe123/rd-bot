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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdTaskFencingConcurrencyTest {

    @Test
    void staleValidatingWriteIsRejectedAfterPeerAdvancedVersion() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = driveToExecuting(registry, "fence-validating");

        registry.markRequirementValidating(created.taskId(), "{\"ok\":true}");
        long versionAfterPeer = store.findVersion(created.taskId()).orElseThrow();

        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                created.taskId(),
                versionAfterPeer - 1L,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.VALIDATING,
                "",
                "{\"stale\":true}",
                null
        ));
        assertEquals(RdTaskStatus.VALIDATING, registry.getTask(created.taskId()).status());
        assertEquals(versionAfterPeer, store.findVersion(created.taskId()).orElseThrow());
    }

    @Test
    void cancelVersusNeedsHumanRaceAllowsExactlyOneWinner() throws Exception {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = driveToExecuting(registry, "fence-race");
        long versionBefore = store.findVersion(created.taskId()).orElseThrow();

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> needsHuman = pool.submit(() -> {
                start.await();
                try {
                    registry.markRequirementFailedNeedsHuman(
                            created.taskId(),
                            "WAITING_POLICY",
                            "{\"aggregateStatus\":\"WAITING_POLICY\"}"
                    );
                    wins.incrementAndGet();
                } catch (RuntimeException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            Future<?> cancel = pool.submit(() -> {
                start.await();
                try {
                    registry.cancelTask(created.taskId(), "operator stop");
                    wins.incrementAndGet();
                } catch (RuntimeException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            needsHuman.get();
            cancel.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, wins.get());
        assertEquals(1, losses.get());
        RdTask terminal = registry.getTask(created.taskId());
        assertTrue(
                terminal.status() == RdTaskStatus.FAILED_NEEDS_HUMAN
                        || terminal.status() == RdTaskStatus.CANCELLED
        );
        assertEquals(versionBefore + 1L, store.findVersion(created.taskId()).orElseThrow());
    }

    private static RdRequirementTask driveToExecuting(RagStreamTaskRegistry registry, String title) {
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                title, "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");
        registry.markRequirementMaterialReady(created.taskId(), "ready");
        registry.markRequirementContextBuilding(created.taskId(), "ctx");
        registry.markRequirementContextReady(created.taskId(), "{}");
        registry.markRequirementPlanGenerating(created.taskId(), "plan");
        registry.markRequirementPlanGenerated(created.taskId(), "{}");
        registry.markRequirementWaitingPolicy(created.taskId(), "{}");
        return registry.markRequirementExecuting(created.taskId(), "prompt-snapshot");
    }
}
