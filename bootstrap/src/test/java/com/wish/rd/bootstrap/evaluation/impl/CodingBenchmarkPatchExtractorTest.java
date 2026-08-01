package com.wish.rd.bootstrap.evaluation.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingBenchmarkPatchExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldKeepExistingCandidatePatchUntouched() throws Exception {
        Path repo = initRepo();
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path candidate = output.resolve("candidate.patch");
        Files.writeString(candidate, "diff --git a/kept b/kept\n");

        CodingBenchmarkPatchExtractor.Result result = CodingBenchmarkPatchExtractor.ensure(
                repo, output, candidate, "");

        assertTrue(result.ready());
        assertEquals(CodingBenchmarkPatchExtractor.Source.AGENT_WRITTEN, result.source());
        assertEquals("diff --git a/kept b/kept\n", Files.readString(candidate));
    }

    @Test
    void shouldPromotePatchDiffToCandidatePatch() throws Exception {
        Path repo = initRepo();
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path candidate = output.resolve("candidate.patch");
        Files.writeString(output.resolve("patch.diff"), "diff --git a/from-bridge b/from-bridge\n");

        CodingBenchmarkPatchExtractor.Result result = CodingBenchmarkPatchExtractor.ensure(
                repo, output, candidate, "");

        assertTrue(result.ready());
        assertEquals(CodingBenchmarkPatchExtractor.Source.PROMOTED_PATCH_DIFF, result.source());
        assertEquals("diff --git a/from-bridge b/from-bridge\n", Files.readString(candidate));
    }

    @Test
    void shouldHostExtractIncludingUntrackedFiles() throws Exception {
        Path repo = initRepo();
        Files.writeString(repo.resolve("tracked.txt"), "changed\n");
        Files.writeString(repo.resolve("Issue2285.java"), "class Issue2285 {}\n");
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path candidate = output.resolve("candidate.patch");

        CodingBenchmarkPatchExtractor.Result result = CodingBenchmarkPatchExtractor.ensure(
                repo, output, candidate, "");

        assertTrue(result.ready());
        assertEquals(CodingBenchmarkPatchExtractor.Source.HOST_EXTRACTED, result.source());
        String patch = Files.readString(candidate);
        assertTrue(patch.contains("Issue2285.java"), patch);
        assertTrue(patch.contains("tracked.txt") || patch.contains("changed"), patch);
    }

    @Test
    void shouldRejectPatchThatTouchesWithheldTestPath() throws Exception {
        Path repo = initRepo();
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path candidate = output.resolve("candidate.patch");
        Files.writeString(output.resolve("patch.diff"), """
                diff --git a/tests/runtime_withheld/SecretTest.java b/tests/runtime_withheld/SecretTest.java
                new file mode 100644
                --- /dev/null
                +++ b/tests/runtime_withheld/SecretTest.java
                @@ -0,0 +1 @@
                +class SecretTest {}
                """);

        CodingBenchmarkPatchExtractor.Result result = CodingBenchmarkPatchExtractor.ensure(
                repo, output, candidate, "tests/runtime_withheld");

        assertFalse(result.ready());
        assertEquals(CodingBenchmarkPatchExtractor.Source.REJECTED, result.source());
        assertFalse(Files.exists(candidate) && Files.size(candidate) > 0);
    }

    private Path initRepo() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo-" + System.nanoTime()));
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "eval@example.com");
        run(repo, "git", "config", "user.name", "eval");
        Files.writeString(repo.resolve("tracked.txt"), "base\n");
        run(repo, "git", "add", "tracked.txt");
        run(repo, "git", "commit", "-m", "base");
        return repo;
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command) + "\n" + output);
        }
    }
}
