package com.wish.rd.engine.retrieval.iterative;

/**
 * Explicit policy for selecting iterative retrieval. Production callers should use
 * {@link #disabled()} unless a task or project policy opts in with bounded limits.
 *
 * @param enabled whether iterative retrieval is allowed
 * @param limits round, token, wall-clock, and no-gain bounds
 */
public record IterativeRetrievalPolicy(
        boolean enabled,
        RetrievalIterationLimits limits
) {

    public IterativeRetrievalPolicy {
        limits = limits == null ? RetrievalIterationLimits.defaults() : limits;
    }

    /** Returns the default single-pass production policy. */
    public static IterativeRetrievalPolicy disabled() {
        return new IterativeRetrievalPolicy(false, RetrievalIterationLimits.defaults());
    }

    /** Returns an explicitly enabled bounded policy. */
    public static IterativeRetrievalPolicy enabled(RetrievalIterationLimits limits) {
        return new IterativeRetrievalPolicy(true, limits);
    }

    /** JavaBean-style predicate for configuration and adapter code. */
    public boolean isEnabled() {
        return enabled;
    }
}
