package com.wish.rd.engine.requirement.job.model;

/**
 * Immutable continuation identity input, intentionally excluding task concurrency metadata.
 *
 * <p>The Host derives version and fencing token from the finalized mutation count rather than
 * reloading a task snapshot after external work.
 *
 * @param role next role, blank only for a terminal plan
 * @param stage next stage, blank only for a terminal plan
 */
public record ContinuationSpec(String role, String stage) {

    /** Normalizes continuation identity and rejects partial terminal encodings. */
    public ContinuationSpec {
        role = safe(role);
        stage = safe(stage);
        if (role.isBlank() != stage.isBlank()) {
            throw new IllegalArgumentException("continuation role and stage must both be blank or nonblank");
        }
    }

    /**
     * Returns the explicit terminal continuation.
     *
     * @return terminal continuation specification
     */
    public static ContinuationSpec terminal() {
        return new ContinuationSpec("", "");
    }

    /**
     * Returns whether this plan has no continuation.
     *
     * @return true when role and stage are both blank
     */
    public boolean isTerminal() {
        return role.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
