package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.lock.DistributedLockExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.runtime.model.RdBugFixTask;

/**
 * {@link RagStreamTaskRegistry} 分布式锁接入测试。
 */
class RagStreamTaskRegistryDistributedLockTest {

    @Test
    void shouldGuardTaskCreationAndTransitionWithDistributedLock() {
        RecordingLockExecutor lockExecutor = new RecordingLockExecutor();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(lockExecutor),
                new InMemoryRdTaskStatusEventStore(lockExecutor),
                generator(),
                lockExecutor
        );

        RdBugFixTask task = registry.createBugFixTask(
                new TicketSnapshot("FS-LOCK-1", "锁测试", "", List.of(), Instant.now()),
                "P1"
        );
        registry.markSearching(task.taskId(), "检索中");

        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rag-stream-task-registry"));
        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task-store"));
        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task-status-event-store"));
    }

    private SnowflakeIdGenerator generator() {
        return new SnowflakeIdGenerator(1, 1, () -> 1_784_000_000_000L);
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
