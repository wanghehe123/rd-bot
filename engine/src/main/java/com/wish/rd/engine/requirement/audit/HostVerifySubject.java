package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;

import java.util.List;

/**
 * Host-verify evidence presented to {@link DeterministicAuditor}.
 *
 * @param auditRunId new audit run id
 * @param commandId durable command id
 * @param codingStageRunId coding stage that was verified
 * @param status verification status
 * @param docsOnly whether Host classified the change-set as docs-only
 * @param buildEvidence BUILD artifacts
 * @param staticEvidence STATIC artifacts
 * @param nowEpochMillis audit time
 */
public record HostVerifySubject(
        String auditRunId,
        String commandId,
        String codingStageRunId,
        HostVerificationStatus status,
        boolean docsOnly,
        List<EvidenceRef> buildEvidence,
        List<EvidenceRef> staticEvidence,
        long nowEpochMillis
) {
    public HostVerifySubject {
        auditRunId = require(auditRunId, "auditRunId");
        commandId = require(commandId, "commandId");
        codingStageRunId = codingStageRunId == null ? "" : codingStageRunId.strip();
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        buildEvidence = buildEvidence == null ? List.of() : List.copyOf(buildEvidence);
        staticEvidence = staticEvidence == null ? List.of() : List.copyOf(staticEvidence);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
