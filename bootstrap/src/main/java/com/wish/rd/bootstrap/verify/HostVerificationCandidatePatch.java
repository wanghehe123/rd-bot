package com.wish.rd.bootstrap.verify;

import java.util.Locale;

/**
 * Verified Coding candidate patch used to replay a host-verification workspace.
 *
 * @param content    unified-diff bytes; empty means the coding stage has no patch
 * @param sha256Hex  lowercase hex digest without the {@code sha256:} prefix
 */
public record HostVerificationCandidatePatch(byte[] content, String sha256Hex) {

    /**
     * Normalizes patch bytes and digest.
     *
     * @param content   raw patch bytes
     * @param sha256Hex digest with or without {@code sha256:} prefix
     */
    public HostVerificationCandidatePatch {
        content = content == null ? new byte[0] : content.clone();
        sha256Hex = normalizeSha256(sha256Hex);
    }

    /**
     * Builds a patch from verified bytes and computes the digest later in the factory.
     *
     * @param content unified-diff bytes
     * @return candidate patch wrapper
     */
    public static HostVerificationCandidatePatch of(byte[] content) {
        return new HostVerificationCandidatePatch(content, "");
    }

    /**
     * @return cloned patch bytes
     */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /**
     * @return byte length of the patch
     */
    public int bytes() {
        return content.length;
    }

    /**
     * @return {@code true} when the patch is non-empty
     */
    public boolean isPresent() {
        return content.length > 0;
    }

    private static String normalizeSha256(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) {
            normalized = normalized.substring("sha256:".length());
        }
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }
}
