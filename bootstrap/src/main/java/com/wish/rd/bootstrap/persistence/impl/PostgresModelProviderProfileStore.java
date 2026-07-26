package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.ModelProviderProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.ModelProviderProfileMapper;
import com.wish.rd.rag.project.agent.ModelProviderProfileStore;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

/** PostgreSQL-backed provider routing metadata store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresModelProviderProfileStore implements ModelProviderProfileStore {

    private final ModelProviderProfileMapper mapper;

    public PostgresModelProviderProfileStore(ModelProviderProfileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ModelProviderProfile save(ModelProviderProfile profile) {
        mapper.upsert(toRow(profile));
        return profile;
    }

    @Override
    public Optional<ModelProviderProfile> find(String providerId) {
        return Optional.ofNullable(mapper.find(providerId)).map(PostgresModelProviderProfileStore::toProfile);
    }

    @Override
    public List<ModelProviderProfile> list() {
        return mapper.list().stream()
                .map(PostgresModelProviderProfileStore::toProfile)
                .toList();
    }

    private static ModelProviderProfileRow toRow(ModelProviderProfile profile) {
        ModelProviderProfileRow row = new ModelProviderProfileRow();
        row.providerId = profile.providerId();
        row.displayName = profile.displayName();
        row.protocol = profile.protocol().name();
        row.baseUrl = profile.baseUrl();
        row.modelId = profile.modelId();
        row.credentialEnvironmentVariable = profile.credentialEnvironmentVariable();
        row.authHeader = profile.authHeader();
        row.enabled = profile.enabled();
        row.version = profile.version();
        return row;
    }

    private static ModelProviderProfile toProfile(ModelProviderProfileRow row) {
        return new ModelProviderProfile(
                row.providerId,
                row.displayName,
                ModelProviderProtocol.parse(row.protocol),
                row.baseUrl,
                row.modelId,
                row.credentialEnvironmentVariable,
                Boolean.TRUE.equals(row.authHeader),
                Boolean.TRUE.equals(row.enabled),
                row.version == null ? 1L : row.version
        );
    }
}
