package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.rag.runtime.model.CreateRequirementTaskCommand;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerificationChangeSetResolverTest {

    @TempDir
    Path workspace;

    @Test
    void resolveReturnsPathsFromSampleUnifiedDiff() throws Exception {
        Files.writeString(workspace.resolve("candidate-patch.diff"), """
                diff --git a/src/App.tsx b/src/App.tsx
                index 1111111..2222222 100644
                --- a/src/App.tsx
                +++ b/src/App.tsx
                @@ -1 +1 @@
                -old
                +new
                diff --git a/docs/guide.md b/docs/guide.md
                new file mode 100644
                --- /dev/null
                +++ b/docs/guide.md
                @@ -0,0 +1 @@
                +hello
                """);

        List<String> changed = new GitHostVerificationChangeSetResolver().resolve(task(), codingStage(), workspace);

        assertEquals(List.of("src/App.tsx", "docs/guide.md"), changed);
    }

    @Test
    void emptyOrUnreadableWorkspaceIsUndeterminableEmptyList() {
        GitHostVerificationChangeSetResolver resolver = new GitHostVerificationChangeSetResolver();

        assertEquals(List.of(), resolver.resolve(task(), codingStage(), workspace));
        assertEquals(List.of(), resolver.resolve(task(), codingStage(), workspace.resolve("missing")));
        assertEquals(List.of(), resolver.resolve(task(), codingStage(), null));
    }

    @Test
    void unreadablePatchYieldsEmptyList() throws Exception {
        Files.write(workspace.resolve("candidate-patch.diff"), new byte[] {0, 1, 2});

        List<String> changed = new GitHostVerificationChangeSetResolver().resolve(task(), codingStage(), workspace);

        assertTrue(changed.isEmpty(), changed.toString());
    }

    @Test
    void gitNameOnlyAfterReplayWinsOverPatchFile() throws Exception {
        Files.writeString(workspace.resolve("candidate-patch.diff"), """
                diff --git a/from-patch.txt b/from-patch.txt
                --- a/from-patch.txt
                +++ b/from-patch.txt
                @@ -0,0 +1 @@
                +ignored when git reports the applied set
                """);
        runGit(List.of("git", "init"));
        runGit(List.of("git", "config", "user.email", "host-verify@example.test"));
        runGit(List.of("git", "config", "user.name", "Host Verify"));
        runGit(List.of("git", "config", "commit.gpgsign", "false"));
        Files.writeString(workspace.resolve("tracked.txt"), "base\n");
        runGit(List.of("git", "add", "tracked.txt"));
        runGit(List.of("git", "commit", "-m", "base"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.tsx"), "new\n");
        runGit(List.of("git", "add", "src/App.tsx"));

        List<String> changed = new GitHostVerificationChangeSetResolver().resolve(task(), codingStage(), workspace);

        assertEquals(List.of("src/App.tsx"), changed);
    }

    private void runGit(List<String> argv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, output);
    }

    private static RdRequirementTask task() {
        return RdRequirementTask.created(
                "9001",
                new CreateRequirementTaskCommand(
                        "title",
                        "P2",
                        "https://example.com/repo.git",
                        "acme",
                        "repo",
                        "main",
                        "ok",
                        List.of(),
                        false
                ),
                1L
        );
    }

    private static AgentStageRun codingStage() {
        return AgentStageRun.pending("7001", "9001", AgentRole.CODING_AGENT, 1, "idem-1", 1L);
    }
}
