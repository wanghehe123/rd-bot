package com.wish.rd.bootstrap.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.entity.RepairRecordArtifactRow;
import com.wish.rd.bootstrap.persistence.entity.RepairRecordRow;
import com.wish.rd.bootstrap.persistence.mapper.RepairRecordArtifactMapper;
import com.wish.rd.bootstrap.persistence.mapper.RepairRecordMapper;
import com.wish.rd.exec.repair.CreateRepairRecordArtifactCommand;
import com.wish.rd.exec.repair.CreateRepairRecordCommand;
import com.wish.rd.exec.repair.RepairRecord;
import com.wish.rd.exec.repair.RepairRecordArtifact;
import com.wish.rd.exec.repair.RepairRecordJson;
import com.wish.rd.exec.repair.RepairRecordPage;
import com.wish.rd.exec.repair.RepairRecordQuery;
import com.wish.rd.exec.repair.RepairRecordRepository;
import com.wish.rd.exec.repair.RepairRecordStatus;
import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 修复记录仓储。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresRepairRecordRepository implements RepairRecordRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final RepairRecordMapper recordMapper;
    private final RepairRecordArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PostgresRepairRecordRepository(
            RepairRecordMapper recordMapper,
            RepairRecordArtifactMapper artifactMapper,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.recordMapper = recordMapper;
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public RepairRecord create(CreateRepairRecordCommand command) {
        long now = System.currentTimeMillis();
        RepairRecord record = new RepairRecord(
                idGenerator.nextIdString(),
                command.ticketId(),
                command.ticketUrl(),
                command.title(),
                RepairRecordStatus.CREATED,
                "",
                command.extensionJson(),
                now,
                now
        );
        recordMapper.insertRecord(toRow(record));
        return record;
    }

    @Override
    public RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.status = status.name();
        row.ragSummary = ragSummary == null ? "" : ragSummary;
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateStatus(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateExecutorJson(String repairRecordId, String executorJson) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.executorJson = normalizeJson(executorJson);
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateExecutorJson(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateDockerJson(String repairRecordId, String dockerJson) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.dockerJson = normalizeJson(dockerJson);
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateDockerJson(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateGithubJson(String repairRecordId, String githubJson) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.githubJson = normalizeJson(githubJson);
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateGithubJson(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateTestJson(String repairRecordId, String testJson) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.testJson = normalizeJson(testJson);
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateTestJson(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateRiskJson(String repairRecordId, String riskJson) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.riskJson = normalizeJson(riskJson);
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateRiskJson(row);
        return toRecord(row);
    }

    @Override
    public RepairRecord updateErrorMessage(String repairRecordId, String errorMessage) {
        RepairRecordRow row = existingRow(repairRecordId);
        row.errorMessage = errorMessage == null ? "" : errorMessage;
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateErrorMessage(row);
        return toRecord(row);
    }

    @Override
    public Optional<RepairRecord> findById(String repairRecordId) {
        return Optional.ofNullable(recordMapper.selectById(PostgresPersistenceSupport.parseId(repairRecordId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<RepairRecord> findByTicketId(String ticketId) {
        return recordMapper.selectList(new QueryWrapper<RepairRecordRow>()
                        .eq("ticket_id", ticketId == null ? "" : ticketId))
                .stream()
                .max(Comparator
                        .comparing((RepairRecordRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toRecord);
    }

    @Override
    public RepairRecordPage query(RepairRecordQuery query) {
        RepairRecordQuery safe = query == null ? RepairRecordQuery.empty() : query;
        QueryWrapper<RepairRecordRow> wrapper = new QueryWrapper<>();
        if (!safe.ticketId().isBlank()) {
            wrapper.eq("ticket_id", safe.ticketId());
        }
        if (!safe.status().isBlank()) {
            wrapper.eq("status", safe.status());
        }
        if (safe.createdFrom() > 0) {
            wrapper.ge("created_at", PostgresPersistenceSupport.toDateTime(safe.createdFrom()));
        }
        if (safe.createdTo() > 0) {
            wrapper.le("created_at", PostgresPersistenceSupport.toDateTime(safe.createdTo()));
        }
        wrapper.orderByDesc("created_at", "id");
        // priority 存于 extension_json，先全量过滤后再内存分页/筛选
        List<RepairRecord> filtered = recordMapper.selectList(wrapper).stream()
                .map(this::toRecord)
                .filter(record -> safe.priority().isBlank()
                        ? true
                        : safe.priority().equals(record.extensionJson().getOrDefault("priority", "")))
                .toList();
        long total = filtered.size();
        int fromIndex = Math.min((safe.page() - 1) * safe.pageSize(), filtered.size());
        int toIndex = Math.min(fromIndex + safe.pageSize(), filtered.size());
        return new RepairRecordPage(List.copyOf(filtered.subList(fromIndex, toIndex)), safe.page(), safe.pageSize(), total);
    }

    @Override
    public RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command) {
        findById(command.repairRecordId())
                .orElseThrow(() -> new IllegalArgumentException("repair record not found: " + command.repairRecordId()));
        RepairRecordArtifact artifact = new RepairRecordArtifact(
                idGenerator.nextIdString(),
                command.repairRecordId(),
                command.artifactType(),
                command.artifactUri(),
                command.summary(),
                System.currentTimeMillis()
        );
        artifactMapper.insertArtifact(toRow(artifact));
        return artifact;
    }

    @Override
    public List<RepairRecordArtifact> listArtifacts(String repairRecordId) {
        return artifactMapper.selectList(new QueryWrapper<RepairRecordArtifactRow>()
                        .eq("repair_record_id", PostgresPersistenceSupport.parseId(repairRecordId)))
                .stream()
                .sorted(Comparator
                        .comparing((RepairRecordArtifactRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toArtifact)
                .toList();
    }

    private RepairRecordRow toRow(RepairRecord record) {
        RepairRecordRow row = new RepairRecordRow();
        row.id = PostgresPersistenceSupport.parseId(record.id());
        row.ticketId = record.ticketId();
        row.ticketUrl = record.ticketUrl();
        row.title = record.title();
        row.status = record.status().name();
        row.ragSummary = record.ragSummary();
        row.executorJson = "{}";
        row.dockerJson = "{}";
        row.githubJson = "{}";
        row.testJson = "{}";
        row.riskJson = "{}";
        row.errorMessage = "";
        row.extensionJson = toJson(record.extensionJson());
        row.createdAt = PostgresPersistenceSupport.toDateTime(record.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(record.updatedAtEpochMillis());
        return row;
    }

    private RepairRecordArtifactRow toRow(RepairRecordArtifact artifact) {
        RepairRecordArtifactRow row = new RepairRecordArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.id());
        row.repairRecordId = PostgresPersistenceSupport.parseId(artifact.repairRecordId());
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.summary = artifact.summary();
        row.contentHash = "";
        row.extensionJson = "{}";
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private RepairRecord toRecord(RepairRecordRow row) {
        return new RepairRecord(
                PostgresPersistenceSupport.idString(row.id),
                row.ticketId,
                row.ticketUrl,
                row.title,
                RepairRecordStatus.valueOf(row.status),
                row.ragSummary,
                row.executorJson,
                row.dockerJson,
                row.githubJson,
                row.testJson,
                row.riskJson,
                row.errorMessage,
                fromJson(row.extensionJson),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private RepairRecordArtifact toArtifact(RepairRecordArtifactRow row) {
        return new RepairRecordArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.repairRecordId),
                row.artifactType,
                row.artifactUri,
                row.summary,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize repair extension json", exception);
        }
    }

    private Map<String, String> fromJson(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, STRING_MAP_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse repair extension json", exception);
        }
    }

    private RepairRecordRow existingRow(String repairRecordId) {
        RepairRecordRow row = recordMapper.selectById(PostgresPersistenceSupport.parseId(repairRecordId));
        if (row == null) {
            throw new IllegalArgumentException("repair record not found: " + repairRecordId);
        }
        return row;
    }

    private String normalizeJson(String value) {
        return RepairRecordJson.normalizeMetadataJson(value);
    }
}
