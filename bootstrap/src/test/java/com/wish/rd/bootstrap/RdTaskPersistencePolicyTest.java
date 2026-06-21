package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RdTaskPersistencePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void shouldProvidePostgresTaskStoreAndProjectManagedSql() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/PostgresRdTaskStore.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdTaskRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdTaskMapper.java")));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_tasks"));
        assertTrue(sql.contains("id                 BIGINT PRIMARY KEY"));
        assertTrue(sql.contains("task_type          VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("execution_result_json JSONB NOT NULL DEFAULT '{}'::jsonb"));
    }
}
