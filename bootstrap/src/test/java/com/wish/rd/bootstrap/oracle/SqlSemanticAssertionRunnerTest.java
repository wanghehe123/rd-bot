package com.wish.rd.bootstrap.oracle;

import com.wish.rd.bootstrap.oracle.impl.SqlSemanticAssertionRunner;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionOutcome;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlSemanticAssertionRunnerTest {

    @TempDir
    Path workspace;

    @Test
    void shouldFailSemanticFieldAssertionWhenSuccessfulSqlHasWrongValue() {
        SqlSemanticAssertionRunner runner = new SqlSemanticAssertionRunner(
                (sql, timeout) -> new SqlSemanticAssertionRunner.SqlQueryResult(
                        List.of("state"),
                        List.of(List.of("PENDING"))
                )
        );

        AssertionResult result = runner.run(spec(AssertionType.SQL_FIELD_VALUE, "eq", "COMPLETED"), context());

        assertEquals(AssertionOutcome.FAILED, result.outcome());
        assertEquals("SQL field value does not match", result.message());
    }

    @Test
    void shouldEvaluateRowCountWithNumericOperator() {
        SqlSemanticAssertionRunner runner = new SqlSemanticAssertionRunner(
                (sql, timeout) -> new SqlSemanticAssertionRunner.SqlQueryResult(
                        List.of("id"),
                        List.of(List.of("1"), List.of("2"))
                )
        );

        AssertionResult result = runner.run(spec(AssertionType.SQL_ROW_COUNT, "gte", "2"), context());

        assertEquals(AssertionOutcome.PASSED, result.outcome());
    }

    private AssertionEvaluationContext context() {
        return new AssertionEvaluationContext(workspace, "", Map.of());
    }

    private static AssertionSpec spec(AssertionType type, String operator, String expected) {
        return new AssertionSpec(
                "sql-assertion",
                "criteria-1",
                List.of(),
                "",
                "",
                type,
                "SELECT state FROM public.jobs",
                operator,
                expected,
                "",
                List.of("qa-evidence/commands/sql.log"),
                1_000L,
                ""
        );
    }
}
