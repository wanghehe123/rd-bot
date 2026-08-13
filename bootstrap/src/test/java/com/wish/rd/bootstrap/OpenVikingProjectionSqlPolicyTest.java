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

    /**
     * WP-6 的身份唯一索引只能住在 p13，并且必须自带审计守卫。索引一旦建成，被它
     * 拒绝的写入就变成运行期错误，操作员再没有"先看审计、再决定谁存活"的机会，
     * 所以迁移必须在存在未解决重复时明确失败，而不是静默跳过。
     */
    @Test
    void shouldCreateTheActiveOnlyIdentityIndexOnlyInP13AndOnlyBehindAnAuditGuard() throws Exception {
        String p13 = Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/sql/postgres/p13_openviking_identity_backfill.sql"));

        assertTrue(p13.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_documents_active_identity"));
        assertTrue(p13.contains("source_identity_key IS NOT NULL"));
        assertTrue(p13.contains("deleted_at IS NULL"),
                "tombstones keep their identity for audit and must not participate in uniqueness");
        assertTrue(p13.contains("superseded_by_document_id IS NULL"),
                "superseded rows keep their identity for provenance and must not participate in uniqueness");
        assertTrue(p13.contains("RAISE EXCEPTION"),
                "the migration must fail loudly while duplicate active identities remain");
        assertTrue(p13.contains("HAVING COUNT(*) > 1"),
                "the guard must actually look for duplicate groups");
        assertTrue(p13.contains("SET source_identity_key = NULL"),
                "blank identities must be normalised before a unique index treats them as a real identity");
        assertTrue(p13.indexOf("RAISE EXCEPTION") < p13.indexOf("CREATE UNIQUE INDEX"),
                "the audit guard must run before the index is created");
    }

    @Test
    void shouldRejectDuplicateSourceIdentityWithAConflictInsteadOfAConstraintViolation() throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        String engine = Files.readString(projectRoot.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeDocumentMutationEngine.java"));
        String writeDocument = methodBody(engine, "public KnowledgeDocument writeDocument(\n"
                + "            PipelineDefinition pipeline,\n"
                + "            WriteKnowledgeDocumentCommand command,\n"
                + "            KnowledgeDocumentSource source\n"
                + "    )");
        assertTrue(writeDocument.contains("rejectDuplicateSourceIdentity("),
                "the always-new-id write path must pre-check the active source identity");

        String controller = Files.readString(projectRoot.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeAdminController.java"));
        assertTrue(controller.contains("@ExceptionHandler(DuplicateSourceIdentityException.class)"));
        assertTrue(controller.contains("HttpStatus.CONFLICT"),
                "a duplicate source identity must surface as 409, not as an unhandled server error");
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
    void shouldKeepTombstonesWhenForeignKeysRestrictAndDeletesStaySoft() throws Exception {
        String p11 = readP11();
        String documentsAlter = p11.substring(p11.indexOf("ALTER TABLE knowledge_documents"));
        assertTrue(documentsAlter.contains("ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ"),
                "tombstones require a soft-delete column on knowledge_documents");

        String revisions = tableBody(p11, "knowledge_document_revisions");
        String bindings = tableBody(p11, "knowledge_external_index_bindings");
        String outbox = tableBody(p11, "knowledge_external_index_outbox");
        assertTrue(revisions.contains("REFERENCES knowledge_documents(id) ON DELETE RESTRICT"),
                "FK cascade must not wipe tombstones");
        assertFalse(revisions.contains("ON DELETE CASCADE"));
        assertFalse(bindings.contains("ON DELETE CASCADE"));
        assertFalse(outbox.contains("ON DELETE CASCADE"));

        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        String engine = Files.readString(projectRoot.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeDocumentMutationEngine.java"));
        String deleteDocument = methodBody(engine, "public void deleteDocument(String documentId)");
        assertTrue(deleteDocument.contains("DELETE_DOCUMENT"),
                "the delete path must enqueue a tombstone projection, not drop the row");
        assertTrue(deleteDocument.contains("withSoftDeleted("));
        assertFalse(deleteDocument.contains("deleteById("));
        assertFalse(deleteDocument.contains("DELETE FROM knowledge_documents"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method " + signature);
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int index = brace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, index + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method body for " + signature);
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
