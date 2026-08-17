package com.wish.rd.engine.requirement.verify;

import com.wish.rd.engine.requirement.verify.model.HostVerificationArtifact;
import com.wish.rd.engine.requirement.verify.model.HostVerificationRun;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStatus;
import com.wish.rd.engine.requirement.verify.model.HostVerificationStep;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for host BUILD/STATIC verification runs, steps, and artifacts.
 *
 * <p>Production uses PostgreSQL. {@code InMemoryHostVerificationStore} is test-only.
 * Callers will be {@code HostVerificationEngine} and the later HTTP adapters.
 */
public interface HostVerificationStore {

    /**
     * Inserts a new verification run.
     *
     * @param run snapshot to persist
     * @return the stored snapshot
     * @throws IllegalArgumentException when {@code run} is null
     * @throws IllegalStateException when {@code runId} or {@code (taskId, attemptNo)} already exists
     */
    HostVerificationRun create(HostVerificationRun run);

    /**
     * Loads one run by id.
     *
     * @param runId verification run id
     * @return the snapshot when present
     */
    Optional<HostVerificationRun> find(String runId);

    /**
     * Lists runs for a task ordered by {@code attemptNo}.
     *
     * @param taskId owning RD task id
     * @return immutable snapshots, possibly empty
     */
    List<HostVerificationRun> listByTask(String taskId);

    /**
     * Advances a run only when the persisted status still matches {@code expected}.
     *
     * <p>Terminal runs are immutable. The first leave from {@code CREATED} sets
     * {@code startedAt}; entering a terminal status sets {@code finishedAt}.
     *
     * @param runId            verification run id
     * @param expected         expected current status
     * @param target           next status
     * @param failureCategory  machine-readable failure class, or blank
     * @param errorMessage     operator-facing error, or blank
     * @param nowEpochMillis   transition time
     * @return updated snapshot
     * @throws IllegalStateException when the expected status is stale, the run is terminal, or the edge is illegal
     */
    HostVerificationRun transition(
            String runId,
            HostVerificationStatus expected,
            HostVerificationStatus target,
            String failureCategory,
            String errorMessage,
            long nowEpochMillis
    );

    /**
     * Inserts or replaces one BUILD/STATIC step snapshot.
     *
     * @param step step snapshot
     * @throws IllegalArgumentException when {@code step} is null
     */
    void saveStep(HostVerificationStep step);

    /**
     * Lists steps for a run in BUILD-then-STATIC order.
     *
     * @param runId verification run id
     * @return immutable snapshots
     */
    List<HostVerificationStep> listSteps(String runId);

    /**
     * Appends one evidence object for a run.
     *
     * @param artifact artifact metadata
     * @return the stored snapshot
     * @throws IllegalArgumentException when {@code artifact} is null
     */
    HostVerificationArtifact appendArtifact(HostVerificationArtifact artifact);

    /**
     * Lists artifacts for a run in created order.
     *
     * @param runId verification run id
     * @return immutable snapshots
     */
    List<HostVerificationArtifact> listArtifacts(String runId);

    /**
     * Deletes every evidence row owned by a task.
     *
     * <p>Callers must delete file/s3 objects first. Runs and steps stay so
     * failure provenance can keep referencing a verification run id.
     *
     * @param taskId owning RD task id
     * @return number of artifact rows removed
     * @throws IllegalArgumentException when {@code taskId} is blank
     */
    int deleteByTask(String taskId);
}
