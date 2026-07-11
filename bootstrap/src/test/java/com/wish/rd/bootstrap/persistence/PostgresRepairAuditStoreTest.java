package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairAuditEventRow;
import com.wish.rd.bootstrap.persistence.impl.PostgresRepairAuditStore;
import com.wish.rd.bootstrap.persistence.mapper.RepairAuditEventMapper;
import com.wish.rd.engine.audit.model.RepairAuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRepairAuditStoreTest {

    @Test
    void shouldQueryAuditEventsByTaskIdInTheDatabase() {
        RepairAuditEventMapper mapper = mock(RepairAuditEventMapper.class);
        RepairAuditEventRow row = new RepairAuditEventRow();
        row.repairRecordId = "repair-1";
        row.taskId = "task-1";
        row.ticketId = "ticket-1";
        row.eventType = "EXECUTION_FINISHED";
        row.externalSystem = "Docker";
        row.summary = "finished";
        row.metadataJson = "{}";
        row.createdAt = PostgresPersistenceSupport.toDateTime(1_783_000_000_000L);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        PostgresRepairAuditStore store = new PostgresRepairAuditStore(mapper, new ObjectMapper());

        List<RepairAuditEvent> result = store.eventsByTaskId("task-1");

        assertEquals(1, result.size());
        assertEquals("task-1", result.getFirst().taskId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<RepairAuditEventRow>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("task_id"));
    }
}
