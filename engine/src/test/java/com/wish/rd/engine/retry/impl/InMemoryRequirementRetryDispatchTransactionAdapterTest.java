package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementStageCommandStore;
import com.wish.rd.engine.retry.TaskRetryTaskPort;
import com.wish.rd.engine.retry.model.InitializeRequirementRetryCommand;
import com.wish.rd.engine.retry.model.RequirementRetryDispatchResult;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRequirementRetryDispatchTransactionAdapterTest {

    @Test
    void initializesAndReplaysTheExactCheckpointBoundInfrastructureCommand() {
        FakeTaskPort tasks = new FakeTaskPort(task());
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        InMemoryRequirementStageCommandStore commands = new InMemoryRequirementStageCommandStore();
        InMemoryRequirementRetryDispatchTransactionAdapter adapter =
                new InMemoryRequirementRetryDispatchTransactionAdapter(tasks, checkpoints, commands,
                        new InMemoryTaskRetryAttemptBindingStore());
        TaskRetryCheckpoint checkpoint = checkpoint();
        AtomicInteger preparationCalls = new AtomicInteger();

        RequirementRetryDispatchResult initialized = adapter.initialize(checkpoint, created -> {
            preparationCalls.incrementAndGet();
            return request(created);
        });
        RequirementRetryDispatchResult replayed = adapter.initialize(checkpoint, ignored -> {
            throw new AssertionError("a replay must not prepare retry attempts");
        });

        assertFalse(initialized.replayed());
        assertTrue(replayed.replayed());
        assertEquals("202", initialized.firstCommand().commandId());
        assertEquals("101", initialized.firstCommand().retryCheckpointId());
        assertEquals(101L, initialized.firstCommand().businessGeneration());
        assertEquals(TaskRetryCheckpointStatus.DISPATCHED, initialized.checkpoint().status());
        assertEquals("202", initialized.checkpoint().dispatchCommandId());
        assertEquals(initialized.firstCommand(), replayed.firstCommand());
        assertEquals(RdTaskStatus.RECOVERING, tasks.task.status());
        assertEquals(1, preparationCalls.get());
    }

    private static InitializeRequirementRetryCommand request(TaskRetryCheckpoint checkpoint) {
        return new InitializeRequirementRetryCommand(
                checkpoint, new TaskRetryRoute(RdTaskStatus.RECOVERING, "REQUIREMENT_DELIVERY",
                "MATERIAL_COLLECTING", null, null, List.of()), "202", 3, 9_000L,
                "project-1", "provider-1", "P1", "", List.of(), 1_000L);
    }

    private static TaskRetryCheckpoint checkpoint() {
        return new TaskRetryCheckpoint("101", "task-1", TaskFailurePhase.MATERIAL, null,
                "", "", "", 1, "retry-key", RdTaskStatus.FAILED_NEEDS_HUMAN, 12L,
                400L, "17", "MATERIAL_COLLECTING", "", "", "", "", "", 0L, 0L, "", 101L,
                "", List.of(), TaskRetryCheckpointStatus.CREATED, "failed", "", 1L, 1L);
    }

    private static RdRequirementTask task() {
        return new RdRequirementTask("task-1", "REQUIREMENT", "ADMIN", "source", "", "P1",
                RdTaskStatus.FAILED_NEEDS_HUMAN, "title", "project-1", "key", "project",
                "repo", "owner", "repo", "main", "feature", "expected", "[]", "", "{}", "",
                "failed", 1L, 1L, false, 0L, 12L, 400L);
    }

    private static final class FakeTaskPort implements TaskRetryTaskPort {
        private RdRequirementTask task;

        private FakeTaskPort(RdRequirementTask task) {
            this.task = task;
        }

        @Override
        public RdRequirementTask getRequirementTask(String taskId) {
            return task;
        }

        @Override
        public RdRequirementTask markRecovering(String taskId, String reason) {
            task = task.withState(RdTaskStatus.RECOVERING, "", "", "", reason, 2L)
                    .withConcurrency(task.version() + 1L, task.fencingToken() + 1L);
            return task;
        }

        @Override
        public RdRequirementTask markRetryPreparationFailed(String taskId, String reason) {
            throw new UnsupportedOperationException();
        }
    }
}
