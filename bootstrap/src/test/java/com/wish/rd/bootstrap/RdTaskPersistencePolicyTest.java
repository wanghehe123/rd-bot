package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdTaskPersistencePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void shouldProvidePostgresTaskStoreAndProjectManagedSql() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p0_knowledge_productionization.sql"));

        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRdTaskStore.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdTaskRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdTaskMapper.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresTaskMaterialStore.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RdTaskMaterialRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdTaskMaterialMapper.java")));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_tasks"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_task_materials"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_requirement_publications"));
        assertTrue(sql.contains("uk_rd_requirement_publications_operation"));
        assertTrue(sql.contains("candidate_patch_sha256"));
        assertTrue(sql.contains("next_reconcile_at"));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRequirementPublicationStore.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RequirementPublicationMapper.java")));
        assertTrue(sql.contains("id                 BIGINT PRIMARY KEY"));
        assertTrue(sql.contains("task_type          VARCHAR(64) NOT NULL"));
        assertTrue(sql.contains("execution_result_json JSONB NOT NULL DEFAULT '{}'::jsonb"));
        assertTrue(sql.contains("source_type        VARCHAR(32) NOT NULL"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_id VARCHAR(256) NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS source_url TEXT NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS repository_url TEXT NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS base_branch VARCHAR(256) NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS expected_result TEXT NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS acceptance_criteria_json JSONB NOT NULL DEFAULT '[]'::jsonb"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("ck_rd_tasks_version_non_negative"));
        assertTrue(sql.contains("ALTER TABLE rd_tasks ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 1"));
        assertTrue(sql.contains("ck_rd_tasks_fencing_token_positive"));
        String mapper = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RdTaskMapper.java"));
        assertTrue(mapper.contains("advanceStatusWithExpectedVersion"));
        assertTrue(mapper.contains("AND version = #{expectedVersion}"));
        assertTrue(mapper.contains("AND fencing_token = #{expectedFencingToken}"));
        assertTrue(mapper.contains("AND status = #{expectedStatus}"));
        assertTrue(mapper.contains("updateTaskWithExpectedVersionFenced"));
        assertTrue(mapper.contains("ON CONFLICT (id) DO NOTHING"));
        assertFalse(mapper.contains("status = EXCLUDED.status"),
                "blind upsert must not overwrite a live task status");
        assertFalse(mapper.contains("version = EXCLUDED.version"),
                "blind upsert must not overwrite task version");
        assertFalse(mapper.contains("fencing_token = EXCLUDED.fencing_token"),
                "blind upsert must not overwrite task fencing token");
        assertTrue(sql.contains("content_hash"));
        assertTrue(sql.contains("VARCHAR(128) NOT NULL DEFAULT ''"));
        assertTrue(sql.contains("metadata_json         JSONB NOT NULL DEFAULT '{}'::jsonb"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_rd_task_materials_task ON rd_task_materials (task_id, created_at)"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_rd_task_materials_source ON rd_task_materials (source_type, source_uri)"));
    }
}
