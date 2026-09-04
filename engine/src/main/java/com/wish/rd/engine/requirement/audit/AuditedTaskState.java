package com.wish.rd.engine.requirement.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host-owned explicit audited state for one requirement task.
 *
 * @param taskId owning task id
 * @param stateVersion 1-based revision
 * @param stateHash SHA-256 of the canonical JSON excluding this hash, or blank before hashing
 * @param contractRef frozen acceptance-criteria identity
 * @param records bounded audited records
 * @param lastAuditRunId most recent audit run, or blank before the first run
 */
public record AuditedTaskState(
        String taskId,
        long stateVersion,
        String stateHash,
        AuditedContractRef contractRef,
        List<AuditedRecord> records,
        String lastAuditRunId
) {
    public static final int MAX_RECORDS = 256;

    public AuditedTaskState {
        taskId = requireText(taskId, "taskId");
        if (stateVersion < 1L) {
            throw new IllegalArgumentException("stateVersion must be >= 1");
        }
        stateHash = stateHash == null ? "" : stateHash.strip();
        if (contractRef == null) {
            throw new IllegalArgumentException("contractRef must not be null");
        }
        records = records == null ? List.of() : List.copyOf(records);
        if (records.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("records must not contain null");
        }
        if (records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("records must not exceed " + MAX_RECORDS);
        }
        Map<String, AuditedRecord> unique = new LinkedHashMap<>();
        for (AuditedRecord record : records) {
            AuditedRecord previous = unique.put(record.id(), record);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate audited record id: " + record.id());
            }
        }
        lastAuditRunId = lastAuditRunId == null ? "" : lastAuditRunId.strip();
    }

    /**
     * Looks up a record by id.
     *
     * @param recordId record id
     * @return the matching record
     */
    public AuditedRecord record(String recordId) {
        return find(recordId).orElseThrow(() -> new IllegalArgumentException("unknown audited record: " + recordId));
    }

    /**
     * Finds a record by id.
     *
     * @param recordId record id
     * @return the matching record when present
     */
    public Optional<AuditedRecord> find(String recordId) {
        String id = recordId == null ? "" : recordId.strip();
        return records.stream().filter(record -> record.id().equals(id)).findFirst();
    }

    /**
     * Returns a copy with a new version, hash, records, and last audit run.
     *
     * @param nextVersion next state version
     * @param nextHash canonical hash
     * @param nextRecords replacement records
     * @param auditRunId last audit run
     * @return updated state
     */
    public AuditedTaskState withRevision(
            long nextVersion,
            String nextHash,
            List<AuditedRecord> nextRecords,
            String auditRunId
    ) {
        return new AuditedTaskState(taskId, nextVersion, nextHash, contractRef, nextRecords, auditRunId);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
