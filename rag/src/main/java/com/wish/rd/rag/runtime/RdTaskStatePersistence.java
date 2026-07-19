package com.wish.rd.rag.runtime;

import com.wish.rd.rag.runtime.model.RdTask;
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
}
