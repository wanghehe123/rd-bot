package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.AssertionResult;
import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Host adapter for {@link AssertionType#SQL_ROW_EXISTS}.
 * Only single-statement SELECT probes are accepted; write/DDL SQL is rejected.
 */
public final class SqlRowExistsAssertionRunner implements AssertionRunnerPort {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|call|execute|copy|merge)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @FunctionalInterface
    public interface SqlExistsProbe {
        boolean exists(String sql, Duration timeout) throws Exception;
    }

    private final SqlExistsProbe probe;

    public SqlRowExistsAssertionRunner(SqlExistsProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
    }

    @Override
    public AssertionResult run(AssertionSpec spec, AssertionEvaluationContext context) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (spec.assertionType() != AssertionType.SQL_ROW_EXISTS) {
            return AssertionResult.unsupported(spec.id(), spec.assertionType());
        }

        String sql;
        try {
            sql = sanitizeSelect(spec.target());
        } catch (IllegalArgumentException exception) {
            return AssertionResult.error(spec.id(), AssertionType.SQL_ROW_EXISTS, exception.getMessage());
        }

        Duration timeout = Duration.ofMillis(Math.max(1L, spec.timeoutMillis()));
        boolean found;
        try {
            found = probe.exists(sql, timeout);
        } catch (Exception exception) {
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return AssertionResult.error(spec.id(), AssertionType.SQL_ROW_EXISTS, "SQL probe failed: " + message);
        }

        List<String> evidence = List.copyOf(spec.evidenceRequired());
        if (found) {
            return AssertionResult.passed(
                    spec.id(),
                    AssertionType.SQL_ROW_EXISTS,
                    "row exists for query",
                    evidence
            );
        }
        return AssertionResult.failed(
                spec.id(),
                AssertionType.SQL_ROW_EXISTS,
                "no row matched query",
                evidence
        );
    }

    static String sanitizeSelect(String rawTarget) {
        String sql = rawTarget == null ? "" : rawTarget.strip();
        if (sql.isBlank()) {
            throw new IllegalArgumentException("SQL_ROW_EXISTS target must not be blank");
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("SQL_ROW_EXISTS target must be a single statement");
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            throw new IllegalArgumentException("SQL_ROW_EXISTS target must be a SELECT (or WITH ... SELECT)");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw new IllegalArgumentException("SQL_ROW_EXISTS target must not contain write/DDL keywords");
        }
        return sql;
    }
}
