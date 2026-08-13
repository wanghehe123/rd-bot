package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingProjectionSqlPolicyTest {

    @Test
    void shouldAddIdentityRevisionAndSoftDeleteColumnsWithoutUniqueIdentityIndex() throws Exception {
        Path sql = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p11_openviking_projection.sql");
        String content = Files.readString(sql);

        assertTrue(content.contains("ALTER TABLE knowledge_bases"));
        assertTrue(content.contains("lifecycle_status"));
        assertTrue(content.contains("ALTER TABLE knowledge_documents"));
        assertTrue(content.contains("source_identity_key"));
        assertTrue(content.contains("local_only_override"));
        assertTrue(content.contains("CREATE TABLE IF NOT EXISTS knowledge_document_revisions"));
        assertTrue(content.contains("REFERENCES knowledge_documents(id) ON DELETE RESTRICT"));
        assertTrue(content.contains("UNIQUE (document_id, sync_version)"));
        assertTrue(content.contains("UNIQUE (document_id, checksum)"));
        assertFalse(content.contains("CREATE UNIQUE INDEX"),
                "WP-6 must audit duplicate survivors before an active-only unique identity index");
        assertFalse(content.contains("knowledge_external_index"),
                "bindings and outbox belong to WP-2, not WP-1");
    }
}
