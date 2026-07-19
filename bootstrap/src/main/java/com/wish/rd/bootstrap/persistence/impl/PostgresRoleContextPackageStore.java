package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RdRoleContextPackageRow;
import com.wish.rd.bootstrap.persistence.mapper.RdRoleContextPackageMapper;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.context.RoleContextPackageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 角色上下文包存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRoleContextPackageStore implements RoleContextPackageStore {

    private static final TypeReference<List<RoleContextEvidence>> EVIDENCE_LIST =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final RdRoleContextPackageMapper mapper;
    private final ObjectMapper objectMapper;

    public PostgresRoleContextPackageStore(RdRoleContextPackageMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
    }

    @Override
    public RoleContextPackage save(RoleContextPackage contextPackage) {
        mapper.upsertContextPackage(toRow(contextPackage));
        return contextPackage;
    }

    @Override
    public Optional<RoleContextPackage> findById(String packageId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(packageId)))
                .map(this::toContextPackage);
    }

    @Override
    public List<RoleContextPackage> listByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<RdRoleContextPackageRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((RdRoleContextPackageRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toContextPackage)
                .toList();
    }

    @Override
    public List<RoleContextPackage> listByTaskAndRole(String taskId, String role) {
        String normalizedRole = role == null ? "" : role.strip().toUpperCase();
        return mapper.selectList(new QueryWrapper<RdRoleContextPackageRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId))
                        .eq("role", normalizedRole))
                .stream()
                .sorted(Comparator
                        .comparing((RdRoleContextPackageRow row) -> row.packageVersion)
                        .thenComparing(row -> row.id))
                .map(this::toContextPackage)
                .toList();
    }

    private RdRoleContextPackageRow toRow(RoleContextPackage contextPackage) {
        RdRoleContextPackageRow row = new RdRoleContextPackageRow();
        row.id = PostgresPersistenceSupport.parseId(contextPackage.packageId());
        row.taskId = PostgresPersistenceSupport.parseId(contextPackage.taskId());
        row.role = contextPackage.role();
        row.packageVersion = contextPackage.packageVersion();
        row.evidenceJson = writeJson(contextPackage.evidence());
        row.acceptanceJson = writeJson(contextPackage.acceptanceCriteria());
        row.riskHintsJson = writeJson(contextPackage.riskHints());
        row.contextBudgetJson = """
                {"maxChars":%d,"usedChars":%d}
                """.formatted(contextPackage.maxChars(), contextPackage.usedChars()).strip();
        row.omittedEvidenceJson = writeJson(contextPackage.omittedEvidenceIds());
        row.retrievalRunId = nullableId(contextPackage.retrievalRunId());
        row.contentHash = PostgresPersistenceSupport.checksum(
                row.evidenceJson + row.acceptanceJson + row.riskHintsJson + row.contextBudgetJson
                        + row.omittedEvidenceJson + contextPackage.retrievalRunId()
        );
        row.createdAt = PostgresPersistenceSupport.toDateTime(contextPackage.createdAtEpochMillis());
        return row;
    }

    private RoleContextPackage toContextPackage(RdRoleContextPackageRow row) {
        JsonNode budget = readTree(row.contextBudgetJson);
        return new RoleContextPackage(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                row.role,
                row.packageVersion == null ? 1 : row.packageVersion,
                readJson(row.evidenceJson, EVIDENCE_LIST),
                readJson(row.acceptanceJson, STRING_LIST),
                readJson(row.riskHintsJson, STRING_LIST),
                budget.path("maxChars").asInt(0),
                budget.path("usedChars").asInt(0),
                readJson(row.omittedEvidenceJson, STRING_LIST),
                PostgresPersistenceSupport.idString(row.retrievalRunId),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private Long nullableId(String value) {
        String safeValue = value == null ? "" : value.strip();
        return safeValue.isBlank() ? null : PostgresPersistenceSupport.parseId(safeValue);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize role context package", exception);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse role context budget json", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, typeReference);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse role context package json", exception);
        }
    }
}
