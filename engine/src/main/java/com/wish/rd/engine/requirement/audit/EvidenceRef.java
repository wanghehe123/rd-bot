package com.wish.rd.engine.requirement.audit;

/**
 * Pointer to a persisted Host artifact that backs an audited record.
 *
 * @param auditRunId audit run that produced this reference
 * @param sourceKind evidence family
 * @param uri durable URI of the persisted artifact
 * @param sha256 content digest of the artifact
 */
public record EvidenceRef(
        String auditRunId,
        EvidenceSourceKind sourceKind,
        String uri,
        String sha256
) {
    public EvidenceRef {
        auditRunId = requireText(auditRunId, "auditRunId");
        if (sourceKind == null) {
            throw new IllegalArgumentException("sourceKind must not be null");
        }
        uri = requireText(uri, "uri");
        sha256 = requireText(sha256, "sha256");
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
