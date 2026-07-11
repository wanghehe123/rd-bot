package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目管理 PostgreSQL schema 策略测试。
 */
class RdProjectPostgresSchemaPolicyTest {

    @Test
    void postgresSqlDeclaresProjectManagementTablesAndTaskProjectColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_projects"));
        assertTrue(sql.contains("project_key"));
        assertTrue(sql.contains("repository_url"));
        assertTrue(sql.contains("default_branch"));
        assertTrue(sql.contains("ALTER TABLE rd_projects ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT"));
        assertTrue(sql.contains("fk_rd_projects_knowledge_base"));
        assertTrue(sql.contains("ON DELETE SET NULL"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_id"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_key"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS project_name"));
        assertTrue(sql.contains("idx_rd_tasks_project"));
    }
}
