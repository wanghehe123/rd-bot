package com.wish.rd.bootstrap.oracle.impl;

import com.wish.rd.engine.oracle.ReadOnlySqlPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC-backed {@link SqlRowExistsAssertionRunner.SqlExistsProbe}.
 * Executes a pre-sanitized SELECT and returns whether any row is present.
 */
public final class JdbcSqlExistsProbe implements SqlRowExistsAssertionRunner.SqlExistsProbe,
        SqlSemanticAssertionRunner.SqlQueryProbe {

    private final DataSource dataSource;

    public JdbcSqlExistsProbe(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean exists(String sql, Duration timeout) throws Exception {
        return !query(sql, timeout).rows().isEmpty();
    }

    /**
     * Executes a Host-validated read-only SQL query and returns values detached from JDBC resources.
     *
     * @param sql SELECT/CTE query
     * @param timeout Host assertion timeout
     * @return immutable tabular result
     * @throws Exception when query execution fails
     */
    @Override
    public SqlSemanticAssertionRunner.SqlQueryResult query(String sql, Duration timeout) throws Exception {
        Objects.requireNonNull(sql, "sql must not be null");
        String validatedSql = ReadOnlySqlPolicy.validate(sql, ReadOnlySqlPolicy.defaultAllowedSchemas());
        Duration effective = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(5)
                : timeout;
        int queryTimeoutSeconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, effective.toSeconds()));
        // sub-second timeouts still need a positive JDBC query timeout
        if (effective.toMillis() > 0 && effective.toSeconds() == 0) {
            queryTimeoutSeconds = 1;
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            boolean originalReadOnly = connection.isReadOnly();
            try {
                if (originalAutoCommit) {
                    connection.setAutoCommit(false);
                }
                if (!originalReadOnly) {
                    connection.setReadOnly(true);
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(queryTimeoutSeconds);
                    // Enforce both JDBC and PostgreSQL transaction-level read-only protections.
                    statement.execute("SET TRANSACTION READ ONLY");
                    // Unqualified table names may resolve only in the Host-approved schema.
                    statement.execute("SET LOCAL search_path TO public");
                    try (ResultSet resultSet = statement.executeQuery(validatedSql)) {
                        return snapshot(resultSet);
                    }
                }
            } finally {
                try {
                    connection.rollback();
                } finally {
                    if (!originalReadOnly) {
                        connection.setReadOnly(false);
                    }
                    if (originalAutoCommit) {
                        connection.setAutoCommit(true);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new SQLException("Host SQL probe failed: " + exception.getMessage(), exception);
        }
    }

    private static SqlSemanticAssertionRunner.SqlQueryResult snapshot(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            columns.add(metadata.getColumnLabel(index));
        }
        List<List<String>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<String> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                Object value = resultSet.getObject(index);
                row.add(value == null ? "" : String.valueOf(value));
            }
            rows.add(List.copyOf(row));
        }
        return new SqlSemanticAssertionRunner.SqlQueryResult(columns, rows);
    }
}
