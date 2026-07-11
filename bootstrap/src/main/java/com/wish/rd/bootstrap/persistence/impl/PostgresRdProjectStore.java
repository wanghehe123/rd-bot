package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdProjectRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectMapper;
import com.wish.rd.rag.project.model.RdProject;
import com.wish.rd.rag.project.RdProjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL RD 项目存储适配器。
 *
 * <p>供项目管理领域服务持久化系统可交付项目及其仓库配置。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdProjectStore implements RdProjectStore {

    private final RdProjectMapper mapper;

    public PostgresRdProjectStore(RdProjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RdProject save(RdProject project) {
        RdProjectRow row = toRow(project);
        if (mapper.selectById(row.id) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return project;
    }

    @Override
    public Optional<RdProject> findById(String projectId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(projectId)))
                .map(this::toProject);
    }

    @Override
    public Optional<RdProject> findByKey(String projectKey) {
        String safeProjectKey = projectKey == null ? "" : projectKey.strip();
        if (safeProjectKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<RdProjectRow>()
                        .eq("project_key", safeProjectKey)
                        .eq("deleted", false)
                        .last("LIMIT 1")))
                .map(this::toProject);
    }

    @Override
    public List<RdProject> list() {
        return mapper.selectList(null)
                .stream()
                .sorted(Comparator.comparing((RdProjectRow row) -> row.updatedAt).reversed()
                        .thenComparing(row -> row.id))
                .map(this::toProject)
                .toList();
    }

    private RdProjectRow toRow(RdProject project) {
        RdProjectRow row = new RdProjectRow();
        row.id = PostgresPersistenceSupport.parseId(project.projectId());
        row.projectKey = project.projectKey();
        row.name = project.name();
        row.description = project.description();
        row.repositoryUrl = project.repositoryUrl();
        row.repoOwner = project.repoOwner();
        row.repoName = project.repoName();
        row.defaultBranch = project.defaultBranch();
        row.knowledgeBaseId = project.knowledgeBaseId().isBlank()
                ? null
                : PostgresPersistenceSupport.parseId(project.knowledgeBaseId());
        row.enabled = project.enabled();
        row.deleted = project.deleted();
        row.createdAt = PostgresPersistenceSupport.toDateTime(project.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(project.updateTimeEpochMillis());
        return row;
    }

    private RdProject toProject(RdProjectRow row) {
        return new RdProject(
                PostgresPersistenceSupport.idString(row.id),
                row.projectKey,
                row.name,
                row.description,
                row.repositoryUrl,
                row.repoOwner,
                row.repoName,
                row.defaultBranch,
                row.enabled != null && row.enabled,
                row.deleted != null && row.deleted,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                row.knowledgeBaseId == null ? "" : PostgresPersistenceSupport.idString(row.knowledgeBaseId)
        );
    }
}
