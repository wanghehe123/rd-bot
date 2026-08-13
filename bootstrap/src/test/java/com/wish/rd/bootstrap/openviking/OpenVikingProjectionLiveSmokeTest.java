package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.openviking.impl.JdkOpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.impl.OpenVikingRestIndexAdapter;
import com.wish.rd.rag.knowledge.model.KnowledgeDocumentRevision;
import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexPollEngine;
import com.wish.rd.rag.knowledge.projection.KnowledgeExternalIndexSyncEngine;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexBindingStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeExternalIndexOutboxStore;
import com.wish.rd.rag.knowledge.projection.impl.InMemoryKnowledgeProjectionSettleAdapter;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeDesiredState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeObservedState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeRemoval;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationStatus;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeOperationType;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeProjectionStatus;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexBinding;
import com.wish.rd.rag.knowledge.projection.model.KnowledgeExternalIndexOperation;
import com.wish.rd.rag.knowledge.projection.model.ProjectionWorkerSettings;
import com.wish.rd.rag.knowledge.store.impl.InMemoryKnowledgeDocumentRevisionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-3/WP-4 端到端真机验证：Outbox 一行 PENDING，经 Worker/Poller 收敛到 version-verified
 * {@code IN_SYNC}，更新到 v2 再次收敛，最后在适配器层验证真实容器的删除假设
 * （rm 幂等、probe 404 即 absent）。
 *
 * <p>默认跳过。先 {@code scripts/openviking/up.sh}，再加 {@code -Drd.openviking.smoke=true}。
 * 只在 {@code viking://resources/rd-bot/wp0-contract/{runId}/} 下创建与删除资源。
 *
 * <p>这个用例存在的理由：单测里的核验断言（尤其"读回 L2 正文的 SHA-256 等于 checksum"）
 * 是对真实服务行为的假设。假设错了必须在这里暴露，而不是等生产里整条投影停在 NEEDS_HUMAN。
 */
@EnabledIfSystemProperty(named = "rd.openviking.smoke", matches = "true")
class OpenVikingProjectionLiveSmokeTest {

    private static final String KB_ID = "1001";
    private static final String DOC_ID = "3001";
    private static final String FEISHU_SOURCE = "https://my.feishu.cn/wiki/IJHrwvwaDiicFpkID8Fcxvsun0g";

    @Test
    void shouldDriveAPendingOutboxRowAllTheWayToVersionVerifiedInSync() throws Exception {
        String baseUrl = System.getProperty("rd.openviking.smoke.base-url", "http://127.0.0.1:1933");
        String runId = "wp3s" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        String userKey = provisionUserKey(baseUrl, runId);
        String runRoot = OpenVikingProjectionUris.contractTestRoot(runId);
        String documentRoot = OpenVikingProjectionUris.contractTestDocumentRoot(runId, DOC_ID);

        OpenVikingContractHttp cleanup = new OpenVikingContractHttp(
                baseUrl, userKey, Duration.ofSeconds(3), Duration.ofSeconds(30));
        Harness harness = new Harness(baseUrl, userKey, documentRoot);
        try {
            String source = liveFeishuMarkdown();
            String bodyV1 = source + "\n\nUnique token: `RD_WP3_LIVE_V1`\n";
            harness.seed(1L, bodyV1);

            assertTrue(harness.indexPort.ready(), "the live container must be ready before the smoke asserts");
            assertEquals(1, harness.syncEngine.runOnce(System.currentTimeMillis()));
            KnowledgeExternalIndexOperation submitted = harness.operation();
            assertEquals(ExternalKnowledgeOperationStatus.WAITING_REMOTE, submitted.status());
            assertFalse(submitted.remoteTaskId().isBlank());

            harness.pollUntilSettled();
            assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, harness.operation().status(),
                    () -> "last error: " + harness.operation().lastErrorCode()
                            + " " + harness.operation().lastErrorMessage()
                            + "\n" + remoteDiagnostic(cleanup, documentRoot));
            KnowledgeExternalIndexBinding inSync = harness.binding();
            assertEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, inSync.projectionStatus());
            assertEquals(ExternalKnowledgeObservedState.READY, inSync.observedState());
            assertEquals(1L, inSync.observedVersion());
            assertEquals(sha256(bodyV1), inSync.observedChecksum());

            String bodyV2 = source + "\n\nUnique token: `RD_WP3_LIVE_V2`\n";
            harness.seed(2L, bodyV2);
            assertEquals(1, harness.syncEngine.runOnce(System.currentTimeMillis()));
            harness.pollUntilSettled();

            assertEquals(ExternalKnowledgeOperationStatus.SUCCEEDED, harness.operation().status());
            KnowledgeExternalIndexBinding updated = harness.binding();
            assertEquals(ExternalKnowledgeProjectionStatus.IN_SYNC, updated.projectionStatus());
            assertEquals(2L, updated.observedVersion());
            assertEquals(sha256(bodyV2), updated.observedChecksum());

            JsonNode read = cleanup.get("/api/v1/content/read", OpenVikingContractHttp.query(
                    "uri", OpenVikingProjectionUris.l2ContentUri(
                            documentRoot, OpenVikingProjectionUris.SOURCE_FILE_NAME)));
            assertTrue(read.path("result").asText().contains("RD_WP3_LIVE_V2"));

            // WP-4 真机验证：删除路径依赖的三个远端假设——rm 受理即幂等、probe 404 表示
            // absent 而非失败、重复删除 count=0。引擎侧的删除状态机（发送边界、DELETING→
            // DELETED、防复活）由单测钉住；生产命名空间的引擎级删除由全栈验收覆盖，
            // 合同冒烟只允许写 wp0-contract 根，因此这里在适配器层直接验证。
            ExternalKnowledgeRemoval removed = harness.indexPort.removeResource(
                    documentRoot, true, OpenVikingProjectionUris.OWNED_ROOT);
            assertTrue(removed.removed(),
                    () -> "live delete failed: " + removed.errorCode() + " " + removed.errorMessage());
            assertTrue(removed.deletedCount() >= 1, "first delete must remove at least one entry");

            ExternalResourceProbe probe = harness.indexPort.inspectResource(documentRoot);
            assertTrue(probe.failureClass().success(),
                    () -> "probe after delete must not fail: " + probe.errorCode() + " " + probe.errorMessage());
            assertFalse(probe.exists(), "the document root must be absent after the delete");

            ExternalKnowledgeRemoval again = harness.indexPort.removeResource(
                    documentRoot, true, OpenVikingProjectionUris.OWNED_ROOT);
            assertTrue(again.removed(), "repeat delete must stay idempotent");
            assertEquals(0, again.deletedCount(), "repeat delete must find nothing left to remove");
        } finally {
            cleanup.delete("/api/v1/fs", OpenVikingContractHttp.query(
                    "uri", OpenVikingProjectionUris.requireCleanupUri(runRoot.replaceAll("/+$", ""), runRoot),
                    "recursive", "true"));
        }
    }

    /**
     * 核验失败时把远端真实状态带进断言。没有这几行，上一次排查就只能靠反复猜
     * {@code target_uri} 与 {@code query} 的组合。
     */
    private static String remoteDiagnostic(OpenVikingContractHttp client, String documentRoot) {
        return "ls(documentRoot)=" + quiet(client, "/api/v1/fs/ls", documentRoot)
                + "\nattrs=" + quiet(client, "/api/v1/fs/attrs", documentRoot);
    }

    private static String quiet(OpenVikingContractHttp client, String path, String uri) {
        try {
            return client.get(path, OpenVikingContractHttp.query("uri", uri)).toString();
        } catch (Exception ex) {
            return "diagnostic failed: " + ex;
        }
    }

    /**
     * 优先用 WP-1/WP-2 验证过的那篇真实飞书 wiki 作为正文。真实中文正文才能暴露
     * "读回 L2 内容再算 SHA-256" 这类编码假设；lark-cli 未登录时退回合成正文，
     * 这样纯 OpenViking 冒烟仍然可以单独跑。
     */
    private static String liveFeishuMarkdown() throws Exception {
        if (!"true".equals(System.getProperty("rd.feishu.docs.smoke"))) {
            return "# WP-3 live projection\n\n合成正文，未启用飞书真机抓取。\n";
        }
        ProcessBuilder builder = new ProcessBuilder(
                "lark-cli", "docs", "+fetch", "--as", "user",
                "--doc", FEISHU_SOURCE, "--doc-format", "markdown", "--format", "json");
        builder.environment().put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
        builder.environment().put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "lark-cli docs +fetch timed out");
        assertEquals(0, process.exitValue(), output);
        String content = new ObjectMapper().readTree(output)
                .path("data").path("document").path("content").asText();
        assertFalse(content.isBlank(), "live wiki content must not be blank");
        assertTrue(content.contains("自动修复"), "the smoke must project the verified WP-1 wiki document");
        return content;
    }

    private static String provisionUserKey(String baseUrl, String runId) throws Exception {
        String rootKey = System.getProperty(
                "rd.openviking.smoke.root-key",
                System.getenv().getOrDefault("OPENVIKING_ROOT_API_KEY", "rd-bot-local-openviking-root"));
        OpenVikingContractHttp root = new OpenVikingContractHttp(
                baseUrl, rootKey, Duration.ofSeconds(3), Duration.ofSeconds(30));
        root.postJson("/api/v1/admin/accounts", Map.of("account_id", "rd-bot", "admin_user_id", "wp3-admin"));
        JsonNode user = root.postJson("/api/v1/admin/accounts/rd-bot/users", Map.of(
                "user_id", runId, "role", "user"));
        assertEquals(200, root.statusOfLast, root.rawOfLast);
        String userKey = user.path("result").path("user_key").asText();
        assertFalse(userKey.isBlank(), "the smoke needs a tenant-scoped user key");
        return userKey;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Harness {

        private final InMemoryKnowledgeExternalIndexOutboxStore outbox =
                new InMemoryKnowledgeExternalIndexOutboxStore();
        private final InMemoryKnowledgeExternalIndexBindingStore bindings =
                new InMemoryKnowledgeExternalIndexBindingStore();
        private final InMemoryKnowledgeDocumentRevisionStore revisions =
                new InMemoryKnowledgeDocumentRevisionStore();
        private final ExternalKnowledgeIndexPort indexPort;
        private final KnowledgeExternalIndexSyncEngine syncEngine;
        private final KnowledgeExternalIndexPollEngine pollEngine;
        private final String documentRoot;
        private String eventId = "";

        private Harness(String baseUrl, String userKey, String documentRoot) {
            this.documentRoot = documentRoot;
            this.indexPort = new OpenVikingRestIndexAdapter(
                    new JdkOpenVikingHttpExchange(
                            baseUrl, () -> userKey, Duration.ofSeconds(3), Duration.ofSeconds(60)),
                    () -> userKey,
                    OpenVikingProjectionUris.OWNED_ROOT);
            InMemoryKnowledgeProjectionSettleAdapter settlePort =
                    new InMemoryKnowledgeProjectionSettleAdapter(outbox, bindings);
            ProjectionWorkerSettings settings = ProjectionWorkerSettings.defaults();
            this.syncEngine = new KnowledgeExternalIndexSyncEngine(
                    indexPort, outbox, bindings, revisions, settlePort, settings, () -> "wp3-live");
            this.pollEngine = new KnowledgeExternalIndexPollEngine(
                    indexPort, outbox, bindings, settlePort, settings, () -> "wp3-live");
        }

        private void seed(long syncVersion, String body) throws Exception {
            long now = System.currentTimeMillis();
            String checksum = sha256(body);
            String revisionId = "9" + syncVersion;
            revisions.save(new KnowledgeDocumentRevision(
                    revisionId, DOC_ID, syncVersion, "live", checksum,
                    "text/markdown", body, "rd-bot-default", "1", now));
            KnowledgeExternalIndexBinding current = bindings
                    .findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, DOC_ID)
                    .orElse(null);
            bindings.save(new KnowledgeExternalIndexBinding(
                    KnowledgeExternalIndexBinding.OPENVIKING, DOC_ID, KB_ID, documentRoot,
                    OpenVikingProjectionUris.ownershipMarker(KB_ID, DOC_ID),
                    ExternalKnowledgeDesiredState.PRESENT, syncVersion, checksum,
                    current == null ? ExternalKnowledgeObservedState.UNKNOWN : current.observedState(),
                    current == null ? 0L : current.observedVersion(),
                    current == null ? "" : current.observedChecksum(),
                    ExternalKnowledgeProjectionStatus.PENDING, "", "", "",
                    0L, current == null ? 0L : current.lastVerifiedAtEpochMillis(), "", "",
                    current == null ? 0L : current.rowVersion(),
                    current == null ? now : current.createdAtEpochMillis(), now));
            eventId = "800" + syncVersion;
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    eventId,
                    "OPENVIKING:doc:" + DOC_ID + ":" + syncVersion + ":UPSERT_DOCUMENT",
                    KnowledgeExternalIndexBinding.OPENVIKING,
                    ExternalKnowledgeOperationType.UPSERT_DOCUMENT,
                    KB_ID, DOC_ID, syncVersion, checksum, documentRoot, revisionId, "",
                    ExternalKnowledgeOperationStatus.PENDING,
                    "", "", "", 0L, 0, 8, now, 0L, "", "", 0L, now, now));
        }

        private void pollUntilSettled() throws InterruptedException {
            for (int attempt = 0; attempt < 120; attempt++) {
                pollEngine.runOnce(System.currentTimeMillis());
                KnowledgeExternalIndexOperation current = operation();
                if (current.status() == ExternalKnowledgeOperationStatus.SUCCEEDED
                        || current.status() == ExternalKnowledgeOperationStatus.NEEDS_HUMAN
                        || current.status() == ExternalKnowledgeOperationStatus.DEAD_LETTER) {
                    return;
                }
                Thread.sleep(1_000L);
                rewind();
            }
        }

        /** 真机等待用秒级节拍，不必陪引擎的分钟级退避时钟一起等。 */
        private void rewind() {
            KnowledgeExternalIndexOperation current = operation();
            long now = System.currentTimeMillis();
            if (current.nextVisibleAtEpochMillis() <= now && current.leaseUntilEpochMillis() <= now) {
                return;
            }
            outbox.delete(current.eventId());
            outbox.enqueue(new KnowledgeExternalIndexOperation(
                    current.eventId(), current.idempotencyKey(), current.provider(), current.operationType(),
                    current.knowledgeBaseId(), current.documentId(), current.syncVersion(), current.checksum(),
                    current.remoteUri(), current.revisionId(), current.payloadRef(), current.status(),
                    current.remoteTaskId(), current.remoteOperationId(), current.leaseOwner(),
                    0L, current.attemptCount(), current.maxAttempts(),
                    now, current.publishedAtEpochMillis(),
                    current.lastErrorCode(), current.lastErrorMessage(), current.rowVersion(),
                    current.createdAtEpochMillis(), current.updatedAtEpochMillis()));
        }

        private KnowledgeExternalIndexOperation operation() {
            return outbox.findById(eventId).orElseThrow();
        }

        private KnowledgeExternalIndexBinding binding() {
            return bindings.findByProviderAndDocumentId(KnowledgeExternalIndexBinding.OPENVIKING, DOC_ID)
                    .orElseThrow();
        }
    }
}
