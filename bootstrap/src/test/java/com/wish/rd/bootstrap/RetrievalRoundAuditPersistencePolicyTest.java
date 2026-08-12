package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies iterative round audit persistence has a PostgreSQL schema and adapter. */
class RetrievalRoundAuditPersistencePolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void shouldProvideRoundAuditSchemaAndAdapter() throws Exception {
        String sql = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p2_rag_retrieval_state.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS rd_retrieval_round_audits"));
        assertTrue(sql.contains("candidate_evidence_ids_json"));
        assertTrue(sql.contains("information_gain"));
        assertTrue(sql.contains("stop_reason"));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/entity/RetrievalRoundAuditRow.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/RetrievalRoundAuditMapper.java")));
        assertTrue(Files.exists(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresRetrievalRoundAuditStore.java")));
    }
}
