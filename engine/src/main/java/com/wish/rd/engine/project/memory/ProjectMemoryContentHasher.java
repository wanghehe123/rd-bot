package com.wish.rd.engine.project.memory;

import com.wish.rd.engine.project.memory.model.ProjectMemoryCandidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Host-owned canonical content digest for consolidation comparisons. */
public final class ProjectMemoryContentHasher {
    private ProjectMemoryContentHasher() {
    }

    public static String sha256(ProjectMemoryCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        String canonical = "logicalKey=" + candidate.logicalKey()
                + "\ntitle=" + candidate.title()
                + "\nsummary=" + candidate.summary()
                + "\nschemaVersion=" + candidate.schemaVersion();
        return digest(canonical);
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
