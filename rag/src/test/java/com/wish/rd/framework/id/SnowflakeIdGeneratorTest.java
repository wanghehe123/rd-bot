package com.wish.rd.framework.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void shouldGenerateUniqueIdsConcurrently() throws Exception {
        SnowflakeIdGenerator generator = SnowflakeIdGenerator.defaultGenerator();
        Set<Long> ids = ConcurrentHashMap.newKeySet();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2_000; i++) {
                executor.submit(() -> ids.add(generator.nextId()));
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(2_000, ids.size());
    }

    @Test
    void shouldRejectClockRollback() {
        AtomicLong now = new AtomicLong(1_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, now::get);

        generator.nextId();
        now.set(999L);

        IllegalStateException exception = assertThrows(IllegalStateException.class, generator::nextId);
        assertTrue(exception.getMessage().contains("Clock moved backwards"));
    }

    @Test
    void shouldWaitForNextMillisWhenSequenceOverflows() {
        AtomicLong now = new AtomicLong(2_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1, () -> now.getAndUpdate(value -> {
            if (value < 6_096L) {
                return value + 1;
            }
            return value;
        }));

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4_097; i++) {
            ids.add(generator.nextId());
        }

        assertEquals(4_097, ids.size());
    }
}
