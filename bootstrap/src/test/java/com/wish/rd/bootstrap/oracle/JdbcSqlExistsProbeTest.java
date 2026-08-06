package com.wish.rd.bootstrap.oracle;

import com.wish.rd.engine.oracle.AssertionEvaluationContext;
import com.wish.rd.engine.oracle.AssertionOutcome;
import com.wish.rd.engine.oracle.AssertionResult;
import com.wish.rd.engine.oracle.model.AssertionSpec;
import com.wish.rd.engine.oracle.model.AssertionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcSqlExistsProbeTest {

    @TempDir
    Path workspace;

    @Test
    void shouldReturnTrueWhenResultSetHasRow() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true);
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        JdbcSqlExistsProbe probe = new JdbcSqlExistsProbe(dataSource);

        assertTrue(probe.exists("SELECT 1", Duration.ofSeconds(2)));
        verify(statement).setQueryTimeout(2);
        verify(statement).executeQuery("SELECT 1");
    }

    @Test
    void shouldReturnFalseWhenResultSetEmpty() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(false);
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertFalse(new JdbcSqlExistsProbe(dataSource).exists("SELECT 1 WHERE false", Duration.ofMillis(500)));
        verify(statement).setQueryTimeout(1);
    }

    @Test
    void shouldWrapSqlException() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        SQLException thrown = assertThrows(
                SQLException.class,
                () -> new JdbcSqlExistsProbe(dataSource).exists("SELECT 1", Duration.ofSeconds(1))
        );
        assertTrue(thrown.getMessage().contains("SQL_ROW_EXISTS"));
    }

    @Test
    void shouldDriveSqlRowExistsRunnerEndToEnd() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenAnswer(invocation -> calls.getAndIncrement() == 0);
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        SqlRowExistsAssertionRunner runner = new SqlRowExistsAssertionRunner(new JdbcSqlExistsProbe(dataSource));
        AssertionResult result = runner.run(
                new AssertionSpec(
                        "j1",
                        "c1",
                        List.of(),
                        "",
                        "query",
                        AssertionType.SQL_ROW_EXISTS,
                        "SELECT 1 FROM dual",
                        "exists",
                        "true",
                        "",
                        List.of(),
                        1_000L,
                        ""
                ),
                AssertionEvaluationContext.of(workspace)
        );

        assertEquals(AssertionOutcome.PASSED, result.outcome());
    }
}
