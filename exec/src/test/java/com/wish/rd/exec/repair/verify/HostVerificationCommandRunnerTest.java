package com.wish.rd.exec.repair.verify;

import com.wish.rd.exec.repair.verify.model.HostVerificationCommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVerificationCommandRunnerTest {

    @TempDir
    Path workingDirectory;

    private final HostVerificationCommandRunner runner = new HostVerificationCommandRunner();

    @Test
    void echoSucceedsWithCombinedOutput() {
        HostVerificationCommandResult result = runner.run(
                "echo hello-host-verify",
                workingDirectory,
                Duration.ofSeconds(5)
        );

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.output().contains("hello-host-verify"), result.output());
    }

    @Test
    void falseCommandReturnsNonZeroExit() {
        HostVerificationCommandResult result = runner.run(
                "false",
                workingDirectory,
                Duration.ofSeconds(5)
        );

        assertFalse(result.timedOut());
        assertTrue(result.exitCode() != 0, "exitCode=" + result.exitCode());
    }

    @Test
    void sleepLongerThanTimeoutSetsTimedOut() {
        HostVerificationCommandResult result = runner.run(
                "sleep 5",
                workingDirectory,
                Duration.ofMillis(200)
        );

        assertTrue(result.timedOut());
        assertTrue(result.exitCode() != 0 || result.timedOut());
    }
}
