package com.wish.rd.engine.bugfix.observability;

import com.wish.rd.engine.agent.impl.InMemoryAgentStageArtifactStore;
import com.wish.rd.engine.agent.impl.InMemoryAgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.context.impl.InMemoryRoleContextPackageStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugFixStageRecorderTest {

    @Test
    void shouldRecordSucceededStageWithArtifactsAndProviderMetadata() {
        InMemoryAgentStageRunStore runStore = new InMemoryAgentStageRunStore();
        InMemoryAgentStageArtifactStore artifactStore = new InMemoryAgentStageArtifactStore();
        InMemoryRoleContextPackageStore contextStore = new InMemoryRoleContextPackageStore();
        BugFixStageRecorder recorder = new BugFixStageRecorder(runStore, artifactStore, contextStore, generator());

        AgentStageRun running = recorder.start(
                "7481000000000000001",
                AgentRole.BUG_RAG_RETRIEVER,
                "ticket and log evidence"
        );
        AgentStageRun succeeded = recorder.succeed(
                running,
                "retrieved chunks: chunk-1",
                "long-cat",
                "[{\"provider\":\"long-cat\",\"status\":\"SUCCESS\"}]"
        );

        assertEquals(AgentStageStatus.SUCCEEDED, succeeded.status());
        assertEquals("long-cat", succeeded.providerName());
        assertTrue(succeeded.startedAtEpochMillis() > 0L);
        assertTrue(succeeded.finishedAtEpochMillis() >= succeeded.startedAtEpochMillis());
        assertFalse(succeeded.promptArtifactId().isBlank());
        assertFalse(succeeded.resultArtifactId().isBlank());
        assertFalse(succeeded.contextPackageId().isBlank());
        assertEquals(3, artifactStore.listByTask(succeeded.taskId()).size());
        assertEquals(succeeded.contextPackageId(), contextStore.listByTask(succeeded.taskId()).get(0).packageId());
    }

    @Test
    void shouldCreateNewAttemptAfterTerminalFailure() {
        InMemoryAgentStageRunStore runStore = new InMemoryAgentStageRunStore();
        BugFixStageRecorder recorder = new BugFixStageRecorder(
                runStore,
                new InMemoryAgentStageArtifactStore(),
                generator()
        );

        AgentStageRun first = recorder.start(
                "7481000000000000002",
                AgentRole.BUG_CODING_AGENT,
                "first execution"
        );
        AgentStageRun failed = recorder.failRetryable(first, "EXECUTOR_ERROR", "docker failed");
        AgentStageRun retry = recorder.start(
                "7481000000000000002",
                AgentRole.BUG_CODING_AGENT,
                "retry execution"
        );

        assertEquals(AgentStageStatus.FAILED_RETRYABLE, failed.status());
        assertEquals(1, failed.attemptNo());
        assertEquals(2, retry.attemptNo());
        assertNotEquals(first.stageRunId(), retry.stageRunId());
        assertEquals(2, runStore.listByTask(first.taskId()).size());
    }

    @Test
    void shouldRejectRequirementRoleForBugFixStage() {
        BugFixStageRecorder recorder = new BugFixStageRecorder(
                new InMemoryAgentStageRunStore(),
                new InMemoryAgentStageArtifactStore(),
                generator()
        );

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> recorder.start("7481000000000000003", AgentRole.REQUIREMENT_REVIEWER, "input")
        );

        assertTrue(exception.getMessage().contains("bug-fix role"));
    }

    private SnowflakeIdGenerator generator() {
        AtomicLong now = new AtomicLong(1_784_000_000_000L);
        return new SnowflakeIdGenerator(2, 2, now::getAndIncrement);
    }
}
