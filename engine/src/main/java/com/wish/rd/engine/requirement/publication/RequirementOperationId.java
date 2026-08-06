package com.wish.rd.engine.requirement.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a stable publication operation id from task + branch + candidate patch identity.
 */
public final class RequirementOperationId {

    private RequirementOperationId() {
    }

    /**
     * Builds {@code sha256(taskId|baseBranch|workBranch|candidatePatchSha256)}.
     *
     * @param taskId               RD task id
     * @param baseBranch           target branch
     * @param workBranch           candidate work branch
     * @param candidatePatchSha256 candidate patch digest
     * @return operation id hex digest prefixed with {@code sha256:}
     */
    public static String of(
            String taskId,
            String baseBranch,
            String workBranch,
            String candidatePatchSha256
    ) {
        String material = require(taskId, "taskId")
                + "|" + require(baseBranch, "baseBranch")
                + "|" + require(workBranch, "workBranch")
                + "|" + normalizeSha(require(candidatePatchSha256, "candidatePatchSha256"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalizeSha(String value) {
        String normalized = value.strip().toLowerCase();
        return normalized.startsWith("sha256:") ? normalized.substring("sha256:".length()) : normalized;
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
