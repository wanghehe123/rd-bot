package com.wish.rd.engine.retrieval.iterative;

/**
 * Why an iterative retrieval loop stops.
 */
public enum RetrievalStopReason {
    GATE_SATISFIED,
    NO_NEW_EVIDENCE,
    MAX_ROUNDS,
    TOKEN_BUDGET,
    TIME_BUDGET,
    NEEDS_CLARIFICATION,
    CONTINUE
}
