package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.QaValidationProfileRow;
import com.wish.rd.bootstrap.persistence.mapper.QaValidationProfileMapper;
import com.wish.rd.rag.qa.QaValidationProfileStore;
import com.wish.rd.rag.qa.model.QaValidationProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** PostgreSQL-backed task and project QA profile store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresQaValidationProfileStore implements QaValidationProfileStore {

    private final QaValidationProfileMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresQaValidationProfileStore(QaValidationProfileMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public QaValidationProfile save(QaValidationProfile profile) {
        QaValidationProfileRow row = new QaValidationProfileRow();
        row.scopeType = profile.scopeType();
        row.scopeId = PostgresPersistenceSupport.parseId(profile.scopeId());
        row.mode = profile.mode();
        row.baseUrl = profile.baseUrl();
        row.startCommand = profile.startCommand();
        row.healthPath = profile.healthPath();
        row.allowedHostsJson = write(profile.allowedHosts());
        row.regressionCommandsJson = write(profile.regressionCommands());
        row.createdAt = PostgresPersistenceSupport.toDateTime(profile.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(profile.updateTimeEpochMillis());
        mapper.upsert(row);
        return profile;
    }

    @Override
    public Optional<QaValidationProfile> find(String scopeType, String scopeId) {
        return Optional.ofNullable(mapper.find(
                        scopeType == null ? "" : scopeType.strip().toUpperCase(java.util.Locale.ROOT),
                        PostgresPersistenceSupport.parseId(scopeId)
                ))
                .map(this::toProfile);
    }

    private QaValidationProfile toProfile(QaValidationProfileRow row) {
        return new QaValidationProfile(
                row.scopeType,
                PostgresPersistenceSupport.idString(row.scopeId),
                row.mode,
                row.baseUrl,
                row.startCommand,
                row.healthPath,
                read(row.allowedHostsJson),
                read(row.regressionCommandsJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize QA validation profile", exception);
        }
    }

    private List<String> read(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("failed to deserialize QA validation profile", exception);
        }
    }
}
