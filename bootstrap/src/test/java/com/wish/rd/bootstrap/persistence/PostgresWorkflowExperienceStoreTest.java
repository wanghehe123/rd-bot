package com.wish.rd.bootstrap.persistence;

import com.wish.rd.bootstrap.persistence.impl.PostgresWorkflowExperienceStore;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdExperienceEntryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdExperienceEntryMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void shouldDropZeroScoreExperienceInsteadOfReturningNewestNoise() {
        WorkflowExperienceEntry unrelated = new WorkflowExperienceEntry(
                "7478000000000003010", "7478000000000000010", "7478000000000002010",
                "7478000000000001010", AgentRole.QA_AGENT, WorkflowExperienceType.QA_REPORT,
                "优惠券活动", "满减优惠券验收通过", "{\"summary\":\"营销活动\"}",
                true, false, true, 1_783_000_001_000L);
        when(mapper.selectList(any())).thenReturn(List.of(row(unrelated)));

        List<WorkflowExperienceEntry> results = store.searchReusable(
                "商品库存批量调整", "7478000000000000999", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldPersistAndHardFilterScopedExperienceMetadata() {
        WorkflowExperienceEntry scoped = new WorkflowExperienceEntry(
                "7478000000000003020", "7478000000000000020", "7478000000000002020",
                "7478000000000001020", AgentRole.QA_AGENT, WorkflowExperienceType.QA_REPORT,
                "商品验收", "商品保存接口验收通过", "{\"status\":\"PASSED\"}",
                true, false, true, 1_783_000_002_000L,
                "waimai-project", "example/waimai", "product-admin",
                List.of("商品", "QA_AGENT"), "0123456789abcdef", 0.95d,
                List.of(AgentRole.QA_AGENT));
        when(mapper.selectList(any())).thenReturn(List.of(row(scoped)));

        store.save(scoped);
        List<WorkflowExperienceEntry> results = store.searchReusableScoped(
                "商品验收", "7478000000000000999", "waimai-project", "example/waimai",
                AgentRole.QA_AGENT, 5);

        assertEquals(List.of(scoped), results);
        org.mockito.ArgumentCaptor<RdExperienceEntryRow> rowCaptor = forClass(RdExperienceEntryRow.class);
        verify(mapper).upsertExperienceEntry(rowCaptor.capture());
        assertEquals("waimai-project", rowCaptor.getValue().projectId);
        assertEquals("example/waimai", rowCaptor.getValue().repositoryFingerprint);
        assertEquals("[\"商品\",\"QA_AGENT\"]", rowCaptor.getValue().tagsJson);
        assertNotNull(rowCaptor.getValue().applicableRolesJson);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<QueryWrapper<RdExperienceEntryRow>> queryCaptor =
                forClass((Class<QueryWrapper<RdExperienceEntryRow>>) (Class<?>) QueryWrapper.class);
        verify(mapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("project_id"));
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
        row.projectId = entry.projectId();
        row.repositoryFingerprint = entry.repositoryFingerprint();
        row.intentId = entry.intentId();
        try {
            row.tagsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(entry.tags());
            row.applicableRolesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    entry.applicableRoles().stream().map(Enum::name).toList());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
        row.sourceRevision = entry.sourceRevision();
        row.evidenceQuality = entry.evidenceQuality();
        row.ingestionTaskId = null;
        row.createdAt = PostgresPersistenceSupport.toDateTime(entry.createdAtEpochMillis());
        return row;
    }
}
