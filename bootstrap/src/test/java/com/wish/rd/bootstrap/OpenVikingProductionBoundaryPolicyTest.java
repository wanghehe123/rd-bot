package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenVikingProductionBoundaryPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void productionWritersMustNotComposeStoreMutations() throws Exception {
        List<String> writers = List.of(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/controller/admin/knowledge/KnowledgeAdminController.java",
                "rag/src/main/java/com/wish/rd/rag/knowledge/FeishuDocKnowledgeImporter.java",
                "rag/src/main/java/com/wish/rd/rag/knowledge/KnowledgeRefreshScheduler.java",
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java"
        );
        for (String writer : writers) {
            String source = Files.readString(PROJECT_ROOT.resolve(writer));
            assertFalse(source.contains("documentStore.save"), writer + " must not save documents directly");
            assertFalse(source.contains("chunkStore.save"), writer + " must not save chunks directly");
            assertFalse(source.contains("vectorStore.index"), writer + " must not index vectors directly");
            assertFalse(source.contains("workspace.writeDocument("), writer + " must not call workspace.writeDocument");
            assertFalse(source.contains("workspace.deleteDocument("), writer + " must not call workspace.deleteDocument");
            assertFalse(source.contains("workspace.createChunk("), writer + " must not call workspace.createChunk");
            assertFalse(source.contains("workspace.rechunkDocument("), writer + " must not call workspace.rechunkDocument");
        }
        String importer = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/FeishuDocKnowledgeImporter.java"));
        assertTrue(importer.contains("KnowledgeDocumentMutationPort"));
        String registry = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/ingestion/IngestionAdminRegistry.java"));
        assertTrue(registry.contains("workspace.mutations()"));
    }

    @Test
    void submitPathMustNeverBeAbleToReplayAnAlreadySentRow() throws Exception {
        String mapper = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/"
                        + "KnowledgeExternalIndexOutboxMapper.java"));
        int claimBatch = mapper.indexOf("List<KnowledgeExternalIndexOutboxRow> claimBatch(");
        assertTrue(claimBatch > 0, "claimBatch must exist");
        String claimSql = mapper.substring(0, claimBatch);
        assertTrue(claimSql.contains("remote_operation_id = ''"),
                "claimBatch must exclude rows that already crossed the send boundary");

        String pollSql = selectSqlBefore(mapper, "List<KnowledgeExternalIndexOutboxRow> claimPollBatch(");
        assertFalse(pollSql.contains("attempt_count = op.attempt_count + 1"),
                "a poll claim must not consume the submission budget");
        assertFalse(pollSql.contains("attempt_count < max_attempts"),
                "a running remote task must stay visible even after the submit budget is gone");
        assertFalse(pollSql.contains("SET status"),
                "a poll claim must not change the operation status");
    }

    /** 取紧邻方法签名之前的那段 {@code @Select} SQL，避开解释性 javadoc 造成的假匹配。 */
    private static String selectSqlBefore(String source, String signature) {
        int method = source.indexOf(signature);
        assertTrue(method > 0, signature + " must exist");
        int select = source.lastIndexOf("@Select(\"\"\"", method);
        assertTrue(select > 0, signature + " must be backed by a @Select");
        return source.substring(select, method);
    }

    @Test
    void theWorkerMustCommitItsSendIntentBeforeTheRequestLeaves() throws Exception {
        String worker = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexSyncEngine.java"));
        int intent = worker.indexOf("ExternalIndexSettleCommand.aboutToSend(");
        int send = worker.indexOf("indexPort.submitUpsert(");
        assertTrue(intent > 0 && send > intent,
                "the durable send marker must be written before the HTTP call, not after");
        assertFalse(worker.contains("indexPort.inspectTask("), "the submit path must not poll");
        assertFalse(worker.contains("indexPort.verifyResource("), "the submit path must not verify");
    }

    @Test
    void thePollerMustNeverResendAWrite() throws Exception {
        String poller = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexPollEngine.java"));
        assertFalse(poller.contains("submitUpsert("),
                "an already-sent write may only be resolved by read-only queries");
        assertTrue(poller.contains("ExternalIndexSettleCommand.verifying()"),
                "a completed task must still pass version verification before it counts");
    }

    /**
     * 真机实测：同一篇已正确落库的中文文档，用短 token 查得到、用它自己 200 字正文查
     * 连续 61 秒 0 命中，{@code tags} 是排序后的过滤器兜不住。相关性打分依赖语料和
     * 嵌入模型，一旦回到闸门里，健康文档会被判 NEEDS_HUMAN，换嵌入模型就是投影停摆。
     */
    @Test
    void versionVerificationMustNotDependOnRelevanceRanking() throws Exception {
        String adapter = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/OpenVikingRestIndexAdapter.java"));
        String verify = adapter.substring(adapter.indexOf("public ExternalKnowledgeVerification verifyResource("));
        assertFalse(verify.contains("search/find"),
                "a relevance-ranked query may never gate the projection settle path");
        String verification = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/model/ExternalKnowledgeVerification.java"));
        assertFalse(verification.contains("CHECK_SEARCH"),
                "removing the constant keeps the check from being re-derived as an improvement");
        String contractSmoke = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/test/java/com/wish/rd/bootstrap/openviking/OpenVikingRealContractSmokeTest.java"));
        assertTrue(contractSmoke.contains("/api/v1/search/find"),
                "the search response shape must stay pinned by the frozen WP-0 contract");
    }

    @Test
    void theAdapterMustNotLeakTheApiKey() throws Exception {
        for (String path : List.of(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/OpenVikingRestIndexAdapter.java",
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/JdkOpenVikingHttpExchange.java")) {
            for (String line : Files.readAllLines(PROJECT_ROOT.resolve(path))) {
                if (!line.contains("log.")) {
                    continue;
                }
                assertFalse(line.contains("apiKey") || line.contains("api_key") || line.contains("X-API-Key"),
                        path + " must never pass the API key to a logger: " + line.strip());
            }
        }
        String adapter = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/impl/OpenVikingRestIndexAdapter.java"));
        assertTrue(adapter.contains("OpenVikingErrorTranslator.safeMessage("),
                "every persisted error string must go through redaction");
    }

    @Test
    void applicationDefaultsMustKeepProjectionOff() throws Exception {
        String yaml = Files.readString(PROJECT_ROOT.resolve("bootstrap/src/main/resources/application.yaml"));
        assertTrue(yaml.contains("mode: ${RD_KNOWLEDGE_PROJECTION_MODE:OFF}"),
                "projection must stay opt-in so an unconfigured deployment never writes to a shared index");
        assertTrue(yaml.contains("enabled: ${RD_OPENVIKING_ENABLED:false}"));
        assertTrue(yaml.contains("api-key-env: ${RD_OPENVIKING_API_KEY_ENV:OPENVIKING_API_KEY}"),
                "the API key must be read from the environment, never from configuration");
        assertTrue(yaml.contains("enabled: ${RD_OPENVIKING_RECONCILE_ENABLED:false}"),
                "the reconciler must stay opt-in so an unconfigured deployment never scans a shared index");
    }

    @Test
    void deleteMustPersistSendIntentBeforeTheRemoteRemove() throws Exception {
        String worker = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexSyncEngine.java"));
        String delete = methodBody(worker, "private boolean dispatchDelete(");
        int intent = delete.indexOf("persistSendIntent(");
        int remove = delete.indexOf("removeSafely(");
        assertTrue(intent > 0 && remove > intent,
                "a delete that already crossed the send boundary must never be issued without a durable marker");
        assertTrue(delete.contains("removeSafely("));
        int aboutToSend = worker.indexOf("ExternalIndexSettleCommand.aboutToSend(");
        int removeResource = worker.indexOf("indexPort.removeResource(");
        assertTrue(aboutToSend > 0 && removeResource > aboutToSend,
                "aboutToSend must precede removeResource in the worker source");
    }

    @Test
    void theReconcilerMustNeverIssueARemoteWrite() throws Exception {
        String reconciler = Files.readString(PROJECT_ROOT.resolve(
                "rag/src/main/java/com/wish/rd/rag/knowledge/projection/KnowledgeExternalIndexReconcileEngine.java"));
        assertFalse(reconciler.contains("removeResource("),
                "orphans are findings, not deletes");
        assertFalse(reconciler.contains("submitUpsert("),
                "the reconciler must never submit a write; it may only enqueue REBUILD_DOCUMENT");
        String config = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/OpenVikingProjectionConfiguration.java"));
        assertTrue(config.contains("KnowledgeExternalIndexReconcileEngine"),
                "the reconciler must be a wired bean so admin and the scheduler share one instance");
        String scheduler = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/openviking/KnowledgeProjectionScheduler.java"));
        assertTrue(scheduler.contains("rd.knowledge.projection.reconcile.enabled"),
                "reconcile must have an independent enable switch");
        assertTrue(scheduler.contains("rd.knowledge.projection.reconcile.interval-millis"),
                "reconcile must tick on its own interval, defaulting to 300000");
    }

    @Test
    void requeueMustNotReturnASentRowToPending() throws Exception {
        String mapper = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/persistence/mapper/"
                        + "KnowledgeExternalIndexOutboxMapper.java"));
        String requeueSql = selectSqlBefore(mapper, "KnowledgeExternalIndexOutboxRow requeueDeadLetter(");
        assertFalse(requeueSql.contains("SET status = 'PENDING'"),
                "a sent dead letter must not be requeued to PENDING; that would replay the write");
        assertTrue(requeueSql.contains("CASE WHEN remote_operation_id = '' THEN 'PENDING' ELSE 'UNKNOWN_REMOTE_RESULT'"),
                "unsent rows return to PENDING; sent rows can only become UNKNOWN_REMOTE_RESULT");
    }

    /**
     * 投影指标查询失败时会静默退回全零。一个被改名的列不会报错，只会让面板一直显示"没有积压"，
     * 因此列名必须和迁移脚本对齐。
     */
    @Test
    void projectionMetricsMustQueryColumnsThatActuallyExist() throws Exception {
        String controller = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/java/com/wish/rd/bootstrap/controller/observability/"
                        + "PrometheusMetricsController.java"));
        int start = controller.indexOf("private static ProjectionMetrics projectionMetrics(");
        assertTrue(start > 0, "projectionMetrics must exist");
        String sql = controller.substring(start, controller.indexOf("\n    }", start));

        String migration = Files.readString(PROJECT_ROOT.resolve(
                "bootstrap/src/main/resources/sql/postgres/p11_openviking_projection.sql"));
        for (String column : List.of("status", "projection_status", "created_at")) {
            assertTrue(sql.contains(column), "the projection gauges must read " + column);
            assertTrue(migration.contains(column + " "),
                    "column " + column + " must exist in p11 or the gauge silently reports zero backlog");
        }
        assertTrue(sql.contains("knowledge_external_index_outbox"));
        assertTrue(sql.contains("knowledge_external_index_bindings"));
        assertFalse(sql.contains("'CLAIMED'"),
                "a claimed row is still unconverged; excluding it would hide a stuck worker");
        for (String terminal : List.of("SUCCEEDED", "SUPERSEDED", "DEAD_LETTER")) {
            assertTrue(sql.contains("'" + terminal + "'"),
                    "terminal state " + terminal + " must be excluded from the backlog age gauge");
        }
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
}
