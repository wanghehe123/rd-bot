package com.wish.rd.engine.retrieval.iterative;

import java.util.List;

/** Persistence boundary for per-round iterative retrieval audit records. */
public interface RetrievalRoundAuditStore {

    /** Appends one immutable round record. */
    void append(RetrievalRoundAudit audit);

    /** Returns rounds for one retrieval run in ascending round order. */
    List<RetrievalRoundAudit> listByRun(String runId);

    /** Returns rounds for one task, ordered by run creation/round order. */
    List<RetrievalRoundAudit> listByTask(String taskId);

    /** No-op adapter for callers that do not need iterative audit persistence. */
    static RetrievalRoundAuditStore noop() {
        return new RetrievalRoundAuditStore() {
            @Override
            public void append(RetrievalRoundAudit audit) {
                // Explicitly opt out of audit persistence for single-pass/local callers.
            }

            @Override
            public List<RetrievalRoundAudit> listByRun(String runId) {
                return List.of();
            }

            @Override
            public List<RetrievalRoundAudit> listByTask(String taskId) {
                return List.of();
            }
        };
    }
}
