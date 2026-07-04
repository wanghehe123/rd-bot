package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.docker.RepairWorkspace;
import com.wish.rd.exec.repair.docker.RepairWorkspaceFactory;
import com.wish.rd.exec.repair.docker.RepairWorkspaceRepositoryPort;
import com.wish.rd.exec.repair.execution.RepairJobCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProcessGitRepairWorkspaceRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCloneCommitAndPushWorkBranch() throws Exception {
        assumeTrue(gitAvailable(), "git CLI is required");
        Path seedRepository = temporaryDirectory.resolve("seed");
        Path remoteRepository = temporaryDirectory.resolve("remote.git");
        createSeedRepository(seedRepository, remoteRepository);

        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(
                temporaryDirectory.resolve("workspaces"),
                "{\"type\":\"object\"}"
        );
        RepairJobCommand command = command(remoteRepository.toString());
        RepairWorkspace workspace = factory.create(command);
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.getGit().setUserName("RD-Bot Test");
        properties.getGit().setUserEmail("rd-bot-test@example.local");
        ProcessGitRepairWorkspaceRepository repository = new ProcessGitRepairWorkspaceRepository(properties);

        RepairWorkspaceRepositoryPort.RepositoryOperationResult prepareResult =
                repository.prepare(command, workspace);
        Files.createDirectories(workspace.repoDirectory().resolve("client/src"));
        Files.writeString(
                workspace.repoDirectory().resolve("client/src/api.ts"),
                "export const fixed = true;\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.files().resultJson(),
                """
                        {
                          "changedFiles": ["client/src/api.ts"]
                        }
                        """,
                StandardCharsets.UTF_8
        );
        RepairWorkspaceRepositoryPort.RepositoryOperationResult publishResult =
                repository.publish(command, workspace);

        assertEquals("true", prepareResult.metadataJson().get("prepared"));
        assertEquals("true", publishResult.metadataJson().get("pushed"));
        assertEquals("repair/task-1001", publishResult.metadataJson().get("workBranch"));
        assertFalse(publishResult.metadataJson().get("commitSha").isBlank());
        assertTrue(publishResult.metadataJson().get("changedFiles").contains("client/src/api.ts"));
        assertFalse(git(null, "--git-dir", remoteRepository.toString(), "rev-parse",
                "refs/heads/repair/task-1001").stdout().isBlank());
    }

    @Test
    void shouldPrepareFromExistingRemoteWorkBranchWhenAvailable() throws Exception {
        assumeTrue(gitAvailable(), "git CLI is required");
        Path seedRepository = temporaryDirectory.resolve("seed");
        Path remoteRepository = temporaryDirectory.resolve("remote.git");
        createSeedRepository(seedRepository, remoteRepository);
        git(seedRepository, "checkout", "-b", "repair/task-1001");
        Files.createDirectories(seedRepository.resolve("src"));
        Files.writeString(
                seedRepository.resolve("src/only-on-work-branch.txt"),
                "work branch content\n",
                StandardCharsets.UTF_8
        );
        git(seedRepository, "add", "src/only-on-work-branch.txt");
        git(seedRepository, "commit", "-m", "work branch");
        git(seedRepository, "push", remoteRepository.toString(), "repair/task-1001");

        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(
                temporaryDirectory.resolve("workspaces"),
                "{\"type\":\"object\"}"
        );
        RepairJobCommand command = command(remoteRepository.toString());
        RepairWorkspace workspace = factory.create(command);
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.getGit().setUserName("RD-Bot Test");
        properties.getGit().setUserEmail("rd-bot-test@example.local");
        ProcessGitRepairWorkspaceRepository repository = new ProcessGitRepairWorkspaceRepository(properties);

        RepairWorkspaceRepositoryPort.RepositoryOperationResult prepareResult =
                repository.prepare(command, workspace);

        assertEquals("origin-work-branch", prepareResult.metadataJson().get("checkoutSource"));
        assertTrue(Files.exists(workspace.repoDirectory().resolve("src/only-on-work-branch.txt")));
    }

    @Test
    void shouldPublishOnlyStructuredChangedFilesAndIgnoreExecutionArtifacts() throws Exception {
        assumeTrue(gitAvailable(), "git CLI is required");
        Path seedRepository = temporaryDirectory.resolve("seed");
        Path remoteRepository = temporaryDirectory.resolve("remote.git");
        createSeedRepository(seedRepository, remoteRepository);

        RepairWorkspaceFactory factory = new RepairWorkspaceFactory(
                temporaryDirectory.resolve("workspaces"),
                "{\"type\":\"object\"}"
        );
        RepairJobCommand command = command(remoteRepository.toString());
        RepairWorkspace workspace = factory.create(command);
        DockerExecutorProperties properties = new DockerExecutorProperties();
        properties.getGit().setUserName("RD-Bot Test");
        properties.getGit().setUserEmail("rd-bot-test@example.local");
        ProcessGitRepairWorkspaceRepository repository = new ProcessGitRepairWorkspaceRepository(properties);

        repository.prepare(command, workspace);
        Files.createDirectories(workspace.repoDirectory().resolve("client/src"));
        Files.writeString(
                workspace.repoDirectory().resolve("client/src/api.ts"),
                "export const fixed = true;\n",
                StandardCharsets.UTF_8
        );
        Files.createDirectories(workspace.repoDirectory().resolve("client/node_modules/pkg"));
        Files.writeString(
                workspace.repoDirectory().resolve("client/node_modules/pkg/index.js"),
                "module.exports = true;\n",
                StandardCharsets.UTF_8
        );
        Files.createDirectories(workspace.repoDirectory().resolve("client/dist"));
        Files.writeString(
                workspace.repoDirectory().resolve("client/dist/app.js"),
                "console.log('built');\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.repoDirectory().resolve("client/package-lock.json"),
                "{}\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.files().resultJson(),
                """
                        {
                          "changedFiles": ["client/src/api.ts"]
                        }
                        """,
                StandardCharsets.UTF_8
        );

        RepairWorkspaceRepositoryPort.RepositoryOperationResult publishResult =
                repository.publish(command, workspace);

        String remoteTree = git(null, "--git-dir", remoteRepository.toString(), "ls-tree", "-r",
                "--name-only", "refs/heads/repair/task-1001").stdout();
        assertEquals("client/src/api.ts", publishResult.metadataJson().get("changedFiles"));
        assertTrue(remoteTree.contains("client/src/api.ts"));
        assertFalse(remoteTree.contains("client/node_modules/pkg/index.js"));
        assertFalse(remoteTree.contains("client/dist/app.js"));
        assertFalse(remoteTree.contains("client/package-lock.json"));
    }

    private static boolean gitAvailable() {
        try {
            return git(null, "--version").exitCode() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static void createSeedRepository(Path seedRepository, Path remoteRepository) throws Exception {
        Files.createDirectories(seedRepository);
        git(seedRepository, "init");
        git(seedRepository, "branch", "-M", "main");
        git(seedRepository, "config", "user.name", "Seed User");
        git(seedRepository, "config", "user.email", "seed@example.local");
        Files.writeString(seedRepository.resolve("README.md"), "# waimai\n", StandardCharsets.UTF_8);
        git(seedRepository, "add", "README.md");
        git(seedRepository, "commit", "-m", "initial");
        git(null, "clone", "--bare", seedRepository.toString(), remoteRepository.toString());
    }

    private static RepairJobCommand command(String repositoryUrl) {
        return new RepairJobCommand(
                "repair-1001",
                "task-1001",
                "FS-1001",
                "外卖下单接口返回 500",
                "Fix waimai order API.",
                repositoryUrl,
                "local",
                "waimai",
                "main",
                "repair/task-1001",
                Map.of("ragSummary", "waimai order context"),
                Map.of()
        );
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
}
