package com.wish.rd.engine.agent.impl;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryAgentStageArtifactStoreTest {

    private final InMemoryAgentStageArtifactStore store = new InMemoryAgentStageArtifactStore();

    @Test
    void saveImmutableShouldBeIdempotentForMatchingHash() {
        AgentStageArtifact artifact = artifact("7478000000000000201", "sha256:abc");
        AgentStageArtifact saved = store.saveImmutable(artifact);
        AgentStageArtifact again = store.saveImmutable(copy(artifact, "different preview", "sha256:abc"));

        assertSame(saved, again);
        assertEquals(1, store.listByTask(artifact.taskId()).size());
    }

    @Test
    void saveImmutableShouldRejectConflictingHash() {
        AgentStageArtifact artifact = artifact("7478000000000000201", "sha256:abc");
        store.saveImmutable(artifact);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> store.saveImmutable(copy(artifact, "different preview", "sha256:def"))
        );
        assertEquals("immutable artifact conflict: 7478000000000000201", error.getMessage());
    }

    @Test
    void saveImmutableConcurrentDifferentHashShouldRetainFirstWriterAndThrowSecond() throws Exception {
        AgentStageArtifact first = artifact("7478000000000000202", "sha256:first");
        AgentStageArtifact second = copy(first, "{\"version\":2}", "sha256:second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AgentStageArtifact> winner = new AtomicReference<>();
        AtomicReference<Throwable> loserError = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runConcurrentSave(store, first, ready, start, winner, loserError));
            executor.submit(() -> runConcurrentSave(store, second, ready, start, winner, loserError));
            ready.await();
            start.countDown();
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertNotNull(winner.get());
        assertEquals("sha256:first", winner.get().contentHash());
        assertNotNull(loserError.get());
        assertEquals(IllegalStateException.class, loserError.get().getClass());
        assertEquals("immutable artifact conflict: 7478000000000000202", loserError.get().getMessage());
        assertEquals(1, store.listByTask(first.taskId()).size());
    }

    @Test
    void saveImmutableConcurrentSameHashShouldReturnStoredContentWithoutOverwrite() throws Exception {
        AgentStageArtifact first = artifact("7478000000000000203", "sha256:same");
        AgentStageArtifact second = copy(first, "{\"version\":2}", "sha256:same");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AgentStageArtifact> firstResult = new AtomicReference<>();
        AtomicReference<AgentStageArtifact> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> runConcurrentSaveSameHash(store, first, ready, start, firstResult, error));
            executor.submit(() -> runConcurrentSaveSameHash(store, second, ready, start, secondResult, error));
            ready.await();
            start.countDown();
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals(null, error.get());
        assertNotNull(firstResult.get());
        assertNotNull(secondResult.get());
        assertEquals(firstResult.get().contentPreview(), secondResult.get().contentPreview());
        assertEquals("sha256:same", firstResult.get().contentHash());
        assertEquals(1, store.listByTask(first.taskId()).size());
    }

    private static void runConcurrentSaveSameHash(
            InMemoryAgentStageArtifactStore store,
            AgentStageArtifact artifact,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<AgentStageArtifact> result,
            AtomicReference<Throwable> error
    ) {
        ready.countDown();
        try {
            start.await();
            result.set(store.saveImmutable(artifact));
        } catch (Throwable throwable) {
            error.compareAndSet(null, throwable);
        }
    }

    private static void runConcurrentSave(
            InMemoryAgentStageArtifactStore store,
            AgentStageArtifact artifact,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<AgentStageArtifact> winner,
            AtomicReference<Throwable> loserError
    ) {
        ready.countDown();
        try {
            start.await();
            AgentStageArtifact saved = store.saveImmutable(artifact);
            winner.compareAndSet(null, saved);
        } catch (Throwable throwable) {
            loserError.compareAndSet(null, throwable);
        }
    }

    private static AgentStageArtifact artifact(String artifactId, String contentHash) {
        return new AgentStageArtifact(
                artifactId,
                "7478000000000000101",
                "7478000000000000000",
                AgentRole.CODING_AGENT,
                "ROLE_EXECUTION_INPUT_MANIFEST",
                "rd-agent-stage://7478000000000000000/7478000000000000101/manifest",
                "manifest",
                "{\"version\":1}",
                contentHash,
                "{}",
                1_783_000_000_000L
        );
    }

    private static AgentStageArtifact copy(
            AgentStageArtifact artifact,
            String contentPreview,
            String contentHash
    ) {
        return new AgentStageArtifact(
                artifact.artifactId(),
                artifact.stageRunId(),
                artifact.taskId(),
                artifact.role(),
                artifact.artifactType(),
                artifact.artifactUri(),
                artifact.summary(),
                contentPreview,
                contentHash,
                artifact.metadataJson(),
                artifact.createdAtEpochMillis()
        );
    }
}
