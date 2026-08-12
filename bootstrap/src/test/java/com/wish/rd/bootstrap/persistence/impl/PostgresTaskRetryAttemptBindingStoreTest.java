package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.TaskRetryAttemptBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryAttemptBindingMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies typed binding replay is immutable and checkpoint-scoped. */
class PostgresTaskRetryAttemptBindingStoreTest {

    @Test
    void persistsTypedTargetAndOrdersCheckpointBindingsByOrdinalThenId() {
        TaskRetryAttemptBindingMapper mapper = mock(TaskRetryAttemptBindingMapper.class);
        Map<Long, TaskRetryAttemptBindingRow> rows = new LinkedHashMap<>();
        when(mapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            TaskRetryAttemptBindingRow row = invocation.getArgument(0);
            if (rows.values().stream().anyMatch(old -> old.id.equals(row.id))) {
                return 0;
            }
            rows.put(row.id, copy(row));
            return 1;
        });
        when(mapper.findById(anyLong())).thenAnswer(invocation -> copy(rows.get(invocation.getArgument(0))));
        when(mapper.listByCheckpoint(anyLong())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> row.checkpointId.equals(invocation.getArgument(0)))
                .sorted(java.util.Comparator.comparing((TaskRetryAttemptBindingRow row) -> row.ordinal)
                        .thenComparing(row -> row.id))
                .map(PostgresTaskRetryAttemptBindingStoreTest::copy).toList());

        PostgresTaskRetryAttemptBindingStore store = new PostgresTaskRetryAttemptBindingStore(mapper);
        store.save(binding("12", "9", "101", 2));
        store.save(binding("11", "9", "100", 1));

        assertEquals(List.of("11", "12"), store.listByCheckpoint("9").stream()
                .map(TaskRetryAttemptBinding::bindingId).toList());
        assertEquals("100", store.findById("11").orElseThrow().attemptId());
    }

    @Test
    void immutableReplayMismatchFailsClosed() {
        TaskRetryAttemptBindingMapper mapper = mock(TaskRetryAttemptBindingMapper.class);
        TaskRetryAttemptBindingRow stored = row(binding("11", "9", "100", 1));
        when(mapper.insertIfAbsent(any())).thenReturn(0);
        when(mapper.findById(11L)).thenReturn(stored);
        PostgresTaskRetryAttemptBindingStore store = new PostgresTaskRetryAttemptBindingStore(mapper);

        assertThrows(IllegalStateException.class, () -> store.save(binding("11", "9", "101", 1)));
    }

    @Test
    void primaryAndChildLookupsDoNotEscapeCheckpoint() {
        TaskRetryAttemptBindingMapper mapper = mock(TaskRetryAttemptBindingMapper.class);
        TaskRetryAttemptBindingRow primary = row(binding("11", "9", "100", 1));
        when(mapper.findPrimary(9L, "AGENT_STAGE", "CODING_AGENT")).thenReturn(List.of(primary));
        when(mapper.findChild(anyLong(), anyLong(), anyString(), anyInt())).thenReturn(List.of());
        PostgresTaskRetryAttemptBindingStore store = new PostgresTaskRetryAttemptBindingStore(mapper);

        assertEquals("11", store.findPrimary("9", TaskRetryAttemptKind.AGENT_STAGE, AgentRole.CODING_AGENT)
                .orElseThrow().bindingId());
        assertEquals(true, store.findChild("9", "11", TaskRetryAttemptKind.RETRIEVAL, 0).isEmpty());
    }

    private static TaskRetryAttemptBinding binding(String id, String checkpointId, String attemptId, int ordinal) {
        return new TaskRetryAttemptBinding(id, checkpointId, TaskRetryAttemptKind.AGENT_STAGE,
                AgentRole.CODING_AGENT, attemptId, "", 1, ordinal);
    }

    private static TaskRetryAttemptBindingRow row(TaskRetryAttemptBinding binding) {
        TaskRetryAttemptBindingRow row = new TaskRetryAttemptBindingRow();
        row.id = Long.valueOf(binding.bindingId()); row.checkpointId = Long.valueOf(binding.checkpointId());
        row.bindingKind = binding.kind().name(); row.role = binding.role().name();
        row.stageRunId = Long.valueOf(binding.attemptId()); row.attemptNo = binding.attemptNo();
        row.ordinal = binding.ordinal(); row.createdAt = OffsetDateTime.now(); return row;
    }

    private static TaskRetryAttemptBindingRow copy(TaskRetryAttemptBindingRow source) {
        if (source == null) return null;
        TaskRetryAttemptBindingRow row = new TaskRetryAttemptBindingRow();
        row.id=source.id; row.checkpointId=source.checkpointId; row.bindingKind=source.bindingKind; row.role=source.role;
        row.stageRunId=source.stageRunId; row.retrievalRunId=source.retrievalRunId; row.aiReviewRunId=source.aiReviewRunId;
        row.parentBindingId=source.parentBindingId; row.attemptNo=source.attemptNo; row.ordinal=source.ordinal; row.createdAt=source.createdAt;
        return row;
    }
}
