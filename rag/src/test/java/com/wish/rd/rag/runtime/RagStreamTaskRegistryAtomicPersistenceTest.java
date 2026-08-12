package com.wish.rd.rag.runtime;

import com.wish.rd.adapter.model.TicketSnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.lock.DistributedLockExecutor;
import com.wish.rd.rag.runtime.impl.CoordinatedRdTaskStatePersistence;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStatusEventStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdBugFixTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldRollBackTaskSnapshotWhenTimelineEventWriteFails() {
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore();
        InMemoryRdTaskStatusEventStore persistedEvents = new InMemoryRdTaskStatusEventStore();
        FailOnceEventStore eventStore = new FailOnceEventStore(persistedEvents);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(
                taskStore,
                eventStore,
                generator(),
                DistributedLockExecutor.local(),
                null
        );

        RdBugFixTask created = registry.createBugFixTask(new TicketSnapshot(
                "FS-ATOMIC-ROLLBACK", "事件失败回滚", "", List.of(), Instant.now()), "P1");
        int timelineBefore = persistedEvents.listByTask(created.taskId()).size();
        eventStore.failNextSave();

        assertThrows(IllegalStateException.class, () -> registry.markSearching(created.taskId(), "开始检索"));

        assertEquals(created, taskStore.findBugFixTask(created.taskId()).orElseThrow());
        assertEquals(timelineBefore, persistedEvents.listByTask(created.taskId()).size());
    }

    @Test
    void shouldKeepConcurrentWriterOutUntilEventFailureRollbackCompletes() throws Exception {
        ObservingReentrantLockExecutor storeLock = new ObservingReentrantLockExecutor();
        InMemoryRdTaskStore taskStore = new InMemoryRdTaskStore(storeLock);
        BlockingFailOnceEventStore eventStore = new BlockingFailOnceEventStore(
                new InMemoryRdTaskStatusEventStore(), storeLock::markFailureWindow);
        RagStreamTaskRegistry registry = new RagStreamTaskRegistry(taskStore, eventStore, generator());
        RdBugFixTask created = registry.createBugFixTask(new TicketSnapshot(
                "FS-ATOMIC-LOCK", "事件失败锁边界", "", List.of(), Instant.now()), "P1");
        RdBugFixTask searching = created.withState(
                RdTaskStatus.SEARCHING, "", "", "", "", "", "", System.currentTimeMillis());
        CoordinatedRdTaskStatePersistence persistence = new CoordinatedRdTaskStatePersistence(taskStore, eventStore);
        eventStore.blockAndFailNextSave();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> finalization = executor.submit(() -> persistence.saveWithEventCas(
                    searching,
                    new RdTaskStatusEvent("event-lock", created.taskId(), "SEARCHING", created.title(), "",
                            System.currentTimeMillis(), 0L, "SYSTEM"),
                    created.version(),
                    created.fencingToken(),
                    created.status()
            ));
            assertTrue(eventStore.awaitFailureSave());

            CountDownLatch competingWriterStarted = new CountDownLatch(1);
            Future<?> competingWriter = executor.submit(() -> {
                competingWriterStarted.countDown();
                taskStore.advanceStatusWithExpectedVersion(
                        created.taskId(),
                        created.version() + 1L,
                        created.fencingToken() + 1L,
                        RdTaskStatus.SEARCHING,
                        RdTaskStatus.EXECUTING,
                        "concurrent writer",
                        null,
                        null,
                        null
                );
            });
            assertTrue(competingWriterStarted.await(1, TimeUnit.SECONDS));
            assertTrue(storeLock.awaitCompetingWriterAttempt());
            assertFalse(storeLock.awaitCompetingWriterLock(), "writer must wait for the rollback boundary");

            eventStore.releaseFailure();
            assertThrows(java.util.concurrent.ExecutionException.class, finalization::get);
            assertThrows(java.util.concurrent.ExecutionException.class, competingWriter::get);
            assertEquals(created, taskStore.findBugFixTask(created.taskId()).orElseThrow());
        } finally {
            eventStore.releaseFailure();
            executor.shutdownNow();
        }
    }

    private SnowflakeIdGenerator generator() {
        AtomicInteger sequence = new AtomicInteger();
        return new SnowflakeIdGenerator(1, 1, () -> 1_784_100_000_000L + sequence.getAndIncrement());
    }

    private static final class RecordingStatePersistence implements RdTaskStatePersistence {

        private final CoordinatedRdTaskStatePersistence delegate;
        private int calls;

        private RecordingStatePersistence(RdTaskStore taskStore, RdTaskStatusEventStore eventStore) {
            this.delegate = new CoordinatedRdTaskStatePersistence(taskStore, eventStore);
        }

        @Override
        public RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event) {
            calls++;
            return delegate.saveWithEvent(task, event);
        }

        @Override
        public RdTask saveWithEventCas(
                RdTask task,
                RdTaskStatusEvent event,
                long expectedVersion,
                long expectedFencingToken,
                com.wish.rd.rag.runtime.model.RdTaskStatus expectedStatus
        ) {
            calls++;
            return delegate.saveWithEventCas(task, event, expectedVersion, expectedFencingToken, expectedStatus);
        }

        private int calls() {
            return calls;
        }
    }

    private static final class FailOnceEventStore implements RdTaskStatusEventStore {

        private final RdTaskStatusEventStore delegate;
        private boolean failNextSave;

        private FailOnceEventStore(RdTaskStatusEventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public RdTaskStatusEvent save(RdTaskStatusEvent event) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("injected timeline write failure");
            }
            return delegate.save(event);
        }

        @Override
        public List<RdTaskStatusEvent> listByTask(String taskId) {
            return delegate.listByTask(taskId);
        }

        @Override
        public int deleteByTask(String taskId) {
            return delegate.deleteByTask(taskId);
        }

        private void failNextSave() {
            failNextSave = true;
        }
    }

    private static final class BlockingFailOnceEventStore implements RdTaskStatusEventStore {

        private final RdTaskStatusEventStore delegate;
        private final Runnable onFailureWindow;
        private final CountDownLatch failureSaveEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFailure = new CountDownLatch(1);
        private boolean blockAndFail;

        private BlockingFailOnceEventStore(RdTaskStatusEventStore delegate, Runnable onFailureWindow) {
            this.delegate = delegate;
            this.onFailureWindow = onFailureWindow;
        }

        @Override
        public RdTaskStatusEvent save(RdTaskStatusEvent event) {
            if (blockAndFail) {
                blockAndFail = false;
                onFailureWindow.run();
                failureSaveEntered.countDown();
                try {
                    if (!releaseFailure.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to inject timeline write failure");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while injecting timeline write failure", interrupted);
                }
                throw new IllegalStateException("injected timeline write failure");
            }
            return delegate.save(event);
        }

        @Override
        public List<RdTaskStatusEvent> listByTask(String taskId) {
            return delegate.listByTask(taskId);
        }

        @Override
        public int deleteByTask(String taskId) {
            return delegate.deleteByTask(taskId);
        }

        private void blockAndFailNextSave() {
            blockAndFail = true;
        }

        private boolean awaitFailureSave() throws InterruptedException {
            return failureSaveEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseFailure() {
            releaseFailure.countDown();
        }
    }

    private static final class ObservingReentrantLockExecutor implements DistributedLockExecutor {

        private final ReentrantLock lock = new ReentrantLock();
        private final CountDownLatch competingWriterAttempted = new CountDownLatch(1);
        private final CountDownLatch competingWriterLocked = new CountDownLatch(1);
        private volatile Thread failureThread;

        @Override
        public <T> T execute(String lockName, Supplier<T> action) {
            boolean competingWriter = failureThread != null && failureThread != Thread.currentThread();
            if (competingWriter) {
                competingWriterAttempted.countDown();
            }
            lock.lock();
            try {
                if (competingWriter) {
                    competingWriterLocked.countDown();
                }
                return action.get();
            } finally {
                lock.unlock();
            }
        }

        private void markFailureWindow() {
            failureThread = Thread.currentThread();
        }

        private boolean awaitCompetingWriterAttempt() throws InterruptedException {
            return competingWriterAttempted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCompetingWriterLock() throws InterruptedException {
            return competingWriterLocked.await(150, TimeUnit.MILLISECONDS);
        }
    }
}
