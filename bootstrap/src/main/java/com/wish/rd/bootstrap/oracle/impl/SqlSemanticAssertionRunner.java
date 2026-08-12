package com.wish.rd.bootstrap.oracle.impl;

import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.ReadOnlySqlPolicy;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Host SQL runner for scalar field, row-count, and boolean invariant semantics.
 * The injected probe must execute the already validated SQL in a read-only transaction.
 */
public final class SqlSemanticAssertionRunner implements AssertionRunnerPort {

    /** Executes one Host-validated SQL query and returns a bounded tabular snapshot. */
    @FunctionalInterface
    public interface SqlQueryProbe {

        /**
         * Executes the provided read-only SQL.
         *
         * @param sql validated SELECT/CTE statement
         * @param timeout Host assertion timeout
         * @return tabular result snapshot
         * @throws Exception on query execution failure
         */
        SqlQueryResult query(String sql, Duration timeout) throws Exception;
    }

    /** Immutable scalar-friendly SQL result used for deterministic Host assertions. */
    public record SqlQueryResult(List<String> columns, List<List<String>> rows) {

        /** Normalizes tabular values without exposing mutable JDBC resources. */
        public SqlQueryResult {
            columns = columns == null ? List.of() : columns.stream()
                    .map(value -> value == null ? "" : value.strip())
                    .toList();
            if (rows == null || rows.isEmpty()) {
                rows = List.of();
            } else {
                List<List<String>> copy = new ArrayList<>();
                for (List<String> row : rows) {
                    copy.add(row == null ? List.of() : row.stream()
                            .map(value -> value == null ? "" : value)
                            .toList());
                }
                rows = List.copyOf(copy);
            }
        }
    }

    private final SqlQueryProbe probe;

    /**
     * Creates the semantic SQL runner.
     *
     * @param probe Host JDBC or test probe
     */
    public SqlSemanticAssertionRunner(SqlQueryProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
    }

    /**
     * Evaluates a frozen SQL semantic assertion.
     *
     * @param spec Host assertion specification
     * @param context Host evaluation context
     * @return pass/fail/error result
     */
    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (spec.assertionType() != AssertionType.SQL_FIELD_VALUE
                && spec.assertionType() != AssertionType.SQL_ROW_COUNT
                && spec.assertionType() != AssertionType.SQL_INVARIANT) {
            return AssertionResult.unsupported(spec.id(), spec.assertionType());
        }
        try {
            String sql = ReadOnlySqlPolicy.validate(spec.target(), ReadOnlySqlPolicy.defaultAllowedSchemas());
            SqlQueryResult result = probe.query(sql, Duration.ofMillis(Math.max(1L, spec.timeoutMillis())));
            if (result == null) {
                return AssertionResult.error(spec.id(), spec.assertionType(), "SQL probe returned null");
            }
            return switch (spec.assertionType()) {
                case SQL_FIELD_VALUE -> fieldValue(spec, result);
                case SQL_ROW_COUNT -> rowCount(spec, result);
                case SQL_INVARIANT -> invariant(spec, result);
                default -> AssertionResult.unsupported(spec.id(), spec.assertionType());
            };
        } catch (Exception exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return AssertionResult.error(spec.id(), spec.assertionType(), "SQL probe failed: " + message);
        }
    }

    private static AssertionResult fieldValue(AssertionSpec spec, SqlQueryResult result) {
        String actual = scalar(result, spec.action());
        if (actual == null) {
            return AssertionResult.failed(spec.id(), AssertionType.SQL_FIELD_VALUE,
                    "SQL field value is absent", spec.evidenceRequired());
        }
        boolean passed = spec.expected().equals(actual);
        return passed
                ? AssertionResult.passed(spec.id(), AssertionType.SQL_FIELD_VALUE,
                "SQL field value matches", spec.evidenceRequired())
                : AssertionResult.failed(spec.id(), AssertionType.SQL_FIELD_VALUE,
                "SQL field value does not match", spec.evidenceRequired());
    }

    private static AssertionResult rowCount(AssertionSpec spec, SqlQueryResult result) {
        long expected;
        try {
            expected = Long.parseLong(spec.expected());
        } catch (NumberFormatException exception) {
            return AssertionResult.error(spec.id(), AssertionType.SQL_ROW_COUNT,
                    "SQL row count expected must be an integer");
        }
        long actual = result.rows().size();
        boolean passed = compare(actual, spec.operator(), expected);
        return passed
                ? AssertionResult.passed(spec.id(), AssertionType.SQL_ROW_COUNT,
                "SQL row count matches", spec.evidenceRequired())
                : AssertionResult.failed(spec.id(), AssertionType.SQL_ROW_COUNT,
                "SQL row count does not match", spec.evidenceRequired());
    }

    private static AssertionResult invariant(AssertionSpec spec, SqlQueryResult result) {
        String actual = scalar(result, spec.action());
        if (actual == null || !("true".equalsIgnoreCase(actual) || "false".equalsIgnoreCase(actual))) {
            return AssertionResult.failed(spec.id(), AssertionType.SQL_INVARIANT,
                    "SQL invariant query did not return a boolean", spec.evidenceRequired());
        }
        boolean actualBoolean = Boolean.parseBoolean(actual);
        boolean expected = "true".equalsIgnoreCase(spec.operator())
                ? true
                : Boolean.parseBoolean(spec.expected());
        boolean passed = actualBoolean == expected;
        return passed
                ? AssertionResult.passed(spec.id(), AssertionType.SQL_INVARIANT,
                "SQL invariant holds", spec.evidenceRequired())
                : AssertionResult.failed(spec.id(), AssertionType.SQL_INVARIANT,
                "SQL invariant does not hold", spec.evidenceRequired());
    }

    private static String scalar(SqlQueryResult result, String requestedColumn) {
        if (result.rows().isEmpty()) {
            return null;
        }
        List<String> row = result.rows().getFirst();
        int column = columnIndex(result.columns(), requestedColumn);
        if (column < 0 || column >= row.size()) {
            return null;
        }
        return row.get(column);
    }

    private static int columnIndex(List<String> columns, String requestedColumn) {
        String requested = requestedColumn == null ? "" : requestedColumn.strip();
        if (requested.isBlank()) {
            return 0;
        }
        for (int index = 0; index < columns.size(); index++) {
            if (requested.equalsIgnoreCase(columns.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean compare(long actual, String operator, long expected) {
        return switch (operator == null ? "" : operator.strip().toLowerCase(Locale.ROOT)) {
            case "eq", "equals" -> actual == expected;
            case "gt" -> actual > expected;
            case "gte" -> actual >= expected;
            case "lt" -> actual < expected;
            case "lte" -> actual <= expected;
            default -> false;
        };
    }
}
