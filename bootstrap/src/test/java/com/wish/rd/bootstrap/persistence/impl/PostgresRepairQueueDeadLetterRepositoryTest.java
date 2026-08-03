package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairQueueDeadLetterRow;
import com.wish.rd.bootstrap.persistence.mapper.RepairQueueDeadLetterMapper;
import com.wish.rd.engine.ticket.model.RepairQueueDeadLetter;
import com.wish.rd.engine.ticket.model.RepairTicketMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRepairQueueDeadLetterRepositoryTest {

    @Test
    void shouldAtomicallyDedupeConcurrentRegularDeadLetterSavesByDeterministicIdentity() {
        AtomicReference<RepairQueueDeadLetterRow> winningRow = new AtomicReference<>();
        List<String> insertIds = new ArrayList<>();
        AtomicInteger legacyInsertCalls = new AtomicInteger();
        AtomicInteger scanThenInsertQueries = new AtomicInteger();
        AtomicInteger conflictLookups = new AtomicInteger();
        RepairQueueDeadLetterMapper mapper = mock(RepairQueueDeadLetterMapper.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if ("insertDeadLetterIfAbsent".equals(methodName)) {
                RepairQueueDeadLetterRow candidate = invocation.getArgument(0);
                insertIds.add(candidate.id);
                return winningRow.compareAndSet(null, candidate) ? 1 : 0;
            }
            if ("insertDeadLetter".equals(methodName)) {
                legacyInsertCalls.incrementAndGet();
                insertIds.add(((RepairQueueDeadLetterRow) invocation.getArgument(0)).id);
                return null;
            }
            if ("selectById".equals(methodName)) {
                conflictLookups.incrementAndGet();
                return winningRow.get();
            }
            if ("selectList".equals(methodName)) {
                scanThenInsertQueries.incrementAndGet();
                return List.of();
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        PostgresRepairQueueDeadLetterRepository repository = new PostgresRepairQueueDeadLetterRepository(
                mapper,
                new ObjectMapper()
        );
        RepairTicketMessage firstDelivery = new RepairTicketMessage(
                "FS-REGULAR-99", "P1", "trace-first", 4, "feishu", "evt-regular-99",
                "helpdesk.ticket.created_v1", Instant.parse("2026-08-02T00:00:00Z")
        );
        RepairTicketMessage recoveredDelivery = new RepairTicketMessage(
                "FS-REGULAR-99", "P2", "trace-recovered", 5, "feishu", "evt-regular-99",
                "helpdesk.ticket.created_v1", Instant.parse("2026-08-02T00:01:00Z")
        );

        RepairQueueDeadLetter first = repository.save(firstDelivery, "terminal false");
        RepairQueueDeadLetter second = repository.save(recoveredDelivery, "terminal false recovered");

        assertAll(
                () -> assertDoesNotThrow(() -> RepairQueueDeadLetterMapper.class.getMethod(
                        "insertDeadLetterIfAbsent", RepairQueueDeadLetterRow.class
                )),
                () -> assertEquals(first.id(), second.id()),
                () -> assertTrue(first.id().startsWith("redis-stream-valid-")),
                () -> assertTrue(first.id().length() <= 128),
                () -> assertEquals(2, insertIds.size(), "winner and loser must both use the atomic insert path"),
                () -> assertEquals(1, Set.copyOf(insertIds).size(), "both saves must target one deterministic primary key"),
                () -> assertEquals(0, legacyInsertCalls.get(), "regular save must not use fresh Snowflake inserts"),
                () -> assertEquals(0, scanThenInsertQueries.get(), "regular save must not pre-query for dedupe"),
                () -> assertEquals(1, conflictLookups.get(), "only the losing insert resolves the persisted row")
        );
    }

    @Test
    void shouldUseOneDeterministicPrimaryIdForRepeatedMalformedRedisRecordSaves() {
        RepairQueueDeadLetterMapper mapper = mock(RepairQueueDeadLetterMapper.class);
        AtomicReference<RepairQueueDeadLetterRow> persisted = new AtomicReference<>();
        doAnswer(invocation -> {
            RepairQueueDeadLetterRow candidate = invocation.getArgument(0);
            if (persisted.compareAndSet(null, candidate)) {
                return 1;
            }
            return 0;
        }).when(mapper).insertDeadLetterIfAbsent(any(RepairQueueDeadLetterRow.class));
        when(mapper.selectById(any(String.class))).thenAnswer(invocation -> persisted.get());
        PostgresRepairQueueDeadLetterRepository repository = new PostgresRepairQueueDeadLetterRepository(
                mapper,
                new ObjectMapper()
        );

        RepairQueueDeadLetter first = repository.saveMalformed(
                "1780000000099-0",
                Map.of("ticketId", "FS-POISON-99", "source", "feishu"),
                "redis stream entry is malformed"
        );
        RepairQueueDeadLetter second = repository.saveMalformed(
                "1780000000099-0",
                Map.of("ticketId", "FS-POISON-99", "source", "feishu"),
                "redis stream entry is malformed"
        );

        assertAll(
                () -> assertEquals(first.id(), second.id()),
                () -> assertTrue(first.id().length() <= 128),
                () -> verify(mapper, times(2)).insertDeadLetterIfAbsent(any(RepairQueueDeadLetterRow.class)),
                () -> verify(mapper).selectById(first.id()),
                () -> verify(mapper, never()).selectList(any())
        );
    }

    @Test
    void shouldPersistMalformedRecordByOriginalRedisRecordIdWithoutSchemaChanges() throws Exception {
        RepairQueueDeadLetterMapper mapper = mock(RepairQueueDeadLetterMapper.class);
        when(mapper.insertDeadLetterIfAbsent(any(RepairQueueDeadLetterRow.class))).thenReturn(1);
        PostgresRepairQueueDeadLetterRepository repository = new PostgresRepairQueueDeadLetterRepository(
                mapper,
                new ObjectMapper()
        );

        RepairQueueDeadLetter saved = repository.saveMalformed(
                "1780000000010-0",
                Map.of(
                        "ticketId", "FS-POISON-1",
                        "attempt", "not-a-number",
                        "source", "feishu",
                        "title", "must not be persisted"
                ),
                "redis stream entry is malformed"
        );

        ArgumentCaptor<RepairQueueDeadLetterRow> row = ArgumentCaptor.forClass(RepairQueueDeadLetterRow.class);
        verify(mapper).insertDeadLetterIfAbsent(row.capture());
        Map<String, String> persisted = new ObjectMapper().readValue(
                row.getValue().messageJson,
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                }
        );
        assertAll(
                () -> assertEquals("1780000000010-0", persisted.get("redisRecordId")),
                () -> assertEquals("FS-POISON-1", saved.ticketId()),
                () -> assertEquals(0, saved.originalAttempt()),
                () -> assertEquals("FS-POISON-1", persisted.get("ticketId")),
                () -> assertEquals("feishu", persisted.get("source")),
                () -> assertEquals(null, persisted.get("title"))
        );
    }
}
