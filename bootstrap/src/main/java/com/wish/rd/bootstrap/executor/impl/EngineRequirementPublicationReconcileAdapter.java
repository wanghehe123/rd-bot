package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.BranchHeadResult;
import com.wish.rd.exec.repair.code.model.FindBranchHeadCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves UNKNOWN_REMOTE_RESULT via remote branch tip and/or open PR marker match.
 */
public final class EngineRequirementPublicationReconcileAdapter
        implements RequirementPublicationReconcilePort {

    private final CodePlatformPort codePlatform;

    public EngineRequirementPublicationReconcileAdapter(CodePlatformPort codePlatform) {
        this.codePlatform = Objects.requireNonNull(codePlatform, "codePlatform must not be null");
    }

    @Override
    public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
        Optional<PullRequestResult> open = findOpenPullRequest(query);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        PullRequestResult pull = open.get();
        String body = pull.metadata() == null ? "" : safe(pull.metadata().get("body"));
        if (!markersMatch(body, query.taskId(), query.operationId())) {
            return Optional.empty();
        }
        int number = parsePullRequestNumber(pull.pullRequestNumber());
        if (pull.pullRequestUrl().isBlank() || number <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MatchedOpenPullRequest(pull.pullRequestUrl(), number));
    }

    @Override
    public Optional<ConflictingOpenPullRequest> findConflictingOpenPullRequest(ReconcileQuery query) {
        Optional<PullRequestResult> open = findOpenPullRequest(query);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        PullRequestResult pull = open.get();
        String body = pull.metadata() == null ? "" : safe(pull.metadata().get("body"));
        if (markersMatch(body, query.taskId(), query.operationId())) {
            return Optional.empty();
        }
        int number = parsePullRequestNumber(pull.pullRequestNumber());
        return Optional.of(new ConflictingOpenPullRequest(
                pull.pullRequestUrl(),
                number,
                "open pull request exists for head/base but taskId/operationId markers do not match"
        ));
    }

    private Optional<PullRequestResult> findOpenPullRequest(ReconcileQuery query) {
        if (query == null
                || query.repoOwner().isBlank()
                || query.repoName().isBlank()
                || query.baseBranch().isBlank()
                || query.workBranch().isBlank()
                || query.taskId().isBlank()) {
            return Optional.empty();
        }
        return codePlatform.findOpenPullRequest(new FindOpenPullRequestCommand(
                query.repoOwner(),
                query.repoName(),
                query.baseBranch(),
                query.workBranch()
        ));
    }

    @Override
    public Optional<String> findRemoteBranchHead(BranchHeadQuery query) {
        return switch (resolveRemoteBranchHead(query)) {
            case RemoteBranchHead.Present present -> Optional.of(present.commitSha());
            case RemoteBranchHead.Absent ignored -> Optional.empty();
            case RemoteBranchHead.Unavailable ignored -> Optional.empty();
        };
    }

    @Override
    public RemoteBranchHead resolveRemoteBranchHead(BranchHeadQuery query) {
        if (query == null
                || query.repoOwner().isBlank()
                || query.repoName().isBlank()
                || query.workBranch().isBlank()) {
            return new RemoteBranchHead.Unavailable();
        }
        Optional<BranchHeadResult> branchHead = codePlatform.findBranchHead(new FindBranchHeadCommand(
                query.repoOwner(),
                query.repoName(),
                query.workBranch()
        )).filter(head -> head.commitSha() != null && !head.commitSha().isBlank());
        // CodePlatform empty means confirmed absence (e.g. GitHub 404); transport errors throw.
        return branchHead.<RemoteBranchHead>map(head -> new RemoteBranchHead.Present(
                        head.commitSha(),
                        metadata(head, "operationId"),
                        metadata(head, "candidatePatchSha256")
                ))
                .orElseGet(RemoteBranchHead.Absent::new);
    }

    private static String metadata(BranchHeadResult head, String key) {
        if (head == null || head.metadata() == null) {
            return "";
        }
        return safe(head.metadata().get(key));
    }

    private static boolean markersMatch(String prBody, String taskId, String operationId) {
        String body = safe(prBody);
        if (taskId.isBlank() || !taskId.equals(markerValue(body, "taskId"))) {
            return false;
        }
        if (operationId.isBlank()) {
            return true;
        }
        return operationId.equals(markerValue(body, "operationId"));
    }

    private static String markerValue(String body, String key) {
        String prefix = "- " + key + ":";
        for (String line : safe(body).lines().toList()) {
            String normalized = line.strip();
            if (!normalized.startsWith(prefix)) continue;
            String value = normalized.substring(prefix.length()).strip();
            if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
                value = value.substring(1, value.length() - 1).strip();
            }
            return value;
        }
        return "";
    }

    private static int parsePullRequestNumber(String value) {
        try {
            return Integer.parseInt(safe(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
