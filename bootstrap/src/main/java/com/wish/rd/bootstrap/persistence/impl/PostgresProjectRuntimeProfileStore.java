package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProjectRuntimeProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectRuntimeProfileMapper;
import com.wish.rd.rag.project.runtime.ProjectRuntimeProfileStore;
import com.wish.rd.rag.project.runtime.model.ProjectRuntimeProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed store for verified project execution runtimes. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresProjectRuntimeProfileStore implements ProjectRuntimeProfileStore {

    private final ProjectRuntimeProfileMapper mapper;

    public PostgresProjectRuntimeProfileStore(ProjectRuntimeProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ProjectRuntimeProfile save(ProjectRuntimeProfile profile) {
        mapper.upsert(toRow(profile));
        return profile;
    }

    @Override
    public Optional<ProjectRuntimeProfile> find(String projectId, String role) {
        return Optional.ofNullable(mapper.find(
                PostgresPersistenceSupport.parseId(projectId), role
        )).map(this::toProfile);
    }

    @Override
    public List<ProjectRuntimeProfile> list(String projectId) {
        return mapper.list(PostgresPersistenceSupport.parseId(projectId)).stream()
                .map(this::toProfile)
                .toList();
    }

    @Override
    public boolean delete(String projectId, String role) {
        return mapper.delete(PostgresPersistenceSupport.parseId(projectId), role) > 0;
    }

    private static ProjectRuntimeProfileRow toRow(ProjectRuntimeProfile profile) {
        ProjectRuntimeProfileRow row = new ProjectRuntimeProfileRow();
        row.projectId = PostgresPersistenceSupport.parseId(profile.projectId());
        row.role = profile.role();
        row.agentType = profile.agentType();
        row.image = profile.image();
        row.dockerfileUri = profile.dockerfileArtifactUri();
        row.dockerfileSha256 = profile.dockerfileSha256();
        row.dockerfileName = profile.dockerfileName();
        row.validationStatus = profile.validationStatus();
        row.validationSummary = profile.validationSummary();
        row.createdAt = PostgresPersistenceSupport.toDateTime(profile.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(profile.updateTimeEpochMillis());
        return row;
    }

    private ProjectRuntimeProfile toProfile(ProjectRuntimeProfileRow row) {
        return new ProjectRuntimeProfile(
                PostgresPersistenceSupport.idString(row.projectId),
                row.role,
                row.agentType,
                row.image,
                row.dockerfileUri,
                row.dockerfileSha256,
                row.dockerfileName,
                row.validationStatus,
                row.validationSummary,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }
}
