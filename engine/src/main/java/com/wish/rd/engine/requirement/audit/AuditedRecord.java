package com.wish.rd.engine.requirement.audit;

import java.util.List;

/**
 * One requirement, gate, artifact, or untrusted claim inside {@link AuditedTaskState}.
 *
 * @param id stable record id such as {@code AC-001} or {@code GATE-BUILD}
 * @param kind record family
 * @param blocking whether completion requires this record to be {@code COMPLETED}
 * @param text human-readable criterion, locator, or claim statement
 * @param status current audit status
 * @param evidenceRefs Host evidence backing a {@code COMPLETED} record
 * @param sourceStageRunId originating stage run for FACT/claim records
 * @param blockedReason required when {@code status=BLOCKED}
 */
public record AuditedRecord(
        String id,
        AuditedRecordKind kind,
        boolean blocking,
        String text,
        AuditedRecordStatus status,
        List<EvidenceRef> evidenceRefs,
        String sourceStageRunId,
        String blockedReason
) {
    public static final int MAX_EVIDENCE_REFS = 16;

    public AuditedRecord {
        id = requireText(id, "id");
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        text = text == null ? "" : text.strip();
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (evidenceRefs.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("evidenceRefs must not contain null");
        }
        if (evidenceRefs.size() > MAX_EVIDENCE_REFS) {
            throw new IllegalArgumentException("evidenceRefs must not exceed " + MAX_EVIDENCE_REFS);
        }
        sourceStageRunId = sourceStageRunId == null ? "" : sourceStageRunId.strip();
        blockedReason = blockedReason == null ? "" : blockedReason.strip();
        if (status == AuditedRecordStatus.COMPLETED && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("COMPLETED record must carry at least one evidence ref: " + id);
        }
        if (status == AuditedRecordStatus.BLOCKED && blockedReason.isBlank()) {
            throw new IllegalArgumentException("BLOCKED record must have blockedReason: " + id);
        }
    }

    /**
     * Returns a copy with a new status and evidence set.
     *
     * @param nextStatus next status
     * @param nextEvidence replacement evidence
     * @param nextBlockedReason blocked reason, or blank
     * @return updated record
     */
    public AuditedRecord withStatus(
            AuditedRecordStatus nextStatus,
            List<EvidenceRef> nextEvidence,
            String nextBlockedReason
    ) {
        return new AuditedRecord(
                id, kind, blocking, text, nextStatus, nextEvidence, sourceStageRunId, nextBlockedReason);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
