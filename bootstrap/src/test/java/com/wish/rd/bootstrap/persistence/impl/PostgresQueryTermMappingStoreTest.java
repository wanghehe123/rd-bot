package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.QueryTermMappingRow;
import com.wish.rd.bootstrap.persistence.mapper.QueryTermMappingMapper;
import com.wish.rd.rag.rewrite.model.ManagedQueryTermMapping;
import com.wish.rd.rag.rewrite.model.QueryTermMappingScope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresQueryTermMappingStoreTest {

    @Test
    void roundTripsGlobalAndProjectScopedRulesThroughPostgresRows() {
        QueryTermMappingMapper mapper = mock(QueryTermMappingMapper.class);
        PostgresQueryTermMappingStore store = new PostgresQueryTermMappingStore(mapper);
        ManagedQueryTermMapping global = new ManagedQueryTermMapping(
                "7482000000000000301", "", QueryTermMappingScope.GLOBAL,
                "下单", "POST /api/orders", 10, true, "global",
                1_783_648_800_000L, 1_783_648_801_000L
        );
        ManagedQueryTermMapping project = new ManagedQueryTermMapping(
                "7482000000000000302", "7482000000000000201", QueryTermMappingScope.PROJECT,
                "下单", "POST /p1/orders", 20, true, "project",
                1_783_648_800_000L, 1_783_648_801_000L
        );

        store.save(global);
        store.save(project);
        verify(mapper, times(2)).upsert(any(QueryTermMappingRow.class));

        QueryTermMappingRow globalRow = row(
                7_482_000_000_000_000_301L, null, "GLOBAL", "下单", "POST /api/orders", 10, true, "global"
        );
        QueryTermMappingRow projectRow = row(
                7_482_000_000_000_000_302L, 7_482_000_000_000_000_201L, "PROJECT", "下单", "POST /p1/orders", 20, true, "project"
        );
        when(mapper.findById(7_482_000_000_000_000_301L)).thenReturn(globalRow);
        when(mapper.list()).thenReturn(List.of(projectRow, globalRow));

        assertEquals(global, store.findById(global.id()).orElseThrow());
        assertEquals(List.of(project.id(), global.id()), store.list().stream().map(ManagedQueryTermMapping::id).toList());

        store.delete(project.id());
        verify(mapper).deleteById(eq(7_482_000_000_000_000_302L));
    }

    private static QueryTermMappingRow row(
            long id,
            Long projectId,
            String scope,
            String sourceTerm,
            String targetTerm,
            int priority,
            boolean enabled,
            String remark
    ) {
        QueryTermMappingRow row = new QueryTermMappingRow();
        row.id = id;
        row.projectId = projectId;
        row.scope = scope;
        row.sourceTerm = sourceTerm;
        row.targetTerm = targetTerm;
        row.priority = priority;
        row.enabled = enabled;
        row.remark = remark;
        row.createdAt = OffsetDateTime.parse("2026-07-10T02:00:00Z");
        row.updatedAt = OffsetDateTime.parse("2026-07-10T02:00:01Z");
        return row;
    }
}
