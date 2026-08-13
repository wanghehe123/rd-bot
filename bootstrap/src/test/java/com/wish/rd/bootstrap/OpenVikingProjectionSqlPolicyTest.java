package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingProjectionSqlPolicyTest {

    @Test
    void shouldAddIdentityRevisionAndSoftDeleteColumnsWithoutUniqueIdentityIndex() throws Exception {
        String content = readP11();

        assertTrue(content.contains("ALTER TABLE knowledge_bases"));
        assertTrue(content.contains("lifecycle_status"));
        assertTrue(content.contains("ALTER TABLE knowledge_documents"));
        assertTrue(content.contains("source_identity_key"));
        assertTrue(content.contains("local_only_override"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS knowledge_document_revisions"));
        assertTrue(content.contains("REFERENCES knowledge_documents(id) ON DELETE RESTRICT"));
        assertTrue(content.contains("UNIQUE (document_id, sync_version)"));
        assertTrue(content.contains("UNIQUE (document_id, checksum)"));
        assertFalse(
                content.contains("CREATE UNIQUE INDEX")
                        && content.contains("source_identity_key")
                        && content.contains("deleted_at IS NULL"),
                "WP-6 must audit duplicate survivors before an active-only unique identity index");
    }

    @Test
    void shouldDefineBindingAndOutboxTablesWithRestrictAndUniqueKeys() throws Exception {
        String content = readP11();
        String bindings = tableBody(content, "knowledge_external_index_bindings");
        String outbox = tableBody(content, "knowledge_external_index_outbox");

        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS knowledge_external_index_bindings"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS knowledge_external_index_outbox"));
        assertTrue(bindings.contains("PRIMARY KEY (provider, document_id)"));
        assertTrue(bindings.contains("UNIQUE (provider, remote_uri)"));
        assertTrue(bindings.contains("REFERENCES knowledge_documents(id) ON DELETE RESTRICT"));
        assertTrue(bindings.contains("REFERENCES knowledge_bases(id) ON DELETE RESTRICT"));
        assertFalse(bindings.contains("ON DELETE CASCADE"));

        assertTrue(outbox.contains("UNIQUE (idempotency_key)"));
        assertTrue(outbox.contains("UNIQUE (provider, document_id, sync_version, operation_type)"));
        assertFalse(outbox.contains("REFERENCES knowledge_documents"));
        assertFalse(outbox.contains("REFERENCES knowledge_bases"));
        assertFalse(outbox.contains("ON DELETE CASCADE"));
        assertTrue(
                content.contains("WHERE document_id IS NULL"),
                "KB-level operations need a partial unique index because NULL document_id bypasses UNIQUE");
        assertTrue(content.contains("idx_knowledge_external_index_outbox_claim"));
    }

    @Test
    void shouldClaimOutboxWithSkipLockedAndTransactionalAdapter() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        String mapper = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/KnowledgeExternalIndexOutboxMapper.java"));
        assertTrue(mapper.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(mapper.contains("lease_owner"));
        assertTrue(mapper.contains("row_version"));

        String adapter = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/impl/PostgresKnowledgeMutationTransactionAdapter.java"));
        assertTrue(adapter.contains("@Transactional"));
        assertFalse(adapter.contains("HttpClient"));
        assertFalse(adapter.contains("Executor"));
    }

    private static String readP11() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p11_openviking_projection.sql");
        return Files.readString(sql);
    }

    private static String tableBody(String sql, String tableName) {
        String marker = "CREATE TABLE IF NOT EXISTS " + tableName;
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "missing table " + tableName);
        int end = sql.indexOf("CREATE TABLE IF NOT EXISTS", start + marker.length());
        if (end < 0) {
            end = sql.length();
        }
        return sql.substring(start, end);
    }
}
