package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.RdTaskRow;
import com.wish.rd.bootstrap.persistence.mapper.RdTaskMapper;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresRdTaskStoreCasTest {

    @Test
    void shouldAdvanceWhenVersionAndStatusMatch() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        AtomicLong storedVersion = new AtomicLong(3L);
        when(mapper.advanceStatusWithExpectedVersion(
                eq(42L),
                eq(3L),
                eq("EXECUTING"),
                eq("COMPLETED"),
                any(),
                any(),
                any(),
                any(),
                any(OffsetDateTime.class)
        )).thenAnswer(invocation -> {
            long expected = invocation.getArgument(1);
            if (storedVersion.get() != expected) {
                return 0;
            }
            storedVersion.incrementAndGet();
            return 1;
        });

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertDoesNotThrow(() -> store.advanceStatusWithExpectedVersion(
                "42",
                3L,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.COMPLETED,
                null,
                "{\"status\":\"SUCCESS\"}",
                "https://example.com/pr/1"
        ));
    }

    @Test
    void shouldRejectStaleWriter() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        when(mapper.advanceStatusWithExpectedVersion(
                anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(OffsetDateTime.class)
        )).thenReturn(0);

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertThrows(IllegalStateException.class, () -> store.advanceStatusWithExpectedVersion(
                "99",
                1L,
                RdTaskStatus.EXECUTING,
                RdTaskStatus.FAILED_NEEDS_HUMAN,
                "stale"
        ));
    }

    @Test
    void shouldReadStoredVersionForFencingCallers() {
        RdTaskMapper mapper = mock(RdTaskMapper.class);
        RdTaskRow row = new RdTaskRow();
        row.id = 7L;
        row.version = 4L;
        when(mapper.selectById(7L)).thenReturn(row);

        PostgresRdTaskStore store = new PostgresRdTaskStore(mapper);

        assertEquals(Optional.of(4L), store.findVersion("7"));
        assertEquals(Optional.empty(), store.findVersion("8"));
    }
}
