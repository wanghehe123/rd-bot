package com.wish.rd.engine.retrieval.iterative.impl;

import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAudit;
import com.wish.rd.engine.retrieval.iterative.RetrievalRoundAuditStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** In-memory round audit store for tests and explicit memory mode. */
public final class InMemoryRetrievalRoundAuditStore implements RetrievalRoundAuditStore {

    private final LinkedHashMap<String, RetrievalRoundAudit> auditsById = new LinkedHashMap<>();

    @Override
    public synchronized void append(RetrievalRoundAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit must not be null");
        }
        RetrievalRoundAudit existing = auditsById.putIfAbsent(audit.auditId(), audit);
        if (existing != null && !existing.equals(audit)) {
            throw new IllegalStateException("retrieval round audit already exists: " + audit.auditId());
        }
    }

    @Override
    public synchronized List<RetrievalRoundAudit> listByRun(String runId) {
        String safeRunId = safe(runId);
        return auditsById.values().stream()
                .filter(audit -> safeRunId.equals(audit.runId()))
                .sorted(Comparator.comparingInt(RetrievalRoundAudit::roundNo)
                        .thenComparingLong(RetrievalRoundAudit::recordedAtEpochMillis)
                        .thenComparing(RetrievalRoundAudit::auditId))
                .toList();
    }

    @Override
    public synchronized List<RetrievalRoundAudit> listByTask(String taskId) {
        String safeTaskId = safe(taskId);
        return auditsById.values().stream()
                .filter(audit -> safeTaskId.equals(audit.taskId()))
                .sorted(Comparator.comparingLong(RetrievalRoundAudit::recordedAtEpochMillis)
                        .thenComparingInt(RetrievalRoundAudit::roundNo)
                        .thenComparing(RetrievalRoundAudit::auditId))
                .toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
