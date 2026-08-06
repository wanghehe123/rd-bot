package com.wish.rd.bootstrap.oracle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

/**
 * JDBC-backed {@link SqlRowExistsAssertionRunner.SqlExistsProbe}.
 * Executes a pre-sanitized SELECT and returns whether any row is present.
 */
public final class JdbcSqlExistsProbe implements SqlRowExistsAssertionRunner.SqlExistsProbe {

    private final DataSource dataSource;

    public JdbcSqlExistsProbe(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean exists(String sql, Duration timeout) throws Exception {
        Objects.requireNonNull(sql, "sql must not be null");
        Duration effective = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(5)
                : timeout;
        int queryTimeoutSeconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, effective.toSeconds()));
        // sub-second timeouts still need a positive JDBC query timeout
        if (effective.toMillis() > 0 && effective.toSeconds() == 0) {
            queryTimeoutSeconds = 1;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new SQLException("SQL_ROW_EXISTS probe failed: " + exception.getMessage(), exception);
        }
    }
}
