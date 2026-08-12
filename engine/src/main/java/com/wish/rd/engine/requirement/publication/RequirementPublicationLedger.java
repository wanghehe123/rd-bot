package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationReplayDecision;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Coordinates external publication intents so remote writes are prepared before
 * side effects and ambiguous results are reconciled instead of replayed.
 */
public final class RequirementPublicationLedger {

    private static final long DEFAULT_RECONCILE_DELAY_MILLIS = 30_000L;

    private final RequirementPublicationStore store;
    private final LongSupplier clock;
    private final IdGenerator idGenerator;

    /**
     * Creates a ledger with wall-clock time and random publication ids.
     *
     * @param store publication store
     */
    public RequirementPublicationLedger(RequirementPublicationStore store) {
        this(store, System::currentTimeMillis, () -> UUID.randomUUID().toString());
    }

    RequirementPublicationLedger(
            RequirementPublicationStore store,
            LongSupplier clock,
            IdGenerator idGenerator
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
    }

    /**
     * Inserts or reuses a prepared publication intent for one operation id.
     *
     * @param command prepare command
     * @return prepared or existing publication
     */
    public RequirementPublication prepare(RequirementPublicationPrepareCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        long now = now();
        RequirementPublication prepared = RequirementPublication.prepared(
                idGenerator.nextId(),
                command.operationId(),
                command.taskId(),
                command.stageRunId(),
                command.baseBranch(),
                command.workBranch(),
                command.candidatePatchSha256(),
                now
        );
        return store.insertPrepared(prepared);
    }

    /**
     * Records the remote branch head observed for this prepared operation.
     *
     * <p>The generic branch-head port proves branch existence and tip identity,
     * but does not by itself prove exact candidate-patch hash equality. A
     * provider-specific operation marker is required for that stronger claim.
     *
     * @param operationId   unique operation identity
     * @param remoteHeadSha observed remote head
     * @return updated publication
     */
    public RequirementPublication markBranchConfirmed(String operationId, String remoteHeadSha) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.BRANCH_CONFIRMED
                || current.status() == RequirementPublicationStatus.PR_CONFIRMED
                || current.status() == RequirementPublicationStatus.COMMITTED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.PREPARED);
        return store.save(current.withBranchConfirmed(remoteHeadSha, now()));
    }

    /**
     * Confirms that the remote pull request exists for this operation.
     *
     * @param operationId       unique operation identity
     * @param pullRequestUrl    PR url
     * @param pullRequestNumber PR number
     * @return updated publication
     */
    public RequirementPublication markPullRequestConfirmed(
            String operationId,
            String pullRequestUrl,
            int pullRequestNumber
    ) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.PR_CONFIRMED
                || current.status() == RequirementPublicationStatus.COMMITTED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.BRANCH_CONFIRMED);
        return store.save(current.withPullRequestConfirmed(pullRequestUrl, pullRequestNumber, now()));
    }

    /**
     * Marks the publication committed after the task snapshot records the PR.
     *
     * @param operationId unique operation identity
     * @return updated publication
     */
    public RequirementPublication markCommitted(String operationId) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.COMMITTED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.PR_CONFIRMED);
        return store.save(current.withCommitted(now()));
    }

    /**
     * Records an ambiguous remote write that must be reconciled later.
     *
     * @param operationId unique operation identity
     * @param error       diagnostic error
     * @return updated publication
     */
    public RequirementPublication markUnknownRemoteResult(String operationId, String error) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT) {
            return current;
        }
        if (current.status() != RequirementPublicationStatus.PREPARED
                && current.status() != RequirementPublicationStatus.BRANCH_CONFIRMED) {
            throw new IllegalStateException(
                    "cannot mark unknown remote result from status " + current.status()
                            + " for operation " + operationId);
        }
        long now = now();
        return store.save(current.withUnknownRemoteResult(error, now + DEFAULT_RECONCILE_DELAY_MILLIS, now));
    }

    /**
     * Resolves UNKNOWN_REMOTE_RESULT after a remote branch tip is observed for a
     * push-timeout ambiguity (blank remoteHeadSha). Requires UNKNOWN with no prior head.
     *
     * @param operationId   unique operation identity
     * @param remoteHeadSha observed remote tip
     * @return updated publication
     */
    public RequirementPublication reconcileBranchConfirmed(String operationId, String remoteHeadSha) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.BRANCH_CONFIRMED
                || current.status() == RequirementPublicationStatus.PR_CONFIRMED
                || current.status() == RequirementPublicationStatus.COMMITTED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT);
        if (!current.remoteHeadSha().isBlank()) {
            throw new IllegalStateException(
                    "cannot reconcile branch when remote head already present for operation "
                            + operationId);
        }
        return store.save(current.withBranchConfirmed(remoteHeadSha, now()));
    }

    /**
     * Resolves UNKNOWN_REMOTE_RESULT when reconcile proves the remote branch does not
     * exist (push likely never landed). Clears remote evidence so replay may push again.
     *
     * @param operationId unique operation identity
     * @return updated publication in PREPARED
     */
    public RequirementPublication reconcileResetPrepared(String operationId) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.PREPARED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT);
        if (!current.remoteHeadSha().isBlank()) {
            throw new IllegalStateException(
                    "cannot reset prepared when remote head already present for operation "
                            + operationId);
        }
        return store.save(current.withResetPrepared(now()));
    }

    /**
     * Escalates a publication that cannot be safely auto-reconciled.
     *
     * @param operationId unique operation identity
     * @param reason      diagnostic reason
     * @return updated publication in NEEDS_HUMAN
     */
    public RequirementPublication markNeedsHuman(String operationId, String reason) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.NEEDS_HUMAN) {
            return current;
        }
        if (current.status() == RequirementPublicationStatus.COMMITTED
                || current.status() == RequirementPublicationStatus.PR_CONFIRMED) {
            throw new IllegalStateException(
                    "cannot mark needs-human from status " + current.status()
                            + " for operation " + operationId);
        }
        return store.save(current.withNeedsHuman(reason, now()));
    }

    /**
     * Resolves UNKNOWN_REMOTE_RESULT after a marker-matched open PR is observed.
     * Requires a prior branch confirmation (non-blank remote head) so push-timeout
     * ambiguity is not silently treated as PR success.
     *
     * @param operationId       unique operation identity
     * @param pullRequestUrl    observed PR url
     * @param pullRequestNumber observed PR number
     * @return updated publication
     */
    public RequirementPublication reconcilePullRequestConfirmed(
            String operationId,
            String pullRequestUrl,
            int pullRequestNumber
    ) {
        RequirementPublication current = require(operationId);
        if (current.status() == RequirementPublicationStatus.PR_CONFIRMED
                || current.status() == RequirementPublicationStatus.COMMITTED) {
            return current;
        }
        requireStatus(current, RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT);
        if (current.remoteHeadSha().isBlank()) {
            throw new IllegalStateException(
                    "cannot reconcile pull request without confirmed remote head for operation "
                            + operationId);
        }
        return store.save(current.withPullRequestConfirmed(pullRequestUrl, pullRequestNumber, now()));
    }

    /**
     * Decides whether a worker may push, create a PR, reuse existing remote state,
     * wait for reconciliation, or stop for human review.
     *
     * @param operationId unique operation identity
     * @return replay decision
     */
    public RequirementPublicationReplayDecision decideReplay(String operationId) {
        RequirementPublication current = require(operationId);
        return switch (current.status()) {
            case PREPARED -> RequirementPublicationReplayDecision.ALLOW_PUSH;
            case BRANCH_CONFIRMED -> RequirementPublicationReplayDecision.ALLOW_CREATE_PULL_REQUEST;
            case PR_CONFIRMED, COMMITTED -> RequirementPublicationReplayDecision.REUSE_PULL_REQUEST;
            case UNKNOWN_REMOTE_RESULT -> RequirementPublicationReplayDecision.WAIT_RECONCILE;
            case NEEDS_HUMAN -> RequirementPublicationReplayDecision.NEEDS_HUMAN;
        };
    }

    /**
     * Looks up a publication by operation id.
     *
     * @param operationId unique operation identity
     * @return publication when present
     */
    public Optional<RequirementPublication> findByOperationId(String operationId) {
        return store.findByOperationId(operationId == null ? "" : operationId.strip());
    }

    /**
     * Finds the newest publication state associated with one requirement task.
     *
     * @param taskId RD requirement task id
     * @return latest publication when the task has produced a remote-side-effect intent
     */
    public Optional<RequirementPublication> findLatestByTaskId(String taskId) {
        return store.findLatestByTaskId(taskId == null ? "" : taskId.strip());
    }

    /**
     * Pushes next_reconcile_at forward while status remains UNKNOWN_REMOTE_RESULT.
     *
     * @param operationId unique operation identity
     * @param delayMillis minimum delay after the later of now and the current schedule
     * @return updated publication
     */
    public RequirementPublication deferReconcile(String operationId, long delayMillis) {
        RequirementPublication current = require(operationId);
        requireStatus(current, RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT);
        long now = now();
        long delay = Math.max(1_000L, delayMillis);
        long base = Math.max(now, current.nextReconcileAtEpochMillis());
        return store.save(current.withNextReconcileAt(base + delay, now));
    }

    private RequirementPublication require(String operationId) {
        return store.findByOperationId(operationId)
                .orElseThrow(() -> new IllegalStateException("publication not found: " + operationId));
    }

    private static void requireStatus(RequirementPublication publication, RequirementPublicationStatus expected) {
        if (publication.status() != expected) {
            throw new IllegalStateException(
                    "publication " + publication.operationId()
                            + " expected status " + expected
                            + " but was " + publication.status());
        }
    }

    private long now() {
        return Math.max(0L, clock.getAsLong());
    }

    @FunctionalInterface
    interface IdGenerator {
        String nextId();
    }
}
