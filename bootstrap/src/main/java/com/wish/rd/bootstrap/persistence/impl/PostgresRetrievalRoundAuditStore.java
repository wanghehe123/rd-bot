package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.RetrievalRoundAuditRow;
import com.wish.rd.bootstrap.persistence.mapper.RetrievalRoundAuditMapper;
import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAudit;
import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAuditStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** PostgreSQL source of truth for immutable iterative retrieval round audits. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRetrievalRoundAuditStore implements RetrievalRoundAuditStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final RetrievalRoundAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRetrievalRoundAuditStore(RetrievalRoundAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
    }

    @Override
    public void append(RetrievalRoundAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit must not be null");
        }
        mapper.insertAudit(toRow(audit));
    }

    @Override
    public List<RetrievalRoundAudit> listByRun(String runId) {
        return mapper.listByRun(PostgresPersistenceSupport.parseId(runId)).stream().map(this::toAudit).toList();
    }

    @Override
    public List<RetrievalRoundAudit> listByTask(String taskId) {
        return mapper.listByTask(PostgresPersistenceSupport.parseId(taskId)).stream().map(this::toAudit).toList();
    }

    private RetrievalRoundAuditRow toRow(RetrievalRoundAudit audit) {
        RetrievalRoundAuditRow row = new RetrievalRoundAuditRow();
        row.id = PostgresPersistenceSupport.parseId(audit.auditId());
        row.taskId = PostgresPersistenceSupport.parseId(audit.taskId());
        row.runId = PostgresPersistenceSupport.parseId(audit.runId());
        row.roundNo = audit.roundNo();
        row.queryPreview = audit.query();
        row.candidateEvidenceIdsJson = writeJson(audit.candidateEvidenceIds());
        row.selectedEvidenceIdsJson = writeJson(audit.selectedEvidenceIds());
        row.missingEvidenceTypesJson = writeJson(audit.missingEvidenceTypes());
        row.informationGain = audit.informationGain();
        row.cumulativeTokens = audit.cumulativeTokens();
        row.elapsedMs = audit.elapsedMillis();
        row.stopReason = audit.stopReason();
        row.scopeFingerprint = audit.scopeFingerprint();
        row.recordedAt = PostgresPersistenceSupport.toDateTime(audit.recordedAtEpochMillis());
        return row;
    }

    private RetrievalRoundAudit toAudit(RetrievalRoundAuditRow row) {
        return new RetrievalRoundAudit(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.runId),
                row.roundNo == null ? 1 : row.roundNo,
                row.queryPreview,
                readJson(row.candidateEvidenceIdsJson),
                readJson(row.selectedEvidenceIdsJson),
                readJson(row.missingEvidenceTypesJson),
                row.informationGain == null ? 0 : row.informationGain,
                row.cumulativeTokens == null ? 0L : row.cumulativeTokens,
                row.elapsedMs == null ? 0L : row.elapsedMs,
                row.stopReason,
                row.scopeFingerprint,
                PostgresPersistenceSupport.toEpochMillis(row.recordedAt)
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize retrieval round audit", exception);
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid retrieval round audit JSON", exception);
        }
    }
}
