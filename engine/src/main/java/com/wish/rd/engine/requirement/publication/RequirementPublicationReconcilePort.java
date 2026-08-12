package com.wish.rd.engine.requirement.publication;

import java.util.Optional;

/**
 * Looks up remote publication state for UNKNOWN_REMOTE_RESULT recovery.
 * Host adapters typically wrap {@code CodePlatformPort} branch-head / open-PR lookups.
 */
public interface RequirementPublicationReconcilePort {

    /**
     * Finds an open PR for head/base whose body markers match the operation.
     *
     * @param query lookup parameters
     * @return matching PR when present
     */
    Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query);

    /**
     * Finds an open PR for head/base whose markers conflict with this operation.
     *
     * <p>Default empty preserves legacy adapters. Production adapters should return a
     * conflict when an open PR exists but taskId/operationId markers do not match.
     *
     * @param query lookup parameters
     * @return conflicting PR when present
     */
    default Optional<ConflictingOpenPullRequest> findConflictingOpenPullRequest(ReconcileQuery query) {
        return Optional.empty();
    }

    /**
     * Finds the tip SHA of a remote work branch when it exists.
     *
     * <p>Default empty keeps existing single-method lambdas/tests working until
     * adapters implement push-timeout branch reconciliation.
     *
     * @param query branch lookup parameters
     * @return tip SHA when present
     */
    default Optional<String> findRemoteBranchHead(BranchHeadQuery query) {
        return Optional.empty();
    }

    /**
     * Classifies remote branch existence for UNKNOWN recovery.
     *
     * <p>Default maps a non-blank {@link #findRemoteBranchHead} result to
     * {@link RemoteBranchHead.Present}, and empty to {@link RemoteBranchHead.Unavailable}
     * so legacy stubs do not incorrectly reset UNKNOWN→PREPARED. Production adapters
     * that can prove absence (e.g. GitHub 404) should return {@link RemoteBranchHead.Absent}.
     *
     * @param query branch lookup parameters
     * @return present tip, confirmed absence, or unavailable lookup
     */
    default RemoteBranchHead resolveRemoteBranchHead(BranchHeadQuery query) {
        return findRemoteBranchHead(query)
                .filter(sha -> sha != null && !sha.isBlank())
                .map(sha -> (RemoteBranchHead) new RemoteBranchHead.Present(sha.strip()))
                .orElseGet(RemoteBranchHead.Unavailable::new);
    }

    /**
     * Remote work-branch tip classification used by UNKNOWN reconciliation.
     */
    sealed interface RemoteBranchHead {

        /**
         * Branch tip observed together with the immutable markers carried by its
         * commit message. A SHA by itself is not evidence that this publication's
         * candidate patch owns the branch.
         *
         * @param commitSha              tip SHA
         * @param operationId            {@code rd-operation-id} commit marker
         * @param candidatePatchSha256   {@code rd-candidate-patch-sha256} commit marker
         */
        record Present(String commitSha, String operationId, String candidatePatchSha256)
                implements RemoteBranchHead {

            public Present {
                commitSha = safe(commitSha);
                operationId = safe(operationId);
                candidatePatchSha256 = safe(candidatePatchSha256);
            }

            /**
             * Backward-compatible construction for legacy adapters. The missing
             * marker values intentionally do not satisfy {@link #matches}.
             *
             * @param commitSha tip SHA
             */
            public Present(String commitSha) {
                this(commitSha, "", "");
            }

            /**
             * Verifies that this branch was created for the exact publication
             * operation and candidate patch currently being reconciled.
             *
             * @param expectedOperationId expected immutable operation id
             * @param expectedCandidatePatchSha256 expected candidate patch hash
             * @return {@code true} only when both markers match exactly
             */
            public boolean matches(String expectedOperationId, String expectedCandidatePatchSha256) {
                return !operationId.isBlank()
                        && !candidatePatchSha256.isBlank()
                        && operationId.equals(safe(expectedOperationId))
                        && candidatePatchSha256.equals(safe(expectedCandidatePatchSha256));
            }

            private static String safe(String value) {
                return value == null ? "" : value.strip();
            }
        }

        /**
         * Remote confirms the work branch does not exist (push likely never landed).
         */
        record Absent() implements RemoteBranchHead {
        }

        /**
         * Lookup could not prove presence or absence; keep waiting.
         */
        record Unavailable() implements RemoteBranchHead {
        }
    }

    /**
     * Remote open-PR match used to advance UNKNOWN → PR_CONFIRMED.
     *
     * @param pullRequestUrl    PR url
     * @param pullRequestNumber PR number
     */
    record MatchedOpenPullRequest(String pullRequestUrl, int pullRequestNumber) {

        public MatchedOpenPullRequest {
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl.strip();
            pullRequestNumber = Math.max(0, pullRequestNumber);
        }
    }

    /**
     * Open PR exists for head/base but markers conflict with this operation.
     *
     * @param pullRequestUrl    conflicting PR url
     * @param pullRequestNumber conflicting PR number
     * @param reason            diagnostic reason
     */
    record ConflictingOpenPullRequest(
            String pullRequestUrl,
            int pullRequestNumber,
            String reason
    ) {

        public ConflictingOpenPullRequest {
            pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl.strip();
            pullRequestNumber = Math.max(0, pullRequestNumber);
            reason = reason == null || reason.isBlank()
                    ? "open pull request exists for head/base but taskId/operationId markers do not match"
                    : reason.strip();
        }
    }

    /**
     * Query for a marker-matched open PR.
     *
     * @param repoOwner   repository owner
     * @param repoName    repository name
     * @param baseBranch  target branch
     * @param workBranch  head branch
     * @param taskId      RD task id marker
     * @param operationId publication operation id marker
     */
    record ReconcileQuery(
            String repoOwner,
            String repoName,
            String baseBranch,
            String workBranch,
            String taskId,
            String operationId
    ) {

        public ReconcileQuery {
            repoOwner = safe(repoOwner);
            repoName = safe(repoName);
            baseBranch = safe(baseBranch);
            workBranch = safe(workBranch);
            taskId = safe(taskId);
            operationId = safe(operationId);
        }

        private static String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }

    /**
     * Query for a remote work-branch tip.
     *
     * @param repoOwner  repository owner
     * @param repoName   repository name
     * @param workBranch head branch
     */
    record BranchHeadQuery(
            String repoOwner,
            String repoName,
            String workBranch
    ) {

        public BranchHeadQuery {
            repoOwner = repoOwner == null ? "" : repoOwner.strip();
            repoName = repoName == null ? "" : repoName.strip();
            workBranch = workBranch == null ? "" : workBranch.strip();
        }
    }
}
