package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Enforces the retrieval-attempt lifecycle without overloading task or agent-stage states. */
public final class RetrievalRunTransitionPolicy {

    private static final Map<RetrievalRunStatus, Set<RetrievalRunStatus>> GRAPH = graph();

    private RetrievalRunTransitionPolicy() {
    }

    public static void ensureTransition(RetrievalRunStatus source, RetrievalRunStatus target) {
        if (source == null || target == null) {
            throw new IllegalStateException("retrieval run status must not be null");
        }
        if (source == target) {
            return;
        }
        if (!GRAPH.getOrDefault(source, Set.of()).contains(target)) {
            throw new IllegalStateException("illegal retrieval run transition: " + source + " -> " + target);
        }
    }

    private static Map<RetrievalRunStatus, Set<RetrievalRunStatus>> graph() {
        EnumMap<RetrievalRunStatus, Set<RetrievalRunStatus>> graph = new EnumMap<>(RetrievalRunStatus.class);
        allow(graph, RetrievalRunStatus.CREATED, RetrievalRunStatus.PLANNING, RetrievalRunStatus.CANCELLED,
                RetrievalRunStatus.RECOVERING);
        allow(graph, RetrievalRunStatus.PLANNING, RetrievalRunStatus.RETRIEVING, RetrievalRunStatus.WAITING_INPUT,
                RetrievalRunStatus.FAILED_RETRYABLE, RetrievalRunStatus.FAILED_NEEDS_HUMAN,
                RetrievalRunStatus.CANCELLED, RetrievalRunStatus.RECOVERING);
        allow(graph, RetrievalRunStatus.RETRIEVING, RetrievalRunStatus.EVALUATING, RetrievalRunStatus.FAILED_RETRYABLE,
                RetrievalRunStatus.FAILED_NEEDS_HUMAN, RetrievalRunStatus.CANCELLED, RetrievalRunStatus.RECOVERING);
        allow(graph, RetrievalRunStatus.EVALUATING, RetrievalRunStatus.PLANNING, RetrievalRunStatus.PACKAGING,
                RetrievalRunStatus.WAITING_INPUT, RetrievalRunStatus.FAILED_RETRYABLE,
                RetrievalRunStatus.FAILED_NEEDS_HUMAN, RetrievalRunStatus.CANCELLED, RetrievalRunStatus.RECOVERING);
        allow(graph, RetrievalRunStatus.PACKAGING, RetrievalRunStatus.SUCCEEDED,
                RetrievalRunStatus.SUCCEEDED_DEGRADED, RetrievalRunStatus.FAILED_RETRYABLE,
                RetrievalRunStatus.FAILED_NEEDS_HUMAN, RetrievalRunStatus.CANCELLED, RetrievalRunStatus.RECOVERING);
        allow(graph, RetrievalRunStatus.RECOVERING, RetrievalRunStatus.PLANNING, RetrievalRunStatus.DEAD_LETTERED,
                RetrievalRunStatus.CANCELLED);
        return Map.copyOf(graph);
    }

    private static void allow(
            EnumMap<RetrievalRunStatus, Set<RetrievalRunStatus>> graph,
            RetrievalRunStatus source,
            RetrievalRunStatus... targets
    ) {
        graph.put(source, EnumSet.copyOf(Set.of(targets)));
    }
}
