package com.wish.rd.engine.requirement.audit;

import java.util.List;

/**
 * QA evidence presented to {@link DeterministicAuditor}.
 *
 * @param auditRunId new audit run id
 * @param commandId durable command id
 * @param qaStageRunId QA stage run
 * @param piQaRemediationV2 whether the attempt had the v2 capability
 * @param fingerprintBefore pre-container fingerprint, or {@code null} when missing
 * @param fingerprintAfter post-container fingerprint, or {@code null} when missing
 * @param currentAcceptances CURRENT-scope results
 */
public record QaSubject(
        String auditRunId,
        String commandId,
        String qaStageRunId,
        boolean piQaRemediationV2,
        WorkspaceFingerprintReceipt fingerprintBefore,
        WorkspaceFingerprintReceipt fingerprintAfter,
        List<QaCurrentAcceptance> currentAcceptances
) {
    public QaSubject {
        auditRunId = require(auditRunId, "auditRunId");
        commandId = require(commandId, "commandId");
        qaStageRunId = require(qaStageRunId, "qaStageRunId");
        currentAcceptances = currentAcceptances == null ? List.of() : List.copyOf(currentAcceptances);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
