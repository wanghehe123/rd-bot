package com.wish.rd.engine.evaluation.model;

import java.util.regex.Pattern;

/** Redacted descriptor of a fully verified, immutable coding benchmark input snapshot. */
public record CodingBenchmarkSnapshot(
        String snapshotId,
        String displayLabel,
        String snapshotDigest,
        int caseCount
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects path-like IDs and unverified digest strings before a snapshot reaches a Web client. */
    public CodingBenchmarkSnapshot {
        snapshotId = requireId(snapshotId, "snapshot id");
        displayLabel = requireId(displayLabel, "display label");
        snapshotDigest = requireDigest(snapshotDigest);
        if (caseCount < 1) {
            throw new IllegalArgumentException("case count must be positive");
        }
    }

    private static String requireId(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String requireDigest(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("snapshot digest must be an immutable sha256 value");
        }
        return normalized;
    }
}
