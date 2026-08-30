package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAgentMemorySqlPolicyTest {

    @Test
    void definesProjectOwnedRevisionedMemoryWithNonCascadingEvidenceAndFencedOperations() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p19_project_agent_memory.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memories"));
        assertTrue(content.contains("REFERENCES rd_projects(id) ON DELETE RESTRICT"));
        assertTrue(content.contains("UNIQUE (project_id, scope_role, memory_type, logical_key)"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memory_revisions"));
        assertTrue(content.contains("status IN ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'EXPIRED', 'REJECTED', 'QUARANTINED', 'DELETED')"));
        assertTrue(content.contains("FOREIGN KEY (memory_id, supersedes_revision_id)"));
        assertTrue(content.contains("FOREIGN KEY (id, head_revision_id)"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memory_sources"));
        assertTrue(content.contains("REFERENCES rd_tasks(id) ON DELETE SET NULL"));
        assertTrue(content.contains("REFERENCES rd_agent_stage_runs(id) ON DELETE SET NULL"));
        assertTrue(content.contains("REFERENCES rd_agent_stage_artifacts(id) ON DELETE SET NULL"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memory_operations"));
        assertTrue(content.contains("UNIQUE (operation_key)"));
        assertTrue(content.contains("fencing_token"));
        assertTrue(content.contains("row_version"));
        assertTrue(content.contains("idx_rd_project_memory_search"));
        assertTrue(content.contains("idx_rd_project_memory_operations_claim"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_project_memory_modes"));
        assertTrue(content.contains("capture_mode IN ('OFF', 'SHADOW', 'ACTIVE')"));
        assertTrue(content.contains("read_mode IN ('LEGACY', 'SHADOW', 'DUAL', 'PRIMARY')"));
    }
}
