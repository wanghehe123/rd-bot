package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.model.RequirementPublication;

import java.util.Optional;

/**
 * Persistence port for requirement publication intents and reconciliation state.
 */
public interface RequirementPublicationStore {

    /**
     * Inserts a prepared publication when the operation id is new.
     *
     * @param publication prepared publication
     * @return existing publication when the operation already exists, otherwise the inserted one
     */
    RequirementPublication insertPrepared(RequirementPublication publication);

    /**
     * Finds a publication by operation id.
     *
     * @param operationId unique operation identity
     * @return publication when present
     */
    Optional<RequirementPublication> findByOperationId(String operationId);

    /**
     * Finds the most recently updated publication for one requirement task.
     *
     * <p>Provider fallback uses this bounded lookup to fail closed when the last
     * remote side effect is unresolved or requires human intervention.
     *
     * @param taskId RD requirement task id
     * @return latest publication ordered by update time and immutable id, when present
     */
    Optional<RequirementPublication> findLatestByTaskId(String taskId);

    /**
     * Lists UNKNOWN_REMOTE_RESULT publications whose next reconcile time is due.
     *
     * @param beforeEpochMillis inclusive upper bound for next_reconcile_at
     * @param limit             max rows
     * @return due publications in stable order
     */
    java.util.List<RequirementPublication> findDueForReconcile(long beforeEpochMillis, int limit);

    /**
     * Saves an updated publication snapshot.
     *
     * @param publication updated publication
     * @return persisted publication
     */
    RequirementPublication save(RequirementPublication publication);
}
