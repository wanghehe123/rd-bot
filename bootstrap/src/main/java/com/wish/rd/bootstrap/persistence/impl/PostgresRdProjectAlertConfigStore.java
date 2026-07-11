package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdProjectAlertConfigRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectAlertConfigMapper;
import com.wish.rd.rag.project.alert.RdProjectAlertConfigStore;
import com.wish.rd.rag.project.alert.model.RdAlertRecipient;
import com.wish.rd.rag.project.alert.model.RdProjectAlertConfig;
import com.wish.rd.rag.project.alert.model.RdProjectAlertEventType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL project alert configuration store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdProjectAlertConfigStore implements RdProjectAlertConfigStore {

    private final RdProjectAlertConfigMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRdProjectAlertConfigStore(RdProjectAlertConfigMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public RdProjectAlertConfig save(RdProjectAlertConfig config) {
        RdProjectAlertConfigRow row = new RdProjectAlertConfigRow();
        row.projectId = PostgresPersistenceSupport.parseId(config.projectId());
        row.enabled = config.enabled();
        row.recipientsJson = write(config.recipients());
        row.eventTypesJson = write(config.eventTypes().stream().map(Enum::name).sorted().toList());
        row.budgetThresholdCny = config.budgetThresholdCny();
        row.failureThreshold = config.failureThreshold();
        row.createdAt = PostgresPersistenceSupport.toDateTime(config.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(config.updateTimeEpochMillis());
        mapper.upsert(row);
        return config;
    }

    @Override
    public Optional<RdProjectAlertConfig> findByProjectId(String projectId) {
        return Optional.ofNullable(mapper.findByProjectId(PostgresPersistenceSupport.parseId(projectId)))
                .map(this::toConfig);
    }

    private RdProjectAlertConfig toConfig(RdProjectAlertConfigRow row) {
        List<RdAlertRecipient> recipients = read(row.recipientsJson, new TypeReference<>() { });
        List<String> eventNames = read(row.eventTypesJson, new TypeReference<>() { });
        Set<RdProjectAlertEventType> eventTypes = new LinkedHashSet<>();
        for (String eventName : eventNames) {
            eventTypes.add(RdProjectAlertEventType.valueOf(eventName));
        }
        return new RdProjectAlertConfig(
                PostgresPersistenceSupport.idString(row.projectId),
                Boolean.TRUE.equals(row.enabled),
                recipients,
                eventTypes,
                row.budgetThresholdCny,
                row.failureThreshold == null ? 1 : row.failureThreshold,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize project alert config", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to deserialize project alert config", exception);
        }
    }
}
