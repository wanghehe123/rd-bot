package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryModeRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryModeMapper;
import com.wish.rd.rag.project.memory.ProjectMemoryModeStore;
import com.wish.rd.rag.project.memory.model.ProjectMemoryCaptureMode;
import com.wish.rd.rag.project.memory.model.ProjectMemoryModeConfig;
import com.wish.rd.rag.project.memory.model.ProjectMemoryReadMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** PostgreSQL per-project capture/read policy store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresProjectMemoryModeStore implements ProjectMemoryModeStore {

    private final ProjectMemoryModeMapper mapper;

    public PostgresProjectMemoryModeStore(ProjectMemoryModeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectMemoryModeConfig getConfig(String projectId) {
        long parsedProjectId = PostgresPersistenceSupport.parseId(projectId);
        ProjectMemoryModeRow row = mapper.findByProjectId(parsedProjectId);
        if (row == null) {
            return ProjectMemoryModeConfig.defaults(PostgresPersistenceSupport.idString(parsedProjectId));
        }
        return toConfig(row);
    }

    @Override
    public void saveConfig(ProjectMemoryModeConfig config) {
        ProjectMemoryModeRow row = new ProjectMemoryModeRow();
        row.projectId = PostgresPersistenceSupport.parseId(config.projectId());
        row.captureMode = config.captureMode().name();
        row.readMode = config.readMode().name();
        row.createdAt = PostgresPersistenceSupport.toDateTime(
                config.createTimeEpochMillis() > 0L
                        ? config.createTimeEpochMillis()
                        : System.currentTimeMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(
                config.updateTimeEpochMillis() > 0L
                        ? config.updateTimeEpochMillis()
                        : System.currentTimeMillis());
        mapper.upsert(row);
    }

    private static ProjectMemoryModeConfig toConfig(ProjectMemoryModeRow row) {
        return new ProjectMemoryModeConfig(
                PostgresPersistenceSupport.idString(row.projectId),
                ProjectMemoryCaptureMode.valueOf(row.captureMode),
                ProjectMemoryReadMode.valueOf(row.readMode),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt));
    }
}
