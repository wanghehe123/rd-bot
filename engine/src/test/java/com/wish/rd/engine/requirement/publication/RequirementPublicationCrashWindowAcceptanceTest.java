package com.wish.rd.engine.requirement.publication;

import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.BranchHeadQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.MatchedOpenPullRequest;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.ReconcileQuery;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort.RemoteBranchHead;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationReplayDecision;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named crash-window acceptance samples for WP-1 (F-PUB-* in the fault matrix).
 * These are deterministic ledger/reconcile scenarios, not live GitHub process kills.
 */
class RequirementPublicationCrashWindowAcceptanceTest {

    private final RequirementPublicationStore store = new InMemoryRequirementPublicationStore();
    private final RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);

    @Test
    @DisplayName("F-PUB-02: crash after successful push → resume skips push, allows PR create")
    void fPub02CrashAfterPushResumeSkipsPush() {
        ledger.prepare(command("op-fpub02", "task-fpub02", "patch-fpub02"));
        ledger.markBranchConfirmed("op-fpub02", "push-sha-1");

        assertEquals(
                RequirementPublicationReplayDecision.ALLOW_CREATE_PULL_REQUEST,
                ledger.decideReplay("op-fpub02")
        );
        assertEquals(
                RequirementPublicationStatus.BRANCH_CONFIRMED,
                store.findByOperationId("op-fpub02").orElseThrow().status()
        );
    }

    @Test
    @DisplayName("F-PUB-03: GitHub 201 then DB miss → reconcile reuses open PR markers")
    void fPub03Github201ThenDbMissReconcileReusesOpenPr() {
        ledger.prepare(command("op-fpub03", "task-fpub03", "patch-fpub03"));
        ledger.markBranchConfirmed("op-fpub03", "head-1");
        ledger.markUnknownRemoteResult("op-fpub03", "DB write failed after GitHub 201");

        RequirementPublication reconciled = ledger.reconcilePullRequestConfirmed(
                "op-fpub03",
                "https://github.com/acme/repo/pull/42",
                42
        );

        assertEquals(RequirementPublicationStatus.PR_CONFIRMED, reconciled.status());
        assertEquals(42, reconciled.pullRequestNumber());
        assertEquals(
                RequirementPublicationReplayDecision.REUSE_PULL_REQUEST,
                ledger.decideReplay("op-fpub03")
        );
    }

    @Test
    @DisplayName("F-PUB-05: push timeout + Absent branch → UNKNOWN resets to PREPARED for safe retry")
    void fPub05PushTimeoutAbsentBranchResetsPrepared() {
        ledger.prepare(command("op-fpub05", "task-fpub05", "patch-fpub05"));
        ledger.markUnknownRemoteResult("op-fpub05", "git push timed out");

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
                "op-fpub05", "task-fpub05", "acme", "repo");

        assertEquals(RequirementPublicationStatus.PREPARED, reset.status());
        assertEquals(RequirementPublicationReplayDecision.ALLOW_PUSH, ledger.decideReplay("op-fpub05"));
        assertTrue(reset.remoteHeadSha().isBlank());
    }

    @Test
    @DisplayName("F-PUB-05b: push timeout + Present head → UNKNOWN reconciles to BRANCH_CONFIRMED")
    void fPub05PushTimeoutPresentHeadConfirmsBranch() {
        ledger.prepare(command("op-fpub05b", "task-fpub05b", "patch-fpub05b"));
        ledger.markUnknownRemoteResult("op-fpub05b", "git push timed out");

        RequirementPublication confirmed = ledger.reconcileBranchConfirmed("op-fpub05b", "present-sha");

        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, confirmed.status());
        assertEquals("present-sha", confirmed.remoteHeadSha());
        assertEquals(
                RequirementPublicationReplayDecision.ALLOW_CREATE_PULL_REQUEST,
                ledger.decideReplay("op-fpub05b")
        );
    }

    private static RequirementPublicationPrepareCommand command(
            String operationId,
            String taskId,
            String patchSha
    ) {
        return new RequirementPublicationPrepareCommand(
                operationId,
                taskId,
                "stage-" + taskId,
                "main",
                "requirement/" + taskId,
                patchSha
        );
    }
}
