package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RdProjectTaskTemplateRow;
import com.wish.rd.bootstrap.persistence.mapper.RdProjectTaskTemplateMapper;
import com.wish.rd.rag.project.template.RdProjectTaskTemplateStore;
import com.wish.rd.rag.project.template.model.RdProjectTaskTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** PostgreSQL project task template store. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRdProjectTaskTemplateStore implements RdProjectTaskTemplateStore {
    private final RdProjectTaskTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRdProjectTaskTemplateStore(RdProjectTaskTemplateMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public RdProjectTaskTemplate save(RdProjectTaskTemplate template) {
        RdProjectTaskTemplateRow row = new RdProjectTaskTemplateRow();
        row.projectId = PostgresPersistenceSupport.parseId(template.projectId());
        row.taskType = template.taskType();
        row.name = template.name();
        row.actualBehavior = template.actualBehavior();
        row.expectedBehavior = template.expectedBehavior();
        row.reproductionSteps = template.reproductionSteps();
        row.affectedScope = template.affectedScope();
        row.acceptanceCriteriaJson = write(template.acceptanceCriteria());
        row.requirementBody = template.requirementBody();
        row.expectedResult = template.expectedResult();
        row.createdAt = PostgresPersistenceSupport.toDateTime(template.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(template.updateTimeEpochMillis());
        mapper.upsert(row);
        return template;
    }

    public Optional<RdProjectTaskTemplate> find(String projectId, String taskType) {
        return Optional.ofNullable(mapper.find(PostgresPersistenceSupport.parseId(projectId), taskType))
                .map(this::toTemplate);
    }

    private RdProjectTaskTemplate toTemplate(RdProjectTaskTemplateRow row) {
        return new RdProjectTaskTemplate(
                PostgresPersistenceSupport.idString(row.projectId), row.taskType, row.name,
                row.actualBehavior, row.expectedBehavior, row.reproductionSteps, row.affectedScope,
                read(row.acceptanceCriteriaJson), row.requirementBody, row.expectedResult,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("failed to serialize task template", exception); }
    }

    private List<String> read(String json) {
        try { return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("failed to deserialize task template", exception); }
    }
}
