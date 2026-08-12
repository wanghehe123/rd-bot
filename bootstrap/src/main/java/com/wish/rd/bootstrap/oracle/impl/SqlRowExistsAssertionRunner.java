package com.wish.rd.bootstrap.oracle.impl;

import com.wish.rd.engine.oracle.AssertionRunnerPort;
import com.wish.rd.engine.oracle.ReadOnlySqlPolicy;
import com.wish.rd.engine.oracle.model.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.model.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Host adapter for {@link AssertionType#SQL_ROW_EXISTS}.
 * Only single-statement SELECT probes are accepted; write/DDL SQL is rejected.
 */
public final class SqlRowExistsAssertionRunner implements AssertionRunnerPort {

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
        return ReadOnlySqlPolicy.validate(rawTarget, ReadOnlySqlPolicy.defaultAllowedSchemas());
    }
}
