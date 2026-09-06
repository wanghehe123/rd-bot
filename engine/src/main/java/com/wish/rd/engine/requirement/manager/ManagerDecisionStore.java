package com.wish.rd.engine.requirement.manager;

import java.util.List;
import java.util.Optional;

/** Persistence port for Manager decisions. JVM memory implementations are test-only. */
public interface ManagerDecisionStore {

    /**
     * Inserts {@code decision} unless {@code (taskId, sourceCommandId)} already exists.
     *
     * @param decision sealed decision with assigned round
     * @return stored row
     */
    ManagerDecision insertIfAbsent(ManagerDecision decision);

    /**
     * Returns the latest decision for a task.
     *
     * @param taskId task id
     * @return latest round, if any
     */
    Optional<ManagerDecision> findLatest(String taskId);

    /**
     * Returns the decision triggered by one source command.
     *
     * @param taskId task id
     * @param sourceCommandId source command id
     * @return matching decision
     */
    Optional<ManagerDecision> findBySourceCommand(String taskId, String sourceCommandId);

    List<ManagerDecision> listByTask(String taskId);

    /**
     * Returns {@code max(round_no)} or {@code 0} when none exist.
     *
     * @param taskId task id
     * @return max round
     */
    int maxRound(String taskId);
}
