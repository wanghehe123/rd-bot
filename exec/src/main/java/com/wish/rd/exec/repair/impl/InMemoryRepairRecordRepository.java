package com.wish.rd.exec.repair.impl;

import com.wish.rd.exec.repair.RepairRecordRepository;

import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.wish.rd.exec.repair.model.CreateRepairAssetCommand;
import com.wish.rd.exec.repair.model.CreateRepairRecordArtifactCommand;
import com.wish.rd.exec.repair.model.CreateRepairRecordCommand;
import com.wish.rd.exec.repair.model.RepairAsset;
import com.wish.rd.exec.repair.model.RepairRecord;
import com.wish.rd.exec.repair.model.RepairRecordArtifact;
import com.wish.rd.exec.repair.model.RepairRecordPage;
import com.wish.rd.exec.repair.model.RepairRecordQuery;
import com.wish.rd.exec.repair.model.RepairRecordStatus;

/**
 * 内存修复记录仓储，用于单测和无数据库本地启动。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "memory", matchIfMissing = true)
public final class InMemoryRepairRecordRepository implements RepairRecordRepository {

    private final SnowflakeIdGenerator idGenerator;
    private final LinkedHashMap<String, RepairRecord> records = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RepairRecordArtifact>> artifacts = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RepairAsset>> assets = new LinkedHashMap<>();

    public InMemoryRepairRecordRepository(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public static InMemoryRepairRecordRepository inMemory() {
        return new InMemoryRepairRecordRepository(SnowflakeIdGenerator.defaultGenerator());
    }

    @Override
    public synchronized RepairRecord create(CreateRepairRecordCommand command) {
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
        records.put(record.id(), record);
        return record;
    }

    @Override
    public synchronized RepairRecord updateStatus(String repairRecordId, RepairRecordStatus status, String ragSummary) {
        RepairRecord record = findById(repairRecordId)
                .orElseThrow(() -> new IllegalArgumentException("repair record not found: " + repairRecordId));
        RepairRecord updated = record.withStatus(status, ragSummary, System.currentTimeMillis());
        records.put(updated.id(), updated);
        return updated;
    }

    @Override
    public synchronized RepairRecord updateExecutorJson(String repairRecordId, String executorJson) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withExecutorJson(executorJson, System.currentTimeMillis()));
    }

    @Override
    public synchronized RepairRecord updateDockerJson(String repairRecordId, String dockerJson) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withDockerJson(dockerJson, System.currentTimeMillis()));
    }

    @Override
    public synchronized RepairRecord updateGithubJson(String repairRecordId, String githubJson) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withGithubJson(githubJson, System.currentTimeMillis()));
    }

    @Override
    public synchronized RepairRecord updateTestJson(String repairRecordId, String testJson) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withTestJson(testJson, System.currentTimeMillis()));
    }

    @Override
    public synchronized RepairRecord updateRiskJson(String repairRecordId, String riskJson) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withRiskJson(riskJson, System.currentTimeMillis()));
    }

    @Override
    public synchronized RepairRecord updateErrorMessage(String repairRecordId, String errorMessage) {
        RepairRecord record = existingRecord(repairRecordId);
        return save(record.withErrorMessage(errorMessage, System.currentTimeMillis()));
    }

    @Override
    public synchronized Optional<RepairRecord> findById(String repairRecordId) {
        return Optional.ofNullable(records.get(repairRecordId));
    }

    @Override
    public synchronized Optional<RepairRecord> findByTicketId(String ticketId) {
        String normalizedTicketId = ticketId == null ? "" : ticketId;
        return records.values().stream()
                .filter(record -> record.ticketId().equals(normalizedTicketId))
                .findFirst();
    }

    @Override
    public synchronized RepairRecordPage query(RepairRecordQuery query) {
        RepairRecordQuery safe = query == null ? RepairRecordQuery.empty() : query;
        // 按创建时间倒序过滤
        List<RepairRecord> filtered = records.values().stream()
                .filter(record -> matches(record, safe))
                .sorted((left, right) -> Long.compare(right.createdAtEpochMillis(), left.createdAtEpochMillis()))
                .toList();
        long total = filtered.size();
        int fromIndex = Math.min((safe.page() - 1) * safe.pageSize(), filtered.size());
        int toIndex = Math.min(fromIndex + safe.pageSize(), filtered.size());
        List<RepairRecord> page = filtered.subList(fromIndex, toIndex);
        return new RepairRecordPage(List.copyOf(page), safe.page(), safe.pageSize(), total);
    }

    private boolean matches(RepairRecord record, RepairRecordQuery query) {
        if (!query.ticketId().isBlank() && !record.ticketId().equals(query.ticketId())) {
            return false;
        }
        if (!query.status().isBlank() && !record.status().name().equals(query.status())) {
            return false;
        }
        if (!query.priority().isBlank()) {
            String priority = record.extensionJson().getOrDefault("priority", "");
            if (!priority.equals(query.priority())) {
                return false;
            }
        }
        if (query.createdFrom() > 0 && record.createdAtEpochMillis() < query.createdFrom()) {
            return false;
        }
        if (query.createdTo() > 0 && record.createdAtEpochMillis() > query.createdTo()) {
            return false;
        }
        return true;
    }

    @Override
    public synchronized RepairRecordArtifact addArtifact(CreateRepairRecordArtifactCommand command) {
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
        artifacts.computeIfAbsent(command.repairRecordId(), ignored -> new ArrayList<>()).add(artifact);
        return artifact;
    }

    @Override
    public synchronized List<RepairRecordArtifact> listArtifacts(String repairRecordId) {
        return List.copyOf(artifacts.getOrDefault(repairRecordId, List.of()));
    }

    @Override
    public synchronized RepairAsset addAsset(CreateRepairAssetCommand command) {
        findById(command.repairRecordId())
                .orElseThrow(() -> new IllegalArgumentException("repair record not found: " + command.repairRecordId()));
        requireSourceArtifactBelongsToRecord(command.repairRecordId(), command.sourceArtifactId());
        RepairAsset asset = new RepairAsset(
                idGenerator.nextIdString(),
                command.repairRecordId(),
                command.assetType(),
                command.title(),
                command.summary(),
                command.contentJson(),
                command.sourceArtifactId(),
                command.reusable(),
                System.currentTimeMillis()
        );
        assets.computeIfAbsent(command.repairRecordId(), ignored -> new ArrayList<>()).add(asset);
        return asset;
    }

    private void requireSourceArtifactBelongsToRecord(String repairRecordId, String sourceArtifactId) {
        if (sourceArtifactId == null || sourceArtifactId.isBlank()) {
            return;
        }
        boolean found = artifacts.getOrDefault(repairRecordId, List.of()).stream()
                .anyMatch(artifact -> artifact.id().equals(sourceArtifactId));
        if (!found) {
            throw new IllegalArgumentException("source artifact must belong to repair record: " + sourceArtifactId);
        }
    }

    @Override
    public synchronized List<RepairAsset> listAssets(String repairRecordId) {
        return List.copyOf(assets.getOrDefault(repairRecordId, List.of()));
    }

    private RepairRecord existingRecord(String repairRecordId) {
        return findById(repairRecordId)
                .orElseThrow(() -> new IllegalArgumentException("repair record not found: " + repairRecordId));
    }

    private RepairRecord save(RepairRecord record) {
        records.put(record.id(), record);
        return record;
    }
}
