package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.ModelProviderCredentialRow;
import com.wish.rd.bootstrap.persistence.mapper.ModelProviderCredentialMapper;
import com.wish.rd.rag.project.agent.ModelProviderCredentialStore;
import com.wish.rd.rag.project.agent.model.ModelProviderCredential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** PostgreSQL-backed provider secret store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresModelProviderCredentialStore implements ModelProviderCredentialStore {

    private final ModelProviderCredentialMapper mapper;

    public PostgresModelProviderCredentialStore(ModelProviderCredentialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String providerId, String secret, long updatedAtEpochMillis) {
        ModelProviderCredentialRow row = new ModelProviderCredentialRow();
        row.providerId = providerId;
        row.secret = secret;
        row.updatedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(updatedAtEpochMillis), ZoneOffset.UTC);
        mapper.upsert(row);
    }

    @Override
    public void delete(String providerId) {
        mapper.delete(providerId);
    }

    @Override
    public Optional<ModelProviderCredential> find(String providerId) {
        ModelProviderCredentialRow row = mapper.find(providerId);
        if (row == null) {
            return Optional.empty();
        }
        long updatedAt = row.updatedAt == null ? 0L : row.updatedAt.toInstant().toEpochMilli();
        return Optional.of(new ModelProviderCredential(row.providerId, row.secret, updatedAt));
    }
}
