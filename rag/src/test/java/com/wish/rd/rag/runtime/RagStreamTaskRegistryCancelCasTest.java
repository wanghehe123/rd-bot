package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagStreamTaskRegistryCancelCasTest {

    @Test
    void cancelUsesStoreCasAndRejectsStaleExpectedStatus() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        CountingStatePersistence persistence = new CountingStatePersistence(store, events);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                events,
                SnowflakeIdGenerator.defaultGenerator(),
                null,
                persistence
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "cancel-cas", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");

        long versionBeforeCancel = store.findVersion(created.taskId()).orElseThrow();
        int casCallsBeforeCancel = persistence.casCalls();
        RdTask cancelled = registry.cancelTask(created.taskId(), "operator stop");
        assertEquals(RdTaskStatus.CANCELLED, cancelled.status());
        assertEquals(casCallsBeforeCancel + 1, persistence.casCalls());
        assertEquals(versionBeforeCancel + 1L, store.findVersion(created.taskId()).orElseThrow());

        // Stale writer still believes task is MATERIAL_COLLECTING at the pre-cancel version.
        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                created.taskId(),
                versionBeforeCancel,
                RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.COMPLETED,
                "late worker"
        ));
        assertEquals(RdTaskStatus.CANCELLED, registry.getTask(created.taskId()).status());
    }

    @Test
    void staleDifferentTargetDoesNotRetryCancelCasWithLatestSnapshot() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore events = new InMemoryRdTaskStatusEventStore();
        CountingStatePersistence persistence = new CountingStatePersistence(store, events);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                store,
                events,
                SnowflakeIdGenerator.defaultGenerator(),
                null,
                persistence
        );
        RdRequirementTask created = registry.createRequirementTask(new CreateRequirementTaskCommand(
                "cancel-stale-race", "P1", "https://github.com/example/waimai.git",
                "example", "waimai", "main", "ok", List.of("a"), false
        ));
        registry.markRequirementMaterialCollecting(created.taskId(), "collect");

        persistence.injectDifferentTargetOnNextCas();
        int casCallsBeforeCancel = persistence.casCalls();
        assertThrows(IllegalStateException.class, () -> registry.cancelTask(created.taskId(), "operator stop"));

        // The injected peer write is the only competing mutation. The cancel CAS is attempted
        // once and the stale catch does not retry using the peer's newer version/fence.
        assertEquals(casCallsBeforeCancel + 1, persistence.casCalls());
        assertEquals(RdTaskStatus.MATERIAL_READY, registry.getTask(created.taskId()).status());
    }

    private static final class CountingStatePersistence implements RdTaskStatePersistence {

        private final InMemoryRdTaskStore store;
        private final CoordinatedRdTaskStatePersistence delegate;
        private int casCalls;
        private boolean injectDifferentTarget;

        private CountingStatePersistence(
                InMemoryRdTaskStore store,
                InMemoryRdTaskStatusEventStore eventStore
        ) {
            this.store = store;
            this.delegate = new CoordinatedRdTaskStatePersistence(store, eventStore);
        }

        @Override
        public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
            return delegate.saveWithEvent(task, event);
        }

        @Override
        public RdTask saveWithEventCas(
                RdTask task,
                RdTaskStatusEvent event,
                long expectedVersion,
                long expectedFencingToken,
                RdTaskStatus expectedStatus
        ) {
            casCalls++;
            if (injectDifferentTarget) {
                injectDifferentTarget = false;
                store.advanceStatusWithExpectedVersion(
                        task.taskId(),
                        expectedVersion,
                        expectedFencingToken,
                        expectedStatus,
                        RdTaskStatus.MATERIAL_READY,
                        "peer advanced",
                        null,
                        null,
                        null
                );
            }
            return delegate.saveWithEventCas(
                    task, event, expectedVersion, expectedFencingToken, expectedStatus);
        }

        @Override
        public RdTask saveActionWithEventCas(
                RdTask task,
                RdTaskStatusEvent event,
                long expectedVersion,
                long expectedFencingToken,
                RdTaskStatus expectedStatus
        ) {
            return delegate.saveActionWithEventCas(
                    task, event, expectedVersion, expectedFencingToken, expectedStatus);
        }

        private int casCalls() {
            return casCalls;
        }

        private void injectDifferentTargetOnNextCas() {
            injectDifferentTarget = true;
        }
    }
}
