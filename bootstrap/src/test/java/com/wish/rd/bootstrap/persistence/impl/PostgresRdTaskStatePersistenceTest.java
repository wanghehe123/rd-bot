package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRdTaskStatePersistenceTest {

    @Test
    void shouldSaveRequirementSnapshotAndEventInOneTransactionalMethod() throws Exception {
        PostgresRdTaskStore taskStore = mock(PostgresRdTaskStore.class);
        PostgresRdTaskStatusEventStore eventStore = mock(PostgresRdTaskStatusEventStore.class);
        PostgresRdTaskStatePersistence persistence = new PostgresRdTaskStatePersistence(taskStore, eventStore);
        RdRequirementTask task = RdRequirementTask.created("1784100000001", command(), 1_784_100_000_000L)
                .withState(RdTaskStatus.MATERIAL_COLLECTING, "", "", "", "", 1_784_100_000_001L);
        RdTaskStatusEvent event = new RdTaskStatusEvent(
                "1784100000002", task.taskId(), task.status().name(), task.title(), "", 1L, 0L,
                RdTaskEventTrigger.SYSTEM.name());
        when(taskStore.saveRequirementTask(task)).thenReturn(task);

        persistence.saveWithEvent(task, event);

        verify(taskStore).saveRequirementTask(task);
        verify(eventStore).save(event);
        Method method = PostgresRdTaskStatePersistence.class.getMethod(
                "saveWithEvent", com.wish.rd.rag.runtime.model.RdTask.class, RdTaskStatusEvent.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
        assertEquals(task, persistence.saveWithEvent(task, null));
    }

    @Test
    void shouldSavePauseOrMetadataActionThroughFencedCasTransaction() throws Exception {
        PostgresRdTaskStore taskStore = mock(PostgresRdTaskStore.class);
        PostgresRdTaskStatusEventStore eventStore = mock(PostgresRdTaskStatusEventStore.class);
        PostgresRdTaskStatePersistence persistence = new PostgresRdTaskStatePersistence(taskStore, eventStore);
        RdRequirementTask task = RdRequirementTask.created("1784100000003", command(), 1_784_100_000_000L)
                .withPaused(true, 1_784_100_000_001L);
        RdTaskStatusEvent event = new RdTaskStatusEvent(
                "1784100000004", task.taskId(), RdTaskStatusEvent.ACTION_PAUSED, task.title(), "pause", 1L, 0L,
                RdTaskEventTrigger.API.name());
        when(taskStore.findTask(task.taskId())).thenReturn(java.util.Optional.of(task));

        assertEquals(task, persistence.saveActionWithEventCas(
                task, event, 0L, 1L, RdTaskStatus.CREATED));

        verify(taskStore).updateTaskWithExpectedVersion(eq(task), eq(0L), eq(1L), eq(RdTaskStatus.CREATED));
        verify(eventStore).save(event);
        Method method = PostgresRdTaskStatePersistence.class.getMethod(
                "saveActionWithEventCas", com.wish.rd.rag.runtime.model.RdTask.class,
                RdTaskStatusEvent.class, long.class, long.class, RdTaskStatus.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private CreateRequirementTaskCommand command() {
        return new CreateRequirementTaskCommand(
                "原子写入", "P1", "manual", "REQ-ATOMIC", "", "https://example.com/repo.git",
                "main", List.of("状态与事件一致"), false);
    }
}
