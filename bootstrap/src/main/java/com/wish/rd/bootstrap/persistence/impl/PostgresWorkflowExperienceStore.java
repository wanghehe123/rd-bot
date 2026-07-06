package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdExperienceEntryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdExperienceEntryMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * PostgreSQL 工作流经验存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresWorkflowExperienceStore implements WorkflowExperienceStore {

    private final RdExperienceEntryMapper mapper;

    public PostgresWorkflowExperienceStore(RdExperienceEntryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkflowExperienceEntry save(WorkflowExperienceEntry entry) {
        mapper.upsertExperienceEntry(toRow(entry));
        return entry;
    }

    @Override
    public List<WorkflowExperienceEntry> listByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<RdExperienceEntryRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((RdExperienceEntryRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toEntry)
                .toList();
    }

    @Override
    public List<WorkflowExperienceEntry> searchReusable(String query, String excludeTaskId, int limit) {
        int safeLimit = Math.max(limit, 0);
        if (safeLimit == 0) {
            return List.of();
        }
        QueryWrapper<RdExperienceEntryRow> wrapper = new QueryWrapper<RdExperienceEntryRow>()
                .eq("reusable", true)
                .eq("failure", false)
                .eq("redacted", true);
        String safeExcludeTaskId = excludeTaskId == null ? "" : excludeTaskId.strip();
        if (!safeExcludeTaskId.isBlank()) {
            wrapper.ne("task_id", PostgresPersistenceSupport.parseId(safeExcludeTaskId));
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return mapper.selectList(wrapper)
                .stream()
                .sorted(Comparator
                        .comparingInt((RdExperienceEntryRow row) -> relevanceScore(row, normalizedQuery)).reversed()
                        .thenComparing((RdExperienceEntryRow row) -> row.createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.id, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .map(this::toEntry)
                .toList();
    }

    private RdExperienceEntryRow toRow(WorkflowExperienceEntry entry) {
        RdExperienceEntryRow row = new RdExperienceEntryRow();
        row.id = PostgresPersistenceSupport.parseId(entry.experienceId());
        row.taskId = PostgresPersistenceSupport.parseId(entry.taskId());
        row.stageRunId = nullableId(entry.stageRunId());
        row.sourceArtifactId = nullableId(entry.sourceArtifactId());
        row.role = entry.role().name();
        row.experienceType = entry.experienceType().name();
        row.title = entry.title();
        row.summary = entry.summary();
        row.contentJson = entry.contentJson();
        row.contentHash = PostgresPersistenceSupport.checksum(entry.contentJson());
        row.reusable = entry.reusable();
        row.failure = entry.failure();
        row.redacted = entry.redacted();
        row.ingestionTaskId = null;
        row.createdAt = PostgresPersistenceSupport.toDateTime(entry.createdAtEpochMillis());
        return row;
    }

    private WorkflowExperienceEntry toEntry(RdExperienceEntryRow row) {
        return new WorkflowExperienceEntry(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.stageRunId),
                PostgresPersistenceSupport.idString(row.sourceArtifactId),
                role(row.role),
                WorkflowExperienceType.valueOf(row.experienceType),
                row.title,
                row.summary,
                row.contentJson,
                Boolean.TRUE.equals(row.reusable),
                Boolean.TRUE.equals(row.failure),
                Boolean.TRUE.equals(row.redacted),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private Long nullableId(String value) {
        String safeValue = value == null ? "" : value.strip();
        return safeValue.isBlank() ? null : PostgresPersistenceSupport.parseId(safeValue);
    }

    private AgentRole role(String value) {
        String safeValue = value == null ? "" : value.strip();
        return safeValue.isBlank() ? AgentRole.REQUIREMENT_REVIEWER : AgentRole.valueOf(safeValue);
    }

    private int relevanceScore(RdExperienceEntryRow row, String normalizedQuery) {
        String haystack = (safe(row.title) + " "
                + safe(row.summary) + " "
                + safe(row.contentJson)).toLowerCase(Locale.ROOT);
        String query = safe(normalizedQuery);
        if (query.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : query.split("\\s+")) {
            if (!token.isBlank() && haystack.contains(token)) {
                score++;
            }
        }
        if (score == 0 && haystack.contains(query)) {
            return 1;
        }
        return score;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
