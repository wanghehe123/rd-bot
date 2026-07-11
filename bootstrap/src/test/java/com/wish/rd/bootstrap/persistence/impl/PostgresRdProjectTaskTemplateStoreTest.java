package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RdProjectTaskTemplateRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectTaskTemplateMapper;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRdProjectTaskTemplateStoreTest {
    @Test
    void shouldRoundTripAcceptanceCriteriaJson() {
        RdProjectTaskTemplateMapper mapper = mock(RdProjectTaskTemplateMapper.class);
        PostgresRdProjectTaskTemplateStore store = new PostgresRdProjectTaskTemplateStore(mapper, new ObjectMapper());
        RdProjectTaskTemplate template = new RdProjectTaskTemplate(
                "7482000000000000701", "BUG_FIX", "Web Bug", "actual", "expected", "steps", "scope",
                List.of("HTTP 200", "页面成功"), "", "", 1_783_200_000_000L, 1_783_200_001_000L);

        store.save(template);
        verify(mapper).upsert(any(RdProjectTaskTemplateRow.class));

        RdProjectTaskTemplateRow row = new RdProjectTaskTemplateRow();
        row.projectId = 7_482_000_000_000_000_701L;
        row.taskType = "BUG_FIX";
        row.name = "Web Bug"; row.actualBehavior = "actual"; row.expectedBehavior = "expected";
        row.reproductionSteps = "steps"; row.affectedScope = "scope";
        row.acceptanceCriteriaJson = "[\"HTTP 200\",\"页面成功\"]";
        row.requirementBody = ""; row.expectedResult = "";
        row.createdAt = OffsetDateTime.now(); row.updatedAt = OffsetDateTime.now();
        when(mapper.find(row.projectId, "BUG_FIX")).thenReturn(row);

        assertEquals(template.acceptanceCriteria(),
                store.find("7482000000000000701", "BUG_FIX").orElseThrow().acceptanceCriteria());
    }
}
