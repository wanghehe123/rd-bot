package com.wish.rd.exec.repair.code.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote branch tip lookup result.
 *
 * @param commitSha tip commit SHA
 * @param metadata  provider metadata
 */
public record BranchHeadResult(
        String commitSha,
        Map<String, String> metadata
) {

    public BranchHeadResult {
        commitSha = normalize(commitSha);
        metadata = copyMetadata(metadata);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static Map<String, String> copyMetadata(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalize(key);
            if (normalizedKey.isBlank()) {
                throw new IllegalArgumentException("metadata key must not be blank");
            }
            if (copied.containsKey(normalizedKey)) {
                throw new IllegalArgumentException("metadata key must be unique after normalization: " + normalizedKey);
            }
            copied.put(normalizedKey, normalize(value));
        });
        return Map.copyOf(copied);
    }
}
