package com.wish.rd.engine.requirement.query;

/**
 * Read-only loader for one stage's persisted result.
 */
public interface StageResultReadPort {

    /**
     * Loads one stage result raw snapshot.
     *
     * @param taskId owning task
     * @param stageRunId stage run
     * @return raw snapshot
     */
    StageResultSnapshot read(String taskId, String stageRunId);
}
