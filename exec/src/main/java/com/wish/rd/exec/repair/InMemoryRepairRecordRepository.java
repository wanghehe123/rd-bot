package com.wish.rd.exec.repair;

import com.wish.rd.framework.id.SnowflakeIdGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * 内存修复记录仓储，用于单测和无数据库本地启动。
 */
public final class InMemoryRepairRecordRepository implements RepairRecordRepository {

    private final SnowflakeIdGenerator idGenerator;
    private final LinkedHashMap<String, RepairRecord> records = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RepairRecordArtifact>> artifacts = new LinkedHashMap<>();

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
}
