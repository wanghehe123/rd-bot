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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wish.rd.rag.runtime.model.RdBugFixTask;

/**
 * {@link RagStreamTaskRegistry} 分布式锁接入测试。
 */
class RagStreamTaskRegistryDistributedLockTest {

    @Test
    void shouldUseTaskScopedLockForTaskTransitions() {
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
        lockExecutor.clear();
        registry.markSearching(task.taskId(), "检索中");
        registry.markExecuting(task.taskId(), "执行中");

        List<String> taskLocks = lockExecutor.lockNames().stream()
                .filter(name -> name.startsWith("rd-bot:lock:rd-task:"))
                .toList();
        assertEquals(List.of(
                "rd-bot:lock:rd-task:" + task.taskId(),
                "rd-bot:lock:rd-task:" + task.taskId()
        ), taskLocks);
        assertFalse(lockExecutor.lockNames().contains("rd-bot:lock:rag-stream-task-registry"));
        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task-store"));
        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task-status-event-store"));
    }

    @Test
    void shouldUseDifferentLockNamesForDifferentTasks() {
        RecordingLockExecutor lockExecutor = new RecordingLockExecutor();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(lockExecutor),
                new InMemoryRdTaskStatusEventStore(lockExecutor),
                generator(),
                lockExecutor
        );
        RdBugFixTask first = registry.createBugFixTask(
                new TicketSnapshot("FS-LOCK-2", "任务一", "", List.of(), Instant.now()),
                "P1"
        );
        RdBugFixTask second = registry.createBugFixTask(
                new TicketSnapshot("FS-LOCK-3", "任务二", "", List.of(), Instant.now()),
                "P1"
        );

        lockExecutor.clear();
        registry.markSearching(first.taskId(), "任务一检索");
        registry.markSearching(second.taskId(), "任务二检索");

        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task:" + first.taskId()));
        assertTrue(lockExecutor.lockNames().contains("rd-bot:lock:rd-task:" + second.taskId()));
        assertFalse(first.taskId().equals(second.taskId()));
        assertFalse(lockExecutor.lockNames().contains("rd-bot:lock:rag-stream-task-registry"));
    }

    @Test
    void shouldAllowDifferentTaskTransitionsToEnterTheirCriticalSectionsTogether() throws Exception {
        ParallelTaskLockExecutor lockExecutor = new ParallelTaskLockExecutor();
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                new InMemoryRdTaskStore(), new InMemoryRdTaskStatusEventStore(), generator(), lockExecutor);
        RdBugFixTask first = registry.createBugFixTask(
                new TicketSnapshot("FS-PARALLEL-1", "任务一", "", List.of(), Instant.now()), "P1");
        RdBugFixTask second = registry.createBugFixTask(
                new TicketSnapshot("FS-PARALLEL-2", "任务二", "", List.of(), Instant.now()), "P1");
        lockExecutor.observeNextTwoTaskLocks();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstFuture = executor.submit(() -> registry.markSearching(first.taskId(), "检索一"));
            var secondFuture = executor.submit(() -> registry.markSearching(second.taskId(), "检索二"));
            firstFuture.get(3, TimeUnit.SECONDS);
            secondFuture.get(3, TimeUnit.SECONDS);
        }

        assertEquals(2, lockExecutor.concurrentEntrants());
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

        private void clear() {
            lockNames.clear();
        }
    }

    private static final class ParallelTaskLockExecutor implements DistributedLockExecutor {

        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        private volatile CountDownLatch entrants;

        @Override
        public <T> T execute(String lockName, Supplier<T> action) {
            ReentrantLock lock = locks.computeIfAbsent(lockName, ignored -> new ReentrantLock());
            lock.lock();
            try {
                CountDownLatch barrier = entrants;
                if (barrier != null && lockName.startsWith("rd-bot:lock:rd-task:")) {
                    barrier.countDown();
                    if (!barrier.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("different task locks were globally serialized");
                    }
                }
                return action.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("task lock test interrupted", exception);
            } finally {
                lock.unlock();
            }
        }

        private void observeNextTwoTaskLocks() {
            entrants = new CountDownLatch(2);
        }

        private long concurrentEntrants() {
            return entrants == null ? 0L : 2L - entrants.getCount();
        }
    }
}
