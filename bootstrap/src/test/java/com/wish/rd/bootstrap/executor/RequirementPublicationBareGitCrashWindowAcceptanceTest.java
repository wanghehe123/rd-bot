package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.ProcessGitRepairWorkspaceRepository;
import com.wish.rd.engine.requirement.publication.RequirementPublicationLedger;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconcilePort;
import com.wish.rd.engine.requirement.publication.RequirementPublicationReconciliationService;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.impl.InMemoryRequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationPrepareCommand;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationReplayDecision;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.docker.model.RepairWorkspace;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * F-PUB-02 acceptance: a process can lose the push response after the remote accepts the
 * commit. Resuming workers must reconcile the immutable commit markers before either one
 * attempts another remote write.
 */
class RequirementPublicationBareGitCrashWindowAcceptanceTest {

    private static final String TASK_ID = "task-bare-git-fpub02";
    private static final String WORK_BRANCH = "requirement/task-bare-git-fpub02";
    private static final String OPERATION_ID = "sha256:bare-git-fpub02-operation";
    private static final String PATCH_SHA256 = "sha256:bare-git-fpub02-patch";

    @TempDir
    Path temporaryDirectory;

    @Test
    void timeoutAfterPushAndConcurrentReplaysLeaveOneRemoteCommitAndOneLedgerOperation() throws Exception {
        assumeTrue(gitAvailable(), "git CLI is required");
        Path seedRepository = temporaryDirectory.resolve("seed");
        Path remoteRepository = temporaryDirectory.resolve("remote.git");
        createSeedRepository(seedRepository, remoteRepository);

        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.getGit().setUserName("RD-Bot Test");
        properties.getGit().setUserEmail("rd-bot-test@example.local");
        ProcessGitRepairWorkspaceRepository repository = new ProcessGitRepairWorkspaceRepository(properties);
        RepairJobCommand command = publicationCommand(remoteRepository.toString());
        RepairWorkspace workspace = new RepairWorkspaceFactory(
                temporaryDirectory.resolve("workspaces"), "{\"type\":\"object\"}")
                .create(command);

        RecordingPublicationStore store = new RecordingPublicationStore();
        RequirementPublicationLedger ledger = new RequirementPublicationLedger(store);
        ledger.prepare(publicationIntent());

        repository.prepare(command, workspace);
        Files.createDirectories(workspace.repoDirectory().resolve("service/src"));
        Files.writeString(
                workspace.repoDirectory().resolve("service/src/publication.ts"),
                "export const publicationRecovered = true;\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.files().resultJson(),
                "{\"changedFiles\":[\"service/src/publication.ts\"]}",
                StandardCharsets.UTF_8
        );

        IOException timeout = assertThrows(IOException.class, () -> {
            repository.publish(command, workspace);
            throw new IOException("simulated timeout after remote push accepted the commit");
        });
        ledger.markUnknownRemoteResult(OPERATION_ID, timeout.getMessage());

        RequirementPublicationReconciliationService reconciliation =
                new RequirementPublicationReconciliationService(
                        ledger,
                        localBareRemoteReconciler(remoteRepository)
                );
        RequirementPublication firstReplay;
        RequirementPublication secondReplay;
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<RequirementPublication> first = workers.submit(
                    () -> replayAfterCrash(ledger, reconciliation, ready, start));
            Future<RequirementPublication> second = workers.submit(
                    () -> replayAfterCrash(ledger, reconciliation, ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS), "both replay workers must be ready");
            start.countDown();
            firstReplay = first.get(20, TimeUnit.SECONDS);
            secondReplay = second.get(20, TimeUnit.SECONDS);
        }

        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, firstReplay.status());
        assertEquals(RequirementPublicationStatus.BRANCH_CONFIRMED, secondReplay.status());
        assertEquals(RequirementPublicationReplayDecision.ALLOW_CREATE_PULL_REQUEST,
                ledger.decideReplay(OPERATION_ID));
        assertEquals(1, store.createdOperationIds().size(),
                "concurrent replay must reuse the one durable publication operation");
        assertEquals(Set.of(OPERATION_ID), store.createdOperationIds());
        assertEquals("1", git(
                null,
                "--git-dir", remoteRepository.toString(),
                "rev-list", "--count", "refs/heads/main..refs/heads/" + WORK_BRANCH
        ).stdout().strip(), "the timeout/replay window must leave exactly one remote publication commit");

        String remoteCommitMessage = git(
                null,
                "--git-dir", remoteRepository.toString(),
                "log", "-1", "--format=%B", "refs/heads/" + WORK_BRANCH
        ).stdout();
        assertTrue(remoteCommitMessage.contains("rd-operation-id: " + OPERATION_ID));
        assertTrue(remoteCommitMessage.contains("rd-candidate-patch-sha256: " + PATCH_SHA256));
    }

    private RequirementPublication replayAfterCrash(
            RequirementPublicationLedger ledger,
            RequirementPublicationReconciliationService reconciliation,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ledger.prepare(publicationIntent());
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS), "replay start signal must arrive");
        return reconciliation.reconcileUnknown(OPERATION_ID, TASK_ID, "local", "bare-remote");
    }

    private RequirementPublicationReconcilePort localBareRemoteReconciler(Path remoteRepository) {
        return new RequirementPublicationReconcilePort() {
            @Override
            public Optional<MatchedOpenPullRequest> findMatchingOpenPullRequest(ReconcileQuery query) {
                return Optional.empty();
            }

            @Override
            public RemoteBranchHead resolveRemoteBranchHead(BranchHeadQuery query) {
                try {
                    String ref = "refs/heads/" + query.workBranch();
                    String commitSha = git(
                            null,
                            "--git-dir", remoteRepository.toString(),
                            "rev-parse", "--verify", ref
                    ).stdout().strip();
                    String message = git(
                            null,
                            "--git-dir", remoteRepository.toString(),
                            "log", "-1", "--format=%B", ref
                    ).stdout();
                    return new RemoteBranchHead.Present(
                            commitSha,
                            marker(message, "rd-operation-id"),
                            marker(message, "rd-candidate-patch-sha256")
                    );
                } catch (Exception exception) {
                    return new RemoteBranchHead.Unavailable();
                }
            }
        };
    }

    private static String marker(String message, String key) {
        String prefix = key + ":";
        return message.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).strip())
                .findFirst()
                .orElse("");
    }

    private static RequirementPublicationPrepareCommand publicationIntent() {
        return new RequirementPublicationPrepareCommand(
                OPERATION_ID,
                TASK_ID,
                "stage-bare-git-fpub02",
                "main",
                WORK_BRANCH,
                PATCH_SHA256
        );
    }

    private static RepairJobCommand publicationCommand(String repositoryUrl) {
        return new RepairJobCommand(
                "bare-git-fpub02",
                TASK_ID + "-publish",
                TASK_ID,
                "bare Git crash-window acceptance",
                "publish reviewed candidate",
                repositoryUrl,
                "local",
                "bare-remote",
                "main",
                WORK_BRANCH,
                Map.of(),
                Map.of(
                        "repositoryDeliveryMode", "PUBLISH",
                        "requirementPublicationTaskId", TASK_ID,
                        "requirementPublicationOperationId", OPERATION_ID,
                        "requirementPublicationCandidatePatchSha256", PATCH_SHA256
                )
        );
    }

    private static void createSeedRepository(Path seedRepository, Path remoteRepository) throws Exception {
        Files.createDirectories(seedRepository);
        git(seedRepository, "init");
        git(seedRepository, "branch", "-M", "main");
        git(seedRepository, "config", "user.name", "Seed User");
        git(seedRepository, "config", "user.email", "seed@example.local");
        Files.writeString(seedRepository.resolve("README.md"), "# bare Git acceptance\n", StandardCharsets.UTF_8);
        git(seedRepository, "add", "README.md");
        git(seedRepository, "commit", "-m", "initial");
        git(null, "clone", "--bare", seedRepository.toString(), remoteRepository.toString());
    }

    private static boolean gitAvailable() {
        try {
            return git(null, "--version").exitCode() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static CommandResult git(Path workingDirectory, String... args) throws Exception {
        String[] argv = new String[args.length + 1];
        argv[0] = "git";
        System.arraycopy(args, 0, argv, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("git command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git command failed: " + output);
        }
        return new CommandResult(process.exitValue(), output);
    }

    private record CommandResult(int exitCode, String stdout) {
    }

    private static final class RecordingPublicationStore implements RequirementPublicationStore {

        private final InMemoryRequirementPublicationStore delegate = new InMemoryRequirementPublicationStore();
        private final Set<String> createdOperationIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public RequirementPublication insertPrepared(RequirementPublication publication) {
            RequirementPublication saved = delegate.insertPrepared(publication);
            if (saved.id().equals(publication.id())) {
                createdOperationIds.add(saved.operationId());
            }
            return saved;
        }

        @Override
        public Optional<RequirementPublication> findByOperationId(String operationId) {
            return delegate.findByOperationId(operationId);
        }

        @Override
        public Optional<RequirementPublication> findLatestByTaskId(String taskId) {
            return delegate.findLatestByTaskId(taskId);
        }

        @Override
        public List<RequirementPublication> findDueForReconcile(long beforeEpochMillis, int limit) {
            return delegate.findDueForReconcile(beforeEpochMillis, limit);
        }

        @Override
        public RequirementPublication save(RequirementPublication publication) {
            return delegate.save(publication);
        }

        private Set<String> createdOperationIds() {
            return Set.copyOf(createdOperationIds);
        }
    }
}
