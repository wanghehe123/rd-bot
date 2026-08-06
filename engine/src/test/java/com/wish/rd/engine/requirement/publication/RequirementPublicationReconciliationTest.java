package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.BranchHeadQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.MatchedOpenPullRequest;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.ReconcileQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.RemoteBranchHead;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationReplayDecision;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the external-publication ledger rules before any GitHub/Git adapter wiring.
 */
class RequirementPublicationReconciliationTest {

    private final RequirementPublicationStore store = new InMemoryRequirementPublicationStore();
    private final RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);

    @Test
    void shouldPreparePublicationIntentOnceForSameOperationId() {
        RequirementPublication first = ledger.prepare(command("op-1", "task-1", "patch-aaa"));
        RequirementPublication second = ledger.prepare(command("op-1", "task-1", "patch-aaa"));

        assertEquals(RequirementPublicationStatus.PREPARED, first.status());
        assertEquals(first.id(), second.id());
        assertEquals(1, store.findByOperationId("op-1").orElseThrow().version());
        assertEquals(RequirementPublicationReplayDecision.ALLOW_PUSH, ledger.decideReplay("op-1"));
    }

    @Test
    void shouldReuseConfirmedPullRequestWithoutReplay() {
        ledger.prepare(command("op-2", "task-2", "patch-bbb"));
        ledger.markBranchConfirmed("op-2", "abc123");
        RequirementPublication confirmed = ledger.markPullRequestConfirmed(
                "op-2",
                "https://github.com/acme/repo/pull/9",
                9
        );

        assertEquals(RequirementPublicationStatus.PR_CONFIRMED, confirmed.status());
        assertEquals(RequirementPublicationReplayDecision.REUSE_PULL_REQUEST, ledger.decideReplay("op-2"));
        assertSame(confirmed, ledger.markPullRequestConfirmed(
                "op-2",
                "https://github.com/acme/repo/pull/9",
                9
        ));
    }

    @Test
    void shouldNotReplayWhenRemoteResultIsUnknown() {
        ledger.prepare(command("op-3", "task-3", "patch-ccc"));
        RequirementPublication unknown = ledger.markUnknownRemoteResult("op-3", "GitHub POST timed out");

        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT, unknown.status());
        assertEquals(RequirementPublicationReplayDecision.WAIT_RECONCILE, ledger.decideReplay("op-3"));
        assertThrows(IllegalStateException.class, () -> ledger.markBranchConfirmed("op-3", "deadbeef"));
        assertThrows(IllegalStateException.class, () -> ledger.markPullRequestConfirmed(
                "op-3",
                "https://github.com/acme/repo/pull/1",
                1
        ));
    }

    @Test
    void shouldReconcileUnknownPullRequestWhenRemoteHeadWasConfirmed() {
        ledger.prepare(command("op-4", "task-4", "patch-ddd"));
        ledger.markBranchConfirmed("op-4", "abc123");
        ledger.markUnknownRemoteResult("op-4", "GitHub POST timed out");

        RequirementPublication reconciled = ledger.reconcilePullRequestConfirmed(
                "op-4",
                "https://github.com/acme/repo/pull/12",
                12
        );

        assertEquals(RequirementPublicationStatus.PR_CONFIRMED, reconciled.status());
        assertEquals("https://github.com/acme/repo/pull/12", reconciled.pullRequestUrl());
        assertEquals(12, reconciled.pullRequestNumber());
        assertEquals(RequirementPublicationReplayDecision.REUSE_PULL_REQUEST, ledger.decideReplay("op-4"));
    }

    @Test
    void shouldRejectReconcileWhenUnknownWithoutRemoteHead() {
        ledger.prepare(command("op-5", "task-5", "patch-eee"));
        ledger.markUnknownRemoteResult("op-5", "GitHub push timed out");

        assertThrows(IllegalStateException.class, () -> ledger.reconcilePullRequestConfirmed(
                "op-5",
                "https://github.com/acme/repo/pull/13",
                13
        ));
        assertEquals(RequirementPublicationReplayDecision.WAIT_RECONCILE, ledger.decideReplay("op-5"));
    }

    @Test
    void shouldReconcileUnknownBranchWhenRemoteHeadAppears() {
        ledger.prepare(command("op-6", "task-6", "patch-fff"));
        ledger.markUnknownRemoteResult("op-6", "GitHub push timed out");

        RequirementPublication reconciled = ledger.reconcileBranchConfirmed("op-6", "cafebabe");

        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, reconciled.status());
        assertEquals("cafebabe", reconciled.remoteHeadSha());
        assertEquals(RequirementPublicationReplayDecision.ALLOW_CREATE_PULL_REQUEST, ledger.decideReplay("op-6"));
    }

    @Test
    void shouldResetUnknownToPreparedWhenRemoteBranchAbsent() {
        ledger.prepare(command("op-8", "task-8", "patch-hhh"));
        ledger.markUnknownRemoteResult("op-8", "GitHub push timed out");

        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                new RequirementPublicationReconcilePort() {
                    @Override
                    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                        return Optional.empty();
                    }

                    @Override
                    public RemoteBranchHead resolveRemoteBranchHead(BranchHeadQuery query) {
                        return new RemoteBranchHead.Absent();
                    }
                }
        );

        RequirementPublication reset = service.reconcileUnknown(
                "op-8", "task-8", "acme", "repo");

        assertEquals(RequirementPublicationStatus.PREPARED, reset.status());
        assertEquals("", reset.remoteHeadSha());
        assertEquals("", reset.lastError());
        assertEquals(0L, reset.nextReconcileAtEpochMillis());
        assertEquals(RequirementPublicationReplayDecision.ALLOW_PUSH, ledger.decideReplay("op-8"));
    }

    @Test
    void shouldKeepUnknownWhenBranchLookupUnavailable() {
        ledger.prepare(command("op-9", "task-9", "patch-iii"));
        ledger.markUnknownRemoteResult("op-9", "GitHub push timed out");

        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                new RequirementPublicationReconcilePort() {
                    @Override
                    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> findRemoteBranchHead(BranchHeadQuery query) {
                        return Optional.empty();
                    }
                }
        );

        RequirementPublication stillUnknown = service.reconcileUnknown(
                "op-9", "task-9", "acme", "repo");

        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT, stillUnknown.status());
        assertEquals(RequirementPublicationReplayDecision.WAIT_RECONCILE, ledger.decideReplay("op-9"));
    }

    @Test
    void shouldMarkNeedsHumanWhenRemoteConflictCannotAutoResolve() {
        ledger.prepare(command("op-10", "task-10", "patch-jjj"));
        ledger.markBranchConfirmed("op-10", "abc123");
        ledger.markUnknownRemoteResult("op-10", "GitHub POST timed out");

        RequirementPublication escalated = ledger.markNeedsHuman(
                "op-10", "open PR markers do not match operationId");

        assertEquals(RequirementPublicationStatus.NEEDS_HUMAN, escalated.status());
        assertEquals("open PR markers do not match operationId", escalated.lastError());
        assertEquals(RequirementPublicationReplayDecision.NEEDS_HUMAN, ledger.decideReplay("op-10"));
    }

    @Test
    void shouldEscalateUnknownToNeedsHumanWhenOpenPrMarkersConflict() {
        ledger.prepare(command("op-11", "task-11", "patch-kkk"));
        ledger.markBranchConfirmed("op-11", "abc123");
        ledger.markUnknownRemoteResult("op-11", "GitHub POST timed out");

        RequirementPublicationReconciliationService service = new RequirementPublicationReconciliationService(
                ledger,
                new RequirementPublicationReconcilePort() {
                    @Override
                    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<RequirementPublicationReconcilePort.ConflictingOpenPullRequest>
                    findConflictingOpenPullRequest(ReconcileQuery query) {
                        return Optional.of(new RequirementPublicationReconcilePort.ConflictingOpenPullRequest(
                                "https://github.com/acme/repo/pull/77",
                                77,
                                "open pull request exists for head/base but taskId/operationId markers do not match"
                        ));
                    }
                }
        );

        RequirementPublication escalated = service.reconcileUnknown(
                "op-11", "task-11", "acme", "repo");

        assertEquals(RequirementPublicationStatus.NEEDS_HUMAN, escalated.status());
        assertEquals(RequirementPublicationReplayDecision.NEEDS_HUMAN, ledger.decideReplay("op-11"));
    }

    @Test
    void shouldFindDueUnknownPublicationsAndDeferReconcileSchedule() {
        ledger.prepare(command("op-7", "task-7", "patch-ggg"));
        ledger.markUnknownRemoteResult("op-7", "GitHub push timed out");
        RequirementPublication due = store.findDueForReconcile(System.currentTimeMillis() + 60_000L, 10)
                .stream()
                .filter(item -> "op-7".equals(item.operationId()))
                .findFirst()
                .orElseThrow();
        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT, due.status());

        RequirementPublication deferred = ledger.deferReconcile("op-7", 60_000L);
        assertEquals(RequirementPublicationStatus.UNKNOWN_REMOTE_RESULT, deferred.status());
        assertTrue(deferred.nextReconcileAtEpochMillis() > due.nextReconcileAtEpochMillis());
        assertTrue(store.findDueForReconcile(System.currentTimeMillis(), 10).stream()
                .noneMatch(item -> "op-7".equals(item.operationId())));
    }

    private static RequirementPublicationPrepareCommand command(
            String operationId,
            String taskId,
            String patchSha
    ) {
        return new RequirementPublicationPrepareCommand(
                operationId,
                taskId,
                "stage-1",
                "main",
                "fix/" + taskId,
                patchSha
        );
    }
}
