package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates RD task state transitions against a task-type-specific graph.
 *
 * <p>The requirement and Bug-fix workflows share the persisted status enum but do not share a
 * business lifecycle. Keeping the graphs separate prevents a caller from moving a requirement
 * into Bug-only {@code SEARCHING}, or a Bug task into requirement planning states.
 */
public final class RdTaskTransitionPolicy {

    private static final Map<RdTaskType, Map<RdTaskStatus, Set<RdTaskStatus>>> GRAPHS = Map.of(
            RdTaskType.REQUIREMENT, requirementGraph(),
            RdTaskType.BUG_FIX, bugFixGraph(),
            RdTaskType.QNA, emptyGraph()
    );

    private RdTaskTransitionPolicy() {
    }

    /**
     * Ensures the requested transition belongs to the supplied task type.
     *
     * @param taskType task workflow type
     * @param source current status
     * @param target requested status
     * @throws IllegalStateException when the edge is not legal for the task type
     */
    public static void ensureTransition(RdTaskType taskType, RdTaskStatus source, RdTaskStatus target) {
        if (taskType == null || source == null || target == null) {
            throw new IllegalStateException("task type and status must not be null");
        }
        if (source == target) {
            return;
        }
        Set<RdTaskStatus> targets = GRAPHS.getOrDefault(taskType, Map.of()).getOrDefault(source, Set.of());
        if (!targets.contains(target)) {
            throw new IllegalStateException(
                    "illegal " + taskType.name() + " task status transition: " + source + " -> " + target
            );
        }
    }

    private static Map<RdTaskStatus, Set<RdTaskStatus>> requirementGraph() {
        EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph = graph();
        put(graph, RdTaskStatus.CREATED, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.MATERIAL_COLLECTING, RdTaskStatus.MATERIAL_READY, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.MATERIAL_READY, RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.CONTEXT_READY, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.CONTEXT_READY, RdTaskStatus.PLAN_GENERATING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.PLAN_GENERATING, RdTaskStatus.PLAN_GENERATED, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.PLAN_GENERATED, RdTaskStatus.WAITING_POLICY, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.WAITING_POLICY, RdTaskStatus.EXECUTING, RdTaskStatus.WAITING_APPROVAL,
                RdTaskStatus.REJECTED, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED,
                RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.WAITING_APPROVAL, RdTaskStatus.EXECUTING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.EXECUTING, RdTaskStatus.VALIDATING, RdTaskStatus.WAITING_APPROVAL, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED,
                RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.VALIDATING, RdTaskStatus.PR_CREATING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.PR_CREATING, RdTaskStatus.COMMITTED, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.COMMITTED, RdTaskStatus.MERGED, RdTaskStatus.REPORTING,
                RdTaskStatus.COMPLETED, RdTaskStatus.REJECTED, RdTaskStatus.CANCELLED);
        put(graph, RdTaskStatus.REPORTING, RdTaskStatus.COMPLETED, RdTaskStatus.FAILED_RETRYABLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.COMPLETED, RdTaskStatus.MERGED);
        put(graph, RdTaskStatus.REJECTED, RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.CANCELLED, RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.DEAD_LETTERED, RdTaskStatus.RECOVERING);
        put(graph, RdTaskStatus.RECOVERING, RdTaskStatus.MATERIAL_COLLECTING,
                RdTaskStatus.CONTEXT_BUILDING, RdTaskStatus.WAITING_POLICY, RdTaskStatus.WAITING_APPROVAL,
                RdTaskStatus.EXECUTING, RdTaskStatus.VALIDATING, RdTaskStatus.PR_CREATING,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        return immutable(graph);
    }

    private static Map<RdTaskStatus, Set<RdTaskStatus>> bugFixGraph() {
        EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph = graph();
        put(graph, RdTaskStatus.CREATED, RdTaskStatus.SEARCHING, RdTaskStatus.REJECTED,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.SEARCHING, RdTaskStatus.EXECUTING, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.EXECUTING, RdTaskStatus.VALIDATING, RdTaskStatus.COMMITTED,
                RdTaskStatus.REJECTED, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.FAILED_NEEDS_HUMAN,
                RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.VALIDATING, RdTaskStatus.COMMITTED, RdTaskStatus.REJECTED,
                RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.COMMITTED, RdTaskStatus.MERGED, RdTaskStatus.REPORTING,
                RdTaskStatus.COMPLETED, RdTaskStatus.REJECTED, RdTaskStatus.CANCELLED);
        put(graph, RdTaskStatus.REPORTING, RdTaskStatus.COMPLETED, RdTaskStatus.FAILED_RETRYABLE,
                RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.COMPLETED, RdTaskStatus.MERGED);
        put(graph, RdTaskStatus.REJECTED, RdTaskStatus.SEARCHING, RdTaskStatus.EXECUTING,
                RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.FAILED_RETRYABLE, RdTaskStatus.SEARCHING, RdTaskStatus.EXECUTING,
                RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.RECOVERING, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        put(graph, RdTaskStatus.RECOVERING, RdTaskStatus.SEARCHING, RdTaskStatus.EXECUTING,
                RdTaskStatus.FAILED_NEEDS_HUMAN, RdTaskStatus.CANCELLED, RdTaskStatus.DEAD_LETTERED);
        return immutable(graph);
    }

    private static EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph() {
        EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph = new EnumMap<>(RdTaskStatus.class);
        for (RdTaskStatus status : RdTaskStatus.values()) {
            graph.put(status, Set.of());
        }
        return graph;
    }

    private static void put(
            EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph,
            RdTaskStatus source,
            RdTaskStatus first,
            RdTaskStatus... rest
    ) {
        EnumSet<RdTaskStatus> targets = EnumSet.of(first, rest);
        graph.put(source, Set.copyOf(targets));
    }

    private static Map<RdTaskStatus, Set<RdTaskStatus>> immutable(
            EnumMap<RdTaskStatus, Set<RdTaskStatus>> graph
    ) {
        return Map.copyOf(graph);
    }

    private static Map<RdTaskStatus, Set<RdTaskStatus>> emptyGraph() {
        return immutable(graph());
    }
}
