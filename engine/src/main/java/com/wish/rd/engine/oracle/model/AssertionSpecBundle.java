package com.wish.rd.engine.oracle.model;

import com.wish.rd.engine.oracle.AssertionSpecHasher;
import com.wish.rd.engine.oracle.model.AssertionSpec;

import java.util.List;
import java.util.Objects;

/**
 * Frozen Host-owned assertion bundle with integrity hash.
 *
 * @param specs       immutable assertion list
 * @param contentHash canonical hash of {@code specs}
 */
public record AssertionSpecBundle(
        List<AssertionSpec> specs,
        String contentHash
) {

    public AssertionSpecBundle {
        specs = specs == null ? List.of() : List.copyOf(specs);
        contentHash = contentHash == null ? "" : contentHash.strip();
        if (contentHash.isBlank()) {
            throw new IllegalArgumentException("contentHash must not be blank");
        }
        String expected = AssertionSpecHasher.hashBundle(specs);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                    "assertion bundle contentHash mismatch: expected " + expected + " but was " + contentHash);
        }
    }

    /**
     * Freezes specs under their canonical hash.
     *
     * @param specs assertion list
     * @return frozen bundle
     */
    public static AssertionSpecBundle freeze(List<AssertionSpec> specs) {
        List<AssertionSpec> copy = specs == null ? List.of() : List.copyOf(specs);
        return new AssertionSpecBundle(copy, AssertionSpecHasher.hashBundle(copy));
    }

    /**
     * Verifies an externally supplied hash without constructing a mutated bundle.
     *
     * @param specs       candidate specs
     * @param contentHash claimed hash
     * @return true when hash matches canonical digest
     */
    public static boolean matches(List<AssertionSpec> specs, String contentHash) {
        String claimed = contentHash == null ? "" : contentHash.strip();
        if (claimed.isBlank()) {
            return false;
        }
        return Objects.equals(AssertionSpecHasher.hashBundle(specs), claimed);
    }
}
