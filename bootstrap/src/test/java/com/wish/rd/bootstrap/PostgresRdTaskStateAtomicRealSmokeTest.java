package com.wish.rd.bootstrap;

import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStatePersistence;
import com.wish.rd.bootstrap.persistence.impl.PostgresRdTaskStore;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rd.knowledge.store=postgres",
        "rd.storage.mode=memory",
        "rd.distributed-lock.mode=local",
        "rd.repair.queue.mode=memory"
})
@EnabledIfSystemProperty(named = "rd.integration.task-state-atomic.enabled", matches = "true")
class PostgresRdTaskStateAtomicRealSmokeTest {

    @Autowired
    private PostgresRdTaskStatePersistence persistence;

    @Autowired
    private PostgresRdTaskStore taskStore;

    @Autowired
    private RdTaskMapper taskMapper;

    @Test
    void shouldRollbackTaskSnapshotWhenTimelineInsertFails() {
        String taskId = String.valueOf(7_900_000_000_000_000_000L + System.currentTimeMillis() % 1_000_000L);
        String eventId = String.valueOf(Long.parseLong(taskId) + 1L);
        long now = System.currentTimeMillis();
        RdBugFixTask created = RdBugFixTask.created(taskId, "ATOMIC-REAL", "事务回滚", "P1", now);
        RdTaskStatusEvent createdEvent = event(eventId, taskId, RdTaskStatus.CREATED, now);
        persistence.saveWithEvent(created, createdEvent);
        try {
            RdBugFixTask searching = created.withState(
                    RdTaskStatus.SEARCHING, "", "", "", "", "", "", now + 1L);
            assertThrows(DataIntegrityViolationException.class, () -> persistence.saveWithEvent(
                    searching, event(eventId, taskId, RdTaskStatus.SEARCHING, now + 1L)));

            assertEquals(RdTaskStatus.CREATED, taskStore.findBugFixTask(taskId).orElseThrow().status());
        } finally {
            taskMapper.deleteById(Long.parseLong(taskId));
        }
    }

    private RdTaskStatusEvent event(String eventId, String taskId, RdTaskStatus status, long now) {
        return new RdTaskStatusEvent(
                eventId, taskId, status.name(), "事务回滚", "", now, 0L,
                RdTaskEventTrigger.SYSTEM.name());
    }
}
