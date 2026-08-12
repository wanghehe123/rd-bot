package com.wish.rd.engine.oracle;

import com.wish.rd.engine.oracle.impl.FileAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionRunReport;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAssertionRunnerTest {

    @TempDir
    Path workspace;

    private final FileAssertionRunner runner = new FileAssertionRunner();

    @Test
    void shouldPassWhenRequiredFileExists() throws Exception {
        Files.writeString(workspace.resolve("out.txt"), "ok");
        AssertionResult result = runner.run(
                fileSpec("f1", AssertionType.FILE_EXISTS, "out.txt"),
                AssertionEvaluationContext.of(workspace)
        );
        assertEquals(AssertionOutcome.PASSED, result.outcome());
    }

    @Test
    void shouldFailWhenRequiredFileMissing() {
        AssertionResult result = runner.run(
                fileSpec("f2", AssertionType.FILE_EXISTS, "missing.txt"),
                AssertionEvaluationContext.of(workspace)
        );
        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertTrue(result.message().contains("missing"));
    }

    @Test
    void shouldRejectPathEscape() {
        AssertionResult result = runner.run(
                fileSpec("f3", AssertionType.FILE_EXISTS, "../secret"),
                AssertionEvaluationContext.of(workspace)
        );
        assertEquals(AssertionOutcome.ERROR, result.outcome());
    }

    @Test
    void shouldIntegrateWithHostOracleForFileExists() throws Exception {
        Files.writeString(workspace.resolve("report.json"), "{}");
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                fileSpec("f4", AssertionType.FILE_EXISTS, "report.json")
        ));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of(
                AssertionType.FILE_EXISTS, new FileAssertionRunner()
        ));

        AssertionRunReport report = oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace));

        assertTrue(report.passed());
        assertFalse(report.results().isEmpty());
    }

    private static AssertionSpec fileSpec(String id, AssertionType type, String target) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "",
                type,
                target,
                "exists",
                "",
                "",
                List.of(),
                1_000L,
                ""
        );
    }
}
