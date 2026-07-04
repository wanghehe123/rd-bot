package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdExperienceEntryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdExperienceEntryMapper;
import com.wish.rd.engine.agent.AgentRole;
import com.wish.rd.engine.agent.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresWorkflowExperienceStoreTest {

    private final RdExperienceEntryMapper mapper = mock(RdExperienceEntryMapper.class);
    private final PostgresWorkflowExperienceStore store = new PostgresWorkflowExperienceStore(mapper);

    @Test
    void shouldSaveAndListExperienceEntriesByTask() {
        WorkflowExperienceEntry entry = new WorkflowExperienceEntry(
                "7478000000000003001",
                "7478000000000000000",
                "7478000000000002001",
                "7478000000000001001",
                AgentRole.QA_AGENT,
                WorkflowExperienceType.QA_REPORT,
                "QA 验收",
                "真实测试通过",
                "{\"status\":\"PASSED\"}",
                true,
                false,
                true,
                1_783_000_000_000L
        );
        when(mapper.selectList(any())).thenReturn(List.of(row(entry)));

        store.save(entry);

        assertEquals(List.of(entry), store.listByTask(entry.taskId()));
        verify(mapper).upsertExperienceEntry(any(RdExperienceEntryRow.class));
    }

    @Test
    void shouldSearchReusableExperiencesForLaterTaskContext() {
        WorkflowExperienceEntry relevant = new WorkflowExperienceEntry(
                "7478000000000003002",
                "7478000000000000001",
                "7478000000000002002",
                "7478000000000001002",
                AgentRole.QA_AGENT,
                WorkflowExperienceType.DELIVERY_REPORT,
                "需求交付报告",
                "订单催单功能验收通过",
                "{\"summary\":\"订单催单功能验收通过\"}",
                true,
                false,
                true,
                1_783_000_000_100L
        );
        WorkflowExperienceEntry lessRelevant = new WorkflowExperienceEntry(
                "7478000000000003003",
                "7478000000000000002",
                "7478000000000002003",
                "7478000000000001003",
                AgentRole.REQUIREMENT_REVIEWER,
                WorkflowExperienceType.REQUIREMENT_REVIEW,
                "需求评审",
                "通用需求评审经验",
                "{\"summary\":\"通用需求评审经验\"}",
                true,
                false,
                true,
                1_783_000_000_200L
        );
        when(mapper.selectList(any())).thenReturn(List.of(row(lessRelevant), row(relevant)));

        List<WorkflowExperienceEntry> results = store.searchReusable(
                "订单催单",
                "7478000000000000999",
                1
        );

        assertEquals(List.of(relevant), results);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<QueryWrapper<RdExperienceEntryRow>> captor =
                forClass((Class<QueryWrapper<RdExperienceEntryRow>>) (Class<?>) QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("reusable"));
        assertTrue(sqlSegment.contains("failure"));
        assertTrue(sqlSegment.contains("redacted"));
        assertTrue(sqlSegment.contains("task_id <>"));
    }

    private RdExperienceEntryRow row(WorkflowExperienceEntry entry) {
        RdExperienceEntryRow row = new RdExperienceEntryRow();
        row.id = Long.parseLong(entry.experienceId());
        row.taskId = Long.parseLong(entry.taskId());
        row.stageRunId = Long.parseLong(entry.stageRunId());
        row.sourceArtifactId = Long.parseLong(entry.sourceArtifactId());
        row.role = entry.role().name();
        row.experienceType = entry.experienceType().name();
        row.title = entry.title();
        row.summary = entry.summary();
        row.contentJson = entry.contentJson();
        row.contentHash = "sha256:test";
        row.reusable = entry.reusable();
        row.failure = entry.failure();
        row.redacted = entry.redacted();
        row.ingestionTaskId = null;
        row.createdAt = PostgresPersistenceSupport.toDateTime(entry.createdAtEpochMillis());
        return row;
    }
}
