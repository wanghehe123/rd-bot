package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.LogPatternAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogPatternAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldEvaluateRequiredAndForbiddenPatternsAgainstHostWorkspaceLog() throws Exception {
        Path log = workspace.resolve("qa-evidence/commands/service.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "server started on port 8080\nrequest completed\n");
        LogPatternAssertionRunner runner = new LogPatternAssertionRunner();

        AssertionResult required = runner.run(
                spec("required", AssertionType.LOG_MUST_MATCH, "server\\s+started"),
                AssertionEvaluationContext.of(workspace)
        );
        AssertionResult forbidden = runner.run(
                spec("forbidden", AssertionType.LOG_MUST_NOT_MATCH, "Exception"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.PASSED, required.outcome());
        assertEquals(AssertionOutcome.PASSED, forbidden.outcome());
    }

    @Test
    void shouldFailSecretAndPiiScanForUnredactedSensitiveLogContent() throws Exception {
        Path log = workspace.resolve("qa-evidence/commands/service.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "password=do-not-log\ncustomer@example.com\n");
        LogPatternAssertionRunner runner = new LogPatternAssertionRunner();

        AssertionResult result = runner.run(
                spec("sensitive", AssertionType.LOG_SECRET_SCAN, "clean"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertTrue(result.message().toLowerCase().contains("sensitive"));
    }

    private static AssertionSpec spec(String id, AssertionType type, String expected) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "",
                type,
                "qa-evidence/commands/service.log",
                type == AssertionType.LOG_MUST_MATCH ? "matches"
                        : type == AssertionType.LOG_MUST_NOT_MATCH ? "not_matches" : "clean",
                expected,
                "",
                List.of("qa-evidence/commands/service.log"),
                1_000L,
                ""
        );
    }
}
