package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.impl.InMemoryRequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementDeliveryDispatchServiceTest {

    @Test
    void shouldPersistClaimAndCompletionAroundExecution() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-1")).thenReturn(new RequirementDeliveryResult(
                "task-1", RdTaskStatus.COMPLETED, "", "{}", ""));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.submit("task-1").join();

        assertEquals(RequirementDeliveryJobStatus.SUCCEEDED, store.findByTask("task-1").orElseThrow().status());
        assertEquals(1, store.findByTask("task-1").orElseThrow().attemptNo());
    }

    @Test
    void shouldPersistRetryableFailureInsteadOfLosingRejectedThreadPoolWork() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-2")).thenThrow(new IllegalStateException("boom"));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-2").join());

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                store.findByTask("task-2").orElseThrow().status());
    }

    @Test
    void shouldPersistLinkageErrorAsRetryableFailureInsteadOfStrandingTheDeliveryJob() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-linkage")).thenThrow(new NoSuchMethodError("stale runtime artifact"));
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-linkage", RdTaskStatus.EXECUTING));
        AtomicLong now = new AtomicLong(1_784_200_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs, ids, registry,
                "test-worker", 3, 60_000L);

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-linkage").join());

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                jobs.findByTask("task-linkage").orElseThrow().status());
        RdRequirementTask failed = (RdRequirementTask) registry.getTask("task-linkage");
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, failed.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                failed.executionResultJson().contains("\"errorCategory\":\"NoSuchMethodError\""));
    }

    @Test
    void shouldKeepPersistedJobPendingWhenTheLocalExecutorRejectsScheduling() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, executor, store, new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                null, "test-worker", 3, 60_000L);

        assertDoesNotThrow(() -> dispatcher.submit("task-pending"));

        assertEquals(RequirementDeliveryJobStatus.PENDING,
                store.findByTask("task-pending").orElseThrow().status());
    }

    @Test
    void shouldFailJobWhenEngineReturnsRetryableOrNeedsHumanStatusWithoutThrowing() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-3")).thenReturn(new RequirementDeliveryResult(
                "task-3", RdTaskStatus.FAILED_RETRYABLE, "", "{}", "stage boom"));
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.submit("task-3").join();

        assertEquals(RequirementDeliveryJobStatus.FAILED_RETRYABLE,
                store.findByTask("task-3").orElseThrow().status());
    }

    @Test
    void shouldDeadLetterExhaustedExpiredLeaseDuringRecovery() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        InMemoryRequirementDeliveryJobStore store = new InMemoryRequirementDeliveryJobStore();
        store.enqueue(RequirementDeliveryJob.pending("job-1", "task-4", 1, 100L));
        store.claim("task-4", "old-worker", 110L, 10L).orElseThrow();
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, store);

        dispatcher.recover();

        assertEquals(RequirementDeliveryJobStatus.DEAD_LETTERED,
                store.findByTask("task-4").orElseThrow().status());
    }

    @Test
    void recoverSkipsProjectWhenInFlightAlreadyAtCap() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit(any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return new RequirementDeliveryResult(taskId, RdTaskStatus.COMPLETED, "", "{}", "");
        });
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        // FairScheduleLimits.defaults().maxPerProject() == 2
        taskStore.saveRequirementTask(requirementTask("t-a0", "proj-a", RdTaskStatus.EXECUTING));
        taskStore.saveRequirementTask(requirementTask("t-a1", "proj-a", RdTaskStatus.EXECUTING));
        taskStore.saveRequirementTask(requirementTask("t-a2", "proj-a", RdTaskStatus.CREATED));
        taskStore.saveRequirementTask(requirementTask("t-b1", "proj-b", RdTaskStatus.CREATED));
        AtomicLong idsClock = new AtomicLong(1_784_300_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, idsClock::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        long leaseNow = System.currentTimeMillis();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a0", "t-a0", 3, leaseNow));
        jobs.claim("t-a0", "worker-live", leaseNow, 3_600_000L).orElseThrow();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a1", "t-a1", 3, leaseNow + 1));
        jobs.claim("t-a1", "worker-live", leaseNow + 1, 3_600_000L).orElseThrow();
        jobs.enqueue(RequirementDeliveryJob.pending("j-a2", "t-a2", 3, leaseNow + 2));
        jobs.enqueue(RequirementDeliveryJob.pending("j-b1", "t-b1", 3, leaseNow + 3));
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                jobs,
                ids,
                registry,
                "test-worker",
                3,
                600_000L
        );

        dispatcher.recover();

        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).submit("t-b1");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a2");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a0");
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).submit("t-a1");
        assertEquals(RequirementDeliveryJobStatus.RUNNING, jobs.findByTask("t-a0").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.RUNNING, jobs.findByTask("t-a1").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.PENDING, jobs.findByTask("t-a2").orElseThrow().status());
        assertEquals(RequirementDeliveryJobStatus.SUCCEEDED, jobs.findByTask("t-b1").orElseThrow().status());
        assertEquals(2, jobs.listInFlight(System.currentTimeMillis()).size());
    }

    @Test
    void shouldCompleteActiveRetryCheckpointWithDeliveryOutcome() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-retry")).thenReturn(new RequirementDeliveryResult(
                "task-retry", RdTaskStatus.COMPLETED, "", "{}", ""));
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        InMemoryTaskRetryCheckpointStore checkpoints = new InMemoryTaskRetryCheckpointStore();
        TaskRetryPoint point = new TaskRetryPoint("task-retry", TaskFailurePhase.PR_PUBLICATION,
                null, "", "", "", "publish failed", 9L);
        TaskRetryCheckpoint created = checkpoints.createOrGet(TaskRetryCheckpoint.created(
                "checkpoint-1", point, 1, "task-retry:9:PR_PUBLICATION:",
                RdTaskStatus.REJECTED, 100L)).checkpoint();
        checkpoints.transition(created.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                TaskRetryCheckpointStatus.DISPATCHED, "", 110L);
        RequirementDeliveryDispatchService dispatcher = dispatcher(engine, jobs, checkpoints);

        dispatcher.submit("task-retry").join();

        assertEquals(TaskRetryCheckpointStatus.SUCCEEDED,
                checkpoints.find("checkpoint-1").orElseThrow().status());
    }

    @Test
    void shouldPersistStructuredEarlyPipelineFailureForExactRetryResolution() {
        RequirementDeliveryEngine engine = mock(RequirementDeliveryEngine.class);
        when(engine.submit("task-context")).thenThrow(new IllegalStateException("context provider timeout"));
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        taskStore.saveRequirementTask(requirementTask("task-context", RdTaskStatus.CONTEXT_BUILDING));
        AtomicLong now = new AtomicLong(1_784_200_000_000L);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(1, 1, now::getAndIncrement);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore, new InMemoryRdTaskStatusEventStore(), ids);
        InMemoryRequirementDeliveryJobStore jobs = new InMemoryRequirementDeliveryJobStore();
        RequirementDeliveryDispatchService dispatcher = new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), jobs, ids, registry,
                "test-worker", 3, 60_000L);

        assertThrows(RuntimeException.class, () -> dispatcher.submit("task-context").join());

        RdRequirementTask failed = (RdRequirementTask) registry.getTask("task-context");
        assertEquals(RdTaskStatus.FAILED_RETRYABLE, failed.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                failed.executionResultJson().contains("\"failurePhase\":\"CONTEXT\""));
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore store
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        return new RequirementDeliveryDispatchService(
                engine,
                new TaskExecutorAdapter(new SyncTaskExecutor()),
                store,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement),
                null,
                "test-worker",
                3,
                60_000L
        );
    }

    private RequirementDeliveryDispatchService dispatcher(
            RequirementDeliveryEngine engine,
            InMemoryRequirementDeliveryJobStore store,
            InMemoryTaskRetryCheckpointStore checkpoints
    ) {
        AtomicLong now = new AtomicLong(1_784_100_000_000L);
        return new RequirementDeliveryDispatchService(
                engine, new TaskExecutorAdapter(new SyncTaskExecutor()), store,
                new SnowflakeIdGenerator(1, 1, now::getAndIncrement), null,
                "test-worker", 3, 60_000L, null, checkpoints);
    }

    private static RdRequirementTask requirementTask(String taskId, RdTaskStatus status) {
        return requirementTask(taskId, "project-1", status);
    }

    private static RdRequirementTask requirementTask(String taskId, String projectId, RdTaskStatus status) {
        return new RdRequirementTask(
                taskId, "REQUIREMENT", "ADMIN", "source", "", "P1", status,
                "订单状态筛选", projectId, "waimai", "外卖项目", "https://github.com/acme/waimai",
                "acme", "waimai", "main", "feature/status", "支持状态筛选", "[\"筛选正确\"]",
                "prompt", "{}", "", "", 10L, 20L, false);
    }
}
