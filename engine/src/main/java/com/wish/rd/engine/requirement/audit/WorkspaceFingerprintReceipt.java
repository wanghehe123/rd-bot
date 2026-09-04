package com.wish.rd.engine.requirement.audit;

/**
 * Tracked-tree fingerprint captured around a QA container.
 *
 * @param headSha git HEAD
 * @param trackedTreeSha256 SHA-256 of tracked file contents
 * @param trackedFileCount number of tracked files
 */
public record WorkspaceFingerprintReceipt(
        String headSha,
        String trackedTreeSha256,
        int trackedFileCount
) {
    public WorkspaceFingerprintReceipt {
        headSha = headSha == null ? "" : headSha.strip();
        trackedTreeSha256 = trackedTreeSha256 == null ? "" : trackedTreeSha256.strip();
        if (trackedFileCount < 0) {
            throw new IllegalArgumentException("trackedFileCount must be >= 0");
        }
    }
}
