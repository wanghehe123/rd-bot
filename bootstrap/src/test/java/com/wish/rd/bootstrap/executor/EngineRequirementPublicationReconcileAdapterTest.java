package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineRequirementPublicationReconcileAdapter;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.exec.repair.code.CodePlatformPort;
import com.wish.rd.exec.repair.code.model.BranchHeadResult;
import com.wish.rd.exec.repair.code.model.CreatePullRequestCommand;
import com.wish.rd.exec.repair.code.model.FindBranchHeadCommand;
import com.wish.rd.exec.repair.code.model.FindOpenPullRequestCommand;
import com.wish.rd.exec.repair.code.model.PullRequestResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRequirementPublicationReconcileAdapterTest {

    @Test
    void shouldMatchOpenPullRequestWhenMarkersAlign() {
        AtomicReference<FindOpenPullRequestCommand> seen = new AtomicReference<>();
        CodePlatformPort platform = new CodePlatformPort() {
            @Override
            public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
                throw new AssertionError("create must not run during reconcile");
            }

            @Override
            public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
                seen.set(command);
                return Optional.of(new PullRequestResult(
                        "https://github.com/acme/order/pull/55",
                        "55",
                        Map.of("body", "- taskId: task-1\n- operationId: op-55")
                ));
            }
        };
        EngineRequirementPublicationReconcileAdapter adapter =
                new EngineRequirementPublicationReconcileAdapter(platform);

        Optional<RequirementPublicationReconcilePort.MatchedOpenPullRequest> match =
                adapter.findMatchingOpenPullRequest(new RequirementPublicationReconcilePort.ReconcileQuery(
                        "acme", "order", "main", "requirement/task-1", "task-1", "op-55"));

        assertTrue(match.isPresent());
        assertEquals("https://github.com/acme/order/pull/55", match.get().pullRequestUrl());
        assertEquals(55, match.get().pullRequestNumber());
        assertEquals("acme", seen.get().repoOwner());
        assertEquals("requirement/task-1", seen.get().workBranch());
    }

    @Test
    void shouldIgnoreOpenPullRequestWhenMarkersMismatch() {
        CodePlatformPort platform = new CodePlatformPort() {
            @Override
            public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
                throw new AssertionError("create must not run during reconcile");
            }

            @Override
            public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
                return Optional.of(new PullRequestResult(
                        "https://github.com/acme/order/pull/55",
                        "55",
                        Map.of("body", "- taskId: other\n- operationId: other-op")
                ));
            }
        };
        EngineRequirementPublicationReconcileAdapter adapter =
                new EngineRequirementPublicationReconcileAdapter(platform);

        Optional<RequirementPublicationReconcilePort.MatchedOpenPullRequest> match =
                adapter.findMatchingOpenPullRequest(new RequirementPublicationReconcilePort.ReconcileQuery(
                        "acme", "order", "main", "requirement/task-1", "task-1", "op-55"));

        assertTrue(match.isEmpty());
    }

    @Test
    void shouldReturnRemoteBranchHeadSha() {
        CodePlatformPort platform = new CodePlatformPort() {
            @Override
            public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
                throw new AssertionError("create must not run during reconcile");
            }

            @Override
            public Optional<BranchHeadResult> findBranchHead(FindBranchHeadCommand command) {
                assertEquals("requirement/task-1", command.branch());
                return Optional.of(new BranchHeadResult("cafebabe", Map.of("provider", "recording")));
            }
        };
        EngineRequirementPublicationReconcileAdapter adapter =
                new EngineRequirementPublicationReconcileAdapter(platform);

        Optional<String> head = adapter.findRemoteBranchHead(
                new RequirementPublicationReconcilePort.BranchHeadQuery(
                        "acme", "order", "requirement/task-1"));

        assertTrue(head.isPresent());
        assertEquals("cafebabe", head.get());
        assertTrue(adapter.resolveRemoteBranchHead(
                new RequirementPublicationReconcilePort.BranchHeadQuery(
                        "acme", "order", "requirement/task-1"))
                instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Present);
    }

    @Test
    void shouldClassifyMissingBranchAsAbsent() {
        CodePlatformPort platform = new CodePlatformPort() {
            @Override
            public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
                throw new AssertionError("create must not run during reconcile");
            }

            @Override
            public Optional<BranchHeadResult> findBranchHead(FindBranchHeadCommand command) {
                return Optional.empty();
            }
        };
        EngineRequirementPublicationReconcileAdapter adapter =
                new EngineRequirementPublicationReconcileAdapter(platform);

        assertTrue(adapter.resolveRemoteBranchHead(
                new RequirementPublicationReconcilePort.BranchHeadQuery(
                        "acme", "order", "requirement/missing"))
                instanceof RequirementPublicationReconcilePort.RemoteBranchHead.Absent);
    }

    @Test
    void shouldExposeConflictingOpenPullRequestWhenMarkersMismatch() {
        CodePlatformPort platform = new CodePlatformPort() {
            @Override
            public PullRequestResult createPullRequest(CreatePullRequestCommand command) {
                throw new AssertionError("create must not run during reconcile");
            }

            @Override
            public Optional<PullRequestResult> findOpenPullRequest(FindOpenPullRequestCommand command) {
                return Optional.of(new PullRequestResult(
                        "https://github.com/acme/order/pull/55",
                        "55",
                        Map.of("body", "- taskId: other\n- operationId: other-op")
                ));
            }
        };
        EngineRequirementPublicationReconcileAdapter adapter =
                new EngineRequirementPublicationReconcileAdapter(platform);

        Optional<RequirementPublicationReconcilePort.ConflictingOpenPullRequest> conflict =
                adapter.findConflictingOpenPullRequest(new RequirementPublicationReconcilePort.ReconcileQuery(
                        "acme", "order", "main", "requirement/task-1", "task-1", "op-55"));

        assertTrue(conflict.isPresent());
        assertEquals("https://github.com/acme/order/pull/55", conflict.get().pullRequestUrl());
        assertTrue(conflict.get().reason().contains("markers do not match"));
    }
}
