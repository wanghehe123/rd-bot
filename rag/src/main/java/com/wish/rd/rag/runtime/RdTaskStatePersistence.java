package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;

/**
 * Persists an RD task snapshot and the corresponding timeline event as one mutation boundary.
 *
 * <p>The PostgreSQL adapter must implement this boundary with one database transaction. Local and
 * test adapters preserve the same call contract while relying on the registry's task-scoped lock.
 */
public interface RdTaskStatePersistence {

    /**
     * Saves the task and event together.
     *
     * @param task updated task snapshot
     * @param event timeline event, or {@code null} when timeline persistence is disabled
     * @return saved task snapshot
     */
    RdTask saveWithEvent(RdTask task, RdTaskStatusEvent event);

    /**
     * Saves a status transition and its event while checking the expected snapshot metadata.
     * Implementations backed by PostgreSQL must execute the CAS and event insert in one transaction.
     * The default keeps source compatibility for lightweight test adapters.
     *
     * @param task next task snapshot
     * @param event timeline event
     * @param expectedVersion version read by the worker
     * @param expectedFencingToken fencing token read by the worker
     * @param expectedStatus status read by the worker
     * @return persisted snapshot
     */
    default RdTask saveWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        return saveWithEvent(task, event);
    }

    /**
     * Persists a non-status task command and its optional event under the same CAS boundary as a
     * status transition. Production adapters must keep the snapshot update and event insert in one
     * transaction; local adapters may rely on the registry's task-scoped lock.
     *
     * @param task             next task snapshot
     * @param event            timeline event, or {@code null} for metadata-only edits
     * @param expectedVersion  version read by the caller
     * @param expectedFencingToken fencing token read by the caller
     * @param expectedStatus   status read by the caller
     * @return persisted snapshot
     */
    default RdTask saveActionWithEventCas(
            RdTask task,
            RdTaskStatusEvent event,
            long expectedVersion,
            long expectedFencingToken,
            RdTaskStatus expectedStatus
    ) {
        return saveWithEventCas(task, event, expectedVersion, expectedFencingToken, expectedStatus);
    }
}
