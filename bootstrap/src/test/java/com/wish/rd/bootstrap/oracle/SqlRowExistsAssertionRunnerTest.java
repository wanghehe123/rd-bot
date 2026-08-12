package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.HostAssertionOracle;
import com.wish.rd.bootstrap.oracle.impl.SqlRowExistsAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionSpecBundle;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRowExistsAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldPassWhenProbeFindsRow() {
        AtomicReference<String> seenSql = new AtomicReference<>();
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> {
            seenSql.set(sql);
            return true;
        });

        AssertionResult result = runner.run(
                sqlSpec("s1", "SELECT 1 FROM users WHERE id = 1"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.PASSED, result.outcome());
        assertEquals("SELECT 1 FROM users WHERE id = 1", seenSql.get());
    }

    @Test
    void shouldFailWhenProbeFindsNoRow() {
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> false);

        AssertionResult result = runner.run(
                sqlSpec("s2", "SELECT 1 FROM users WHERE id = -1"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertTrue(result.message().toLowerCase().contains("no row"));
    }

    @Test
    void shouldRejectNonSelectSql() {
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> true);

        AssertionResult result = runner.run(
                sqlSpec("s3", "DELETE FROM users WHERE id = 1"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
        assertTrue(result.message().toLowerCase().contains("select"));
    }

    @Test
    void shouldRejectMultiStatementSql() {
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> true);

        AssertionResult result = runner.run(
                sqlSpec("s4", "SELECT 1; DROP TABLE users"),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.ERROR, result.outcome());
    }

    @Test
    void shouldIntegrateWithHostOracle() {
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> true);
        AssertionSpecBundle bundle = AssertionSpecBundle.freeze(List.of(
                sqlSpec("s5", "SELECT 1 FROM dual")
        ));
        HostAssertionOracle oracle = new HostAssertionOracle(Map.of(
                AssertionType.SQL_ROW_EXISTS, runner
        ));

        assertTrue(oracle.evaluate(bundle, AssertionEvaluationContext.of(workspace)).passed());
    }

    @Test
    void shouldHonorTimeoutFromSpec() {
        AtomicReference<Duration> seen = new AtomicReference<>();
        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner((sql, timeout) -> {
            seen.set(timeout);
            return true;
        });

        runner.run(sqlSpec("s6", "SELECT 1", 3_000L), AssertionEvaluationContext.of(workspace));

        assertEquals(Duration.ofMillis(3_000L), seen.get());
    }

    private static AssertionSpec sqlSpec(String id, String target) {
        return sqlSpec(id, target, 1_000L);
    }

    private static AssertionSpec sqlSpec(String id, String target, long timeoutMillis) {
        return new AssertionSpec(
                id,
                "criteria-" + id,
                List.of(),
                "",
                "query",
                AssertionType.SQL_ROW_EXISTS,
                target,
                "exists",
                "true",
                "",
                List.of(),
                timeoutMillis,
                ""
        );
    }
}
