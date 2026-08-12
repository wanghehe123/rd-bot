package com.wish.rd.engine.retrieval.iterative;

import java.util.List;

/** Immutable, redacted audit record for one iterative retrieval round. */
public record RetrievalRoundAudit(
        String auditId,
        String taskId,
        String runId,
        int roundNo,
        String query,
        List<String> candidateEvidenceIds,
        List<String> selectedEvidenceIds,
        List<String> missingEvidenceTypes,
        int informationGain,
        long cumulativeTokens,
        long elapsedMillis,
        String stopReason,
        String scopeFingerprint,
        long recordedAtEpochMillis
) {

    public RetrievalRoundAudit {
        auditId = require(auditId, "auditId");
        taskId = require(taskId, "taskId");
        runId = require(runId, "runId");
        roundNo = Math.max(1, roundNo);
        query = bounded(query, 4_000);
        candidateEvidenceIds = normalize(candidateEvidenceIds);
        selectedEvidenceIds = normalize(selectedEvidenceIds);
        missingEvidenceTypes = normalize(missingEvidenceTypes);
        informationGain = Math.max(0, informationGain);
        cumulativeTokens = Math.max(0L, cumulativeTokens);
        elapsedMillis = Math.max(0L, elapsedMillis);
        stopReason = safe(stopReason);
        scopeFingerprint = safe(scopeFingerprint);
        recordedAtEpochMillis = Math.max(0L, recordedAtEpochMillis);
    }

    private static List<String> normalize(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    private static String require(String value, String name) {
        String safeValue = safe(value);
        if (safeValue.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return safeValue;
    }

    private static String bounded(String value, int maxChars) {
        String safeValue = safe(value);
        return safeValue.length() <= maxChars ? safeValue : safeValue.substring(0, maxChars);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
