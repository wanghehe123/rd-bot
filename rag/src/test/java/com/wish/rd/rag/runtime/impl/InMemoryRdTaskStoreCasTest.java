package com.wish.rd.rag.runtime.impl;

import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-CAS-01: stale status writers are rejected by version CAS.
 */
class InMemoryRdTaskStoreCasTest {

    @Test
    void shouldRejectStaleVersionOnStatusAdvance() {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RdRequirementTask task = RdRequirementTask.created(
                "1001",
                new CreateRequirementTaskCommand(
                        "CAS", "P1", "https://github.com/example/waimai.git",
                        "example", "waimai", "main", "ok", List.of("a"), false
                ),
                1L
        );
        store.saveRequirementTask(task);
        assertEquals(0L, store.findVersion("1001").orElseThrow());

        store.advanceStatusWithExpectedVersion(
                "1001", 0L, RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, ""
        );
        assertEquals(1L, store.findVersion("1001").orElseThrow());
        assertEquals(RdTaskStatus.MATERIAL_COLLECTING, store.findRequirementTask("1001").orElseThrow().status());

        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                "1001", 0L, RdTaskStatus.CREATED, RdTaskStatus.CANCELLED, "stale"
        ));
        assertEquals(RdTaskStatus.MATERIAL_COLLECTING, store.findRequirementTask("1001").orElseThrow().status());
        assertEquals(1L, store.findVersion("1001").orElseThrow());
    }

    @Test
    void shouldAllowOnlyOneWinnerWhenCancelRacesComplete() throws Exception {
        InMemoryRdTaskStore store = new InMemoryRdTaskStore();
        RdRequirementTask task = RdRequirementTask.created(
                "1002",
                new CreateRequirementTaskCommand(
                        "race", "P1", "https://github.com/example/waimai.git",
                        "example", "waimai", "main", "ok", List.of("a"), false
                ),
                1L
        );
        store.saveRequirementTask(task);
        store.advanceStatusWithExpectedVersion(
                "1002", 0L, RdTaskStatus.CREATED, RdTaskStatus.EXECUTING, ""
        );

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger losses = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = pool.submit(() -> {
                start.await();
                try {
                    store.advanceStatusWithExpectedVersion(
                            "1002", 1L, RdTaskStatus.EXECUTING, RdTaskStatus.CANCELLED, "cancel"
                    );
                    wins.incrementAndGet();
                } catch (IllegalStateException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            Future<?> complete = pool.submit(() -> {
                start.await();
                try {
                    store.advanceStatusWithExpectedVersion(
                            "1002", 1L, RdTaskStatus.EXECUTING, RdTaskStatus.COMPLETED, ""
                    );
                    wins.incrementAndGet();
                } catch (IllegalStateException exception) {
                    losses.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            cancel.get();
            complete.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, wins.get());
        assertEquals(1, losses.get());
        RdTaskStatus terminal = store.findRequirementTask("1002").orElseThrow().status();
        assertTrue(terminal == RdTaskStatus.CANCELLED || terminal == RdTaskStatus.COMPLETED);
        assertEquals(2L, store.findVersion("1002").orElseThrow());
    }
}
