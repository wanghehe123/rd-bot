package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RdExperienceEntryRow;
import com.wish.rd.bootstrap.persistence.mapper.RdExperienceEntryMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.WorkflowExperienceEntry;
import com.wish.rd.engine.agent.WorkflowExperienceStore;
import com.wish.rd.engine.agent.model.WorkflowExperienceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 工作流经验存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresWorkflowExperienceStore implements WorkflowExperienceStore {

    private static final Pattern SEARCH_TERM_PATTERN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9_]{2,}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        return searchReusableScoped(query, excludeTaskId, "", "", null, limit);
    }

    @Override
    public List<WorkflowExperienceEntry> searchReusableScoped(
            String query,
            String excludeTaskId,
            String projectId,
            String repositoryFingerprint,
            AgentRole role,
            int limit
    ) {
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
        String safeProjectId = safe(projectId);
        String safeRepositoryFingerprint = safe(repositoryFingerprint).toLowerCase(Locale.ROOT);
        if (!safeProjectId.isBlank()) {
            wrapper.eq("project_id", safeProjectId);
        } else if (!safeRepositoryFingerprint.isBlank()) {
            wrapper.eq("repository_fingerprint", safeRepositoryFingerprint);
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return mapper.selectList(wrapper)
                .stream()
                .filter(row -> relevanceScore(row, normalizedQuery) > 0)
                .map(this::toEntry)
                .filter(entry -> role == null
                        || entry.applicableRoles().isEmpty()
                        || entry.applicableRoles().contains(role))
                .sorted(Comparator
                        .comparingInt((WorkflowExperienceEntry entry) -> relevanceScore(entry, normalizedQuery)).reversed()
                        .thenComparing(Comparator.comparingLong(
                                WorkflowExperienceEntry::createdAtEpochMillis).reversed())
                        .thenComparing(WorkflowExperienceEntry::experienceId, Comparator.reverseOrder()))
                .limit(safeLimit)
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
        row.projectId = entry.projectId();
        row.repositoryFingerprint = entry.repositoryFingerprint();
        row.intentId = entry.intentId();
        row.tagsJson = json(entry.tags());
        row.sourceRevision = entry.sourceRevision();
        row.evidenceQuality = entry.evidenceQuality();
        row.applicableRolesJson = json(entry.applicableRoles().stream().map(Enum::name).toList());
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
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                row.projectId,
                row.repositoryFingerprint,
                row.intentId,
                stringList(row.tagsJson),
                row.sourceRevision,
                row.evidenceQuality == null
                        ? (Boolean.TRUE.equals(row.failure) ? 0.0d : 1.0d)
                        : row.evidenceQuality,
                roleList(row.applicableRolesJson).isEmpty()
                        ? List.of(role(row.role))
                        : roleList(row.applicableRolesJson)
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
        int score = haystack.contains(query) ? 2 : 0;
        for (String token : searchTerms(query)) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private int relevanceScore(WorkflowExperienceEntry entry, String normalizedQuery) {
        String haystack = (safe(entry.title()) + " "
                + safe(entry.summary()) + " "
                + safe(entry.contentJson()) + " "
                + String.join(" ", entry.tags())).toLowerCase(Locale.ROOT);
        return relevanceScore(haystack, normalizedQuery);
    }

    private int relevanceScore(String haystack, String normalizedQuery) {
        String query = safe(normalizedQuery);
        if (query.isBlank()) {
            return 0;
        }
        int score = haystack.contains(query) ? 2 : 0;
        for (String token : searchTerms(query)) {
            if (haystack.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> searchTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = SEARCH_TERM_PATTERN.matcher(safe(query).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN)) {
                int[] codePoints = token.codePoints().toArray();
                for (int index = 0; index + 1 < codePoints.length; index++) {
                    terms.add(new String(codePoints, index, 2));
                }
            } else if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("experience metadata is not JSON serializable", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            if (safe(value).isBlank()) {
                return List.of();
            }
            return OBJECT_MAPPER.readValue(value, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<AgentRole> roleList(String value) {
        return stringList(value).stream()
                .map(this::roleOrNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private AgentRole roleOrNull(String value) {
        try {
            return AgentRole.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
