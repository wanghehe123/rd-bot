package com.wish.rd.engine.requirement.audit;

/**
 * Runtime mode for {@link AuditedCompletionGate}. State writeback, {@code HOST_VERIFY},
 * fingerprints, and admin APIs stay on in both modes.
 */
public enum AuditedWritebackGateMode {
    /** Record would-reject blockers and warn; do not change REJECTED/COMPLETED decisions. */
    SHADOW,
    /** Reject completion when the audited head does not support it. */
    ENFORCE;

    /**
     * Parses a configuration token. Blank or unknown values fail closed.
     *
     * @param value raw configuration value
     * @return parsed mode
     */
    public static AuditedWritebackGateMode parse(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("gate-mode must be ENFORCE or SHADOW");
        }
        for (AuditedWritebackGateMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("gate-mode must be ENFORCE or SHADOW but was: " + value);
    }
}
