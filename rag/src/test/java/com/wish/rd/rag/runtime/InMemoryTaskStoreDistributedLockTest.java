package com.wish.rd.rag.runtime;

import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存任务相关 store 的分布式锁接入测试。
 */
class InMemoryTaskStoreDistributedLockTest {

    @Test
    void shouldGuardMaterialStoreWithDistributedLock() {
        RecordingLockExecutor lockExecutor = new RecordingLockExecutor();
        InMemoryTaskMaterialStore store = new InMemoryTaskMaterialStore(lockExecutor);

        store.save(new TaskMaterial(
                "mat-1",
                "task-1",
                TaskMaterialType.REQUIREMENT_DOC,
                TaskMaterialSourceType.MANUAL_TEXT,
                "需求",
                "",
                "text/plain",
                "sha256:test",
                "content",
                "",
                "",
                "",
                "{}",
                1000L,
                1000L
        ));
        store.listByTask("task-1");
        store.deleteByTask("task-1");

        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:task-material-store"));
    }

    @Test
    void shouldGuardStatusEventStoreWithDistributedLock() {
        RecordingLockExecutor lockExecutor = new RecordingLockExecutor();
        InMemoryRdTaskStatusEventStore store = new InMemoryRdTaskStatusEventStore(lockExecutor);

        store.save(new RdTaskStatusEvent(
                "event-1",
                "task-1",
                RdTaskStatus.CREATED.name(),
                "创建",
                "",
                1000L,
                0L,
                RdTaskEventTrigger.API.name()
        ));
        store.listByTask("task-1");
        store.deleteByTask("task-1");

        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task-status-event-store"));
    }

    private static final class RecordingLockExecutor implements DistributedLockExecutor {

        private final List<String> lockNames = new ArrayList<>();

        @Override
        public <T> T execute(String lockName, Supplier<T> action) {
            lockNames.add(lockName);
            return action.get();
        }

        private List<String> lockNames() {
            return List.copyOf(lockNames);
        }
    }
}
