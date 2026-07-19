package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QaEvidenceSqlPolicyTest {

    @Test
    void shouldProvideIdempotentQaProfileAndEvidenceTables() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p5_qa_evidence.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_qa_validation_profiles"));
        assertTrue(content.contains("PRIMARY KEY (scope_type, scope_id)"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS rd_qa_evidence_objects"));
        assertTrue(content.contains("UNIQUE (task_id, stage_run_id, artifact_id)"));
        assertTrue(content.contains("CREATE INDEX IF NOT EXISTS idx_rd_qa_evidence_task"));
    }
}
