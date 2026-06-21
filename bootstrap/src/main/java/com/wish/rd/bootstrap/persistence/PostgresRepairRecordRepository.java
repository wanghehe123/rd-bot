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
        RepairRecordRow row = recordMapper.selectById(PostgresPersistenceSupport.parseId(repairRecordId));
        if (row == null) {
            throw new IllegalArgumentException("repair record not found: " + repairRecordId);
        }
        row.status = status.name();
        row.ragSummary = ragSummary == null ? "" : ragSummary;
        row.updatedAt = OffsetDateTime.now();
        recordMapper.updateById(row);
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
}
