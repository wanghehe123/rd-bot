package com.wish.rd.rag.project.memory.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical, field-named length-prefixed SHA-256 operation key encoder. */
public final class ProjectMemoryOperationKey {
    private ProjectMemoryOperationKey() {
    }

    public static String sha256(String projectId, String kind, String sourceIdentity, String sourceContentHash,
                                String extractorVersion, String schemaVersion) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("projectId", required(projectId, "projectId"));
        fields.put("kind", required(kind, "kind"));
        fields.put("sourceIdentity", required(sourceIdentity, "sourceIdentity"));
        fields.put("sourceContentHash", required(sourceContentHash, "sourceContentHash"));
        fields.put("extractorVersion", required(extractorVersion, "extractorVersion"));
        fields.put("schemaVersion", required(schemaVersion, "schemaVersion"));
        StringBuilder canonical = new StringBuilder();
        fields.forEach((name, value) -> canonical.append(name.length()).append(':').append(name)
                .append('=').append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
