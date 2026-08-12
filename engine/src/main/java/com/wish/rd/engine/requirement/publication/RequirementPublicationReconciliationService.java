package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * Best-effort remote reconciliation for {@code UNKNOWN_REMOTE_RESULT} publications.
 * Shared by delivery resume and the background reconcile scheduler.
 */
public final class RequirementPublicationReconciliationService {

    public static final long DEFAULT_DEFER_MILLIS = 30_000L;

    private final RequirementPublicationLedger ledger;
    private final RequirementPublicationReconcilePort reconciler;

    public RequirementPublicationReconciliationService(
            RequirementPublicationLedger ledger,
            RequirementPublicationReconcilePort reconciler
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.reconciler = reconciler;
    }

    /**
     * Attempts branch-head and/or open-PR recovery for one UNKNOWN publication.
     *
     * @param operationId publication operation id
     * @param taskId      RD task id
     * @param repoOwner   repository owner
     * @param repoName    repository name
     * @return publication after best-effort reconcile
     */
    public RequirementPublication reconcileUnknown(
            String operationId,
            String taskId,
            String repoOwner,
            String repoName
    ) {
        RequirementPublication snapshot = ledger.findByOperationId(operationId).orElse(null);
        if (snapshot == null || snapshot.status() != RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT) {
            return snapshot;
        }
        if (reconciler == null) {
            return snapshot;
        }
        try {
            if (snapshot.remoteHeadSha().isBlank()) {
                RequirementPublicationReconcilePort.RemoteBranchHead branchHead =
                        reconciler.resolveRemoteBranchHead(
                                new RequirementPublicationReconcilePort.BranchHeadQuery(
                                        safe(repoOwner),
                                        safe(repoName),
                                        snapshot.workBranch()
                                ));
                if (branchHead instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Present present
                        && !present.commitSha().isBlank()) {
                    if (!present.matches(snapshot.operationId(), snapshot.candidatePatchSha256())) {
                        return ledger.markNeedsHuman(
                                operationId,
                                "remote branch markers do not match publication operation or candidate patch"
                        );
                    }
                    ledger.reconcileBranchConfirmed(operationId, present.commitSha());
                } else if (branchHead instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Absent) {
                    return ledger.reconcileResetPrepared(operationId);
                }
                snapshot = ledger.findByOperationId(operationId).orElse(null);
                if (snapshot == null
                        || snapshot.status() != RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT
                        || snapshot.remoteHeadSha().isBlank()) {
                    return snapshot;
                }
            }
            if (snapshot.status() != RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT
                    || snapshot.remoteHeadSha().isBlank()) {
                return snapshot;
            }
            RequirementPublicationReconcilePort.ReconcileQuery reconcileQuery =
                    new RequirementPublicationReconcilePort.ReconcileQuery(
                            safe(repoOwner),
                            safe(repoName),
                            snapshot.baseBranch(),
                            snapshot.workBranch(),
                            safe(taskId),
                            operationId
                    );
            Optional<RequirementPublicationReconcilePort.MatchedOpenPullRequest> matched =
                    reconciler.findMatchingOpenPullRequest(reconcileQuery)
                            .filter(match -> !match.pullRequestUrl().isBlank() && match.pullRequestNumber() > 0);
            if (matched.isPresent()) {
                RequirementPublicationReconcilePort.MatchedOpenPullRequest match = matched.get();
                ledger.reconcilePullRequestConfirmed(
                        operationId,
                        match.pullRequestUrl(),
                        match.pullRequestNumber()
                );
                return ledger.findByOperationId(operationId).orElse(snapshot);
            }
            Optional<RequirementPublicationReconcilePort.ConflictingOpenPullRequest> conflict =
                    reconciler.findConflictingOpenPullRequest(reconcileQuery);
            if (conflict.isPresent()) {
                return ledger.markNeedsHuman(operationId, conflict.get().reason());
            }
            return ledger.findByOperationId(operationId).orElse(snapshot);
        } catch (RuntimeException ignored) {
            return ledger.findByOperationId(operationId).orElse(snapshot);
        }
    }

    /**
     * Defers the next poll when the publication is still UNKNOWN after a tick.
     *
     * @param operationId publication operation id
     * @return deferred publication, or current snapshot when no longer UNKNOWN
     */
    public RequirementPublication deferIfStillUnknown(String operationId) {
        RequirementPublication snapshot = ledger.findByOperationId(operationId).orElse(null);
        if (snapshot == null || snapshot.status() != RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT) {
            return snapshot;
        }
        return ledger.deferReconcile(operationId, DEFAULT_DEFER_MILLIS);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
