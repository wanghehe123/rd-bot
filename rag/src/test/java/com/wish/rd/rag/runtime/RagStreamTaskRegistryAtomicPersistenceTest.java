package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.lock.DistributedLockExecutor;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that a task snapshot and its timeline event are persisted as one mutation call. */
class RagStreamTaskRegistryAtomicPersistenceTest {

    @Test
    void shouldPersistEachTaskMutationThroughSingleAtomicPortCall() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore eventStore = new InMemoryRdTaskStatusEventStore();
        RecordingStatePersistence persistence = new RecordingStatePersistence(taskStore, eventStore);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore,
                eventStore,
                generator(),
                DistributedLockExecutor.local(),
                persistence
        );

        RdBugFixTask task = registry.createBugFixTask(new TicketSnapshot(
                "FS-ATOMIC-1", "原子状态写入", "", List.of(), Instant.now()), "P1");
        registry.markSearching(task.taskId(), "开始检索");

        assertEquals(2, persistence.calls());
        assertEquals(2, eventStore.listByTask(task.taskId()).size());
        assertEquals("SEARCHING", eventStore.listByTask(task.taskId()).getLast().status());
        assertEquals("SEARCHING", taskStore.findBugFixTask(task.taskId()).orElseThrow().status().name());
    }

    private SnowflakeIdGenerator generator() {
        AtomicInteger sequence = new AtomicInteger();
        return new SnowflakeIdGenerator(1, 1, () -> 1_784_100_000_000L + sequence.getAndIncrement());
    }

    private static final class RecordingStatePersistence implements RdTaskStatePersistence {

        private final RdTaskStore taskStore;
        private final RdTaskStatusEventStore eventStore;
        private int calls;

        private RecordingStatePersistence(RdTaskStore taskStore, RdTaskStatusEventStore eventStore) {
            this.taskStore = taskStore;
            this.eventStore = eventStore;
        }

        @Override
        public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
            calls++;
            RdTask saved = task instanceof RdBugFixTask bugFixTask
                    ? taskStore.saveBugFixTask(bugFixTask)
                    : taskStore.saveRequirementTask((com.wish.rd.rag.runtime.model.RdRequirementTask) task);
            if (event != null) {
                eventStore.save(event);
            }
            return saved;
        }

        private int calls() {
            return calls;
        }
    }
}
