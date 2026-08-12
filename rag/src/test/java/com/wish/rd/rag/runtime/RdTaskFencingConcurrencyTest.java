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
    void staleStageSnapshotCannotRefreshVersionBeforeValidatingCas() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store, new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask staleWorker = driveToExecuting(registry, "fence-stage-snapshot");

        registry.markRequirementValidating(staleWorker.taskId(), "{\"peer\":true}");

        assertThrows(IllegalStateException.class, () -> registry.transitionRequirementFenced(
                staleWorker,
                RdTaskStatus.VALIDATING,
                staleWorker.promptSnapshot(),
                "{\"stale\":true}",
                "",
                ""
        ));
        assertEquals(RdTaskStatus.VALIDATING, registry.getTask(staleWorker.taskId()).status());
    }

    @Test
    void zeroFenceStageSnapshotIsRejectedBeforeTaskOrTimelineMutation() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store, events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask persisted = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "zero-fence-stage", "P1", "https://github.com/example/repo.git",
                "example", "repo", "main", "ok", List.of("accepted"), false));
        RdRequirementTask legacySnapshot = persisted.withConcurrency(persisted.version(), 0L);
        int timelineBefore = events.listByTask(persisted.taskId()).size();

        assertThrows(IllegalArgumentException.class, () -> registry.transitionRequirementFenced(
                legacySnapshot, RdTaskStatus.MATERIAL_COLLECTING, "", "", "", ""));

        assertEquals(persisted, registry.getRequirementTask(persisted.taskId()));
        assertEquals(timelineBefore, events.listByTask(persisted.taskId()).size());
    }

    @Test
    void metadataEditFencesOutStaleRequirementStageSnapshot() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store, new InMemoryRdTaskStatusEventStore(), SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask staleWorker = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "原始需求", "P1", "ADMIN", "source-fence", "https://example.test/req/fence",
                "project-fence", "PROJ-FENCE", "项目 Fence",
                "https://github.com/example/repo.git", "owner", "repo", "main",
                "交付结果", List.of("保留验收标准"), List.of(), false, 256L));
        long originalVersion = staleWorker.version();
        long originalFencingToken = staleWorker.fencingToken();

        RdRequirementTask edited = (RdRequirementTask) registry.updateTaskMetadata(
                staleWorker.taskId(), "编辑后的需求", "P0", "ignored-ticket-title");

        assertEquals("编辑后的需求", edited.title());
        assertEquals("P0", edited.priority());
        assertEquals("project-fence", edited.projectId());
        assertEquals("[\"保留验收标准\"]", edited.acceptanceCriteriaJson());
        assertEquals(originalVersion + 1L, edited.version());
        assertEquals(originalFencingToken + 1L, edited.fencingToken());

        assertThrows(IllegalStateException.class, () -> registry.transitionRequirementFenced(
                staleWorker,
                RdTaskStatus.MATERIAL_COLLECTING,
                "stale prompt",
                "{\"stale\":true}",
                "",
                ""));

        RdRequirementTask persisted = (RdRequirementTask) registry.getTask(staleWorker.taskId());
        assertEquals(RdTaskStatus.CREATED, persisted.status());
        assertEquals("编辑后的需求", persisted.title());
        assertEquals("P0", persisted.priority());
        assertEquals("project-fence", persisted.projectId());
        assertEquals("[\"保留验收标准\"]", persisted.acceptanceCriteriaJson());
        assertEquals(edited.version(), persisted.version());
        assertEquals(edited.fencingToken(), persisted.fencingToken());
    }

    @Test
    void taskSnapshotCarriesVersionAndFencingTokenAcrossCasAdvance() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                new InMemoryRdTaskStatusEventStore(),
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask created = driveToExecuting(registry, "fence-snapshot");

        assertTrue(created.version() > 0L);
        assertTrue(created.fencingToken() > 0L);

        RdRequirementTask validating = registry.markRequirementValidating(
                created.taskId(), "{\"ok\":true}");

        assertEquals(created.version() + 1L, validating.version());
        assertTrue(validating.fencingToken() > created.fencingToken());
        assertEquals(validating.version(), store.findVersion(created.taskId()).orElseThrow());
        assertEquals(validating.fencingToken(), store.findFencingToken(created.taskId()).orElseThrow());
    }

    @Test
    void stalePublicationFinalizeUsesOriginalFencingSnapshotAndDoesNotAppendAnEvent() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                events,
                SnowflakeIdGenerator.defaultGenerator()
        );
        RdRequirementTask executing = driveToExecuting(registry, "fence-publication");
        RdRequirementTask validating = registry.markRequirementValidating(executing.taskId(), "{\"validation\":true}");
        RdRequirementTask prCreating = registry.markRequirementPrCreating(validating.taskId(), "{\"review\":true}");
        int eventCountBeforeStaleFinalize = events.listByTask(prCreating.taskId()).size();

        registry.markRequirementFailedNeedsHuman(
                prCreating.taskId(), "operator intervened", "{\"reason\":\"race\"}");

        assertThrows(IllegalStateException.class, () -> registry.markRequirementCommittedFenced(
                prCreating.taskId(),
                prCreating.version(),
                prCreating.status(),
                prCreating.fencingToken(),
                "https://github.com/example/waimai/pull/42",
                "{\"status\":\"SUCCESS\"}"
        ));
        assertEquals(RdTaskStatus.FAILED_NEEDS_HUMAN, registry.getTask(prCreating.taskId()).status());
        assertEquals(eventCountBeforeStaleFinalize + 1, events.listByTask(prCreating.taskId()).size());
    }

    @Test
    void zeroFencePublicationFinalizeIsRejectedBeforeTaskOrTimelineMutation() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(store, events, SnowflakeIdGenerator.defaultGenerator());
        RdRequirementTask executing = driveToExecuting(registry, "fence-publication-zero");
        RdRequirementTask validating = registry.markRequirementValidating(executing.taskId(), "{\"validation\":true}");
        RdRequirementTask prCreating = registry.markRequirementPrCreating(validating.taskId(), "{\"review\":true}");
        int timelineBefore = events.listByTask(prCreating.taskId()).size();

        assertThrows(IllegalArgumentException.class, () -> registry.markRequirementCommittedFenced(
                prCreating.taskId(), prCreating.version(), prCreating.status(), 0L,
                "https://github.com/example/waimai/pull/43", "{\"status\":\"SUCCESS\"}"));

        assertEquals(prCreating, registry.getRequirementTask(prCreating.taskId()));
        assertEquals(timelineBefore, events.listByTask(prCreating.taskId()).size());
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
