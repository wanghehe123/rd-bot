package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 OpenViking v0.4.13 合同。默认跳过；需要先启动
 * {@code deploy/openviking/docker-compose.yml}，再加 {@code -Drd.openviking.smoke=true}。
 *
 * <p>只在 {@code viking://resources/rd-bot/wp0-contract/{runId}/} 下创建和删除资源。
 */
@EnabledIfSystemProperty(named = "rd.openviking.smoke", matches = "true")
class OpenVikingRealContractSmokeTest {

    @Test
    void shouldUploadExactRootPollL0L1UpdateAndIdempotentlyDeleteOnlyTheTestUri() throws Exception {
        String baseUrl = System.getProperty("rd.openviking.smoke.base-url", "http://127.0.0.1:1933");
        String rootKey = System.getProperty(
                "rd.openviking.smoke.root-key",
                System.getenv().getOrDefault("OPENVIKING_ROOT_API_KEY", "rd-bot-local-openviking-root")
        );
        OpenVikingContractHttp anonymous = new OpenVikingContractHttp(
                baseUrl, "", Duration.ofSeconds(3), Duration.ofSeconds(15)
        );
        JsonNode health = anonymous.get("/health");
        assertEquals(200, anonymous.statusOfLast);
        assertEquals("ok", health.path("status").asText());
        assertEquals("v0.4.13", health.path("version").asText());
        JsonNode ready = anonymous.get("/ready");
        assertEquals("ready", ready.path("status").asText());
        assertEquals("ok", ready.path("checks").path("embedding").asText());
        assertEquals("not_configured", ready.path("checks").path("ollama").asText());

        OpenVikingContractHttp root = new OpenVikingContractHttp(
                baseUrl, rootKey, Duration.ofSeconds(3), Duration.ofSeconds(30)
        );
        ensureAccount(root);
        String runId = "wp0s" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        JsonNode user = root.postJson("/api/v1/admin/accounts/rd-bot/users", Map.of(
                "user_id", runId,
                "role", "user"
        ));
        assertEquals(200, root.statusOfLast, root.rawOfLast);
        String userKey = user.path("result").path("user_key").asText();
        assertFalse(userKey.isBlank());
        assertFalse(userKey.equals(rootKey));

        OpenVikingContractHttp client = new OpenVikingContractHttp(
                baseUrl, userKey, Duration.ofSeconds(3), Duration.ofSeconds(60)
        );
        String runRoot = OpenVikingProjectionUris.contractTestRoot(runId);
        String to = OpenVikingProjectionUris.contractTestDocumentRoot(runId, "1001");
        String l2 = OpenVikingProjectionUris.l2ContentUri(to, OpenVikingProjectionUris.SOURCE_FILE_NAME);
        String bodyV1 = "# WP-0 live contract\n\nUnique token: `RD_WP0_LIVE_CONTRACT_V1`\n";
        String checksumV1 = sha256(bodyV1);
        try {
            JsonNode uploaded = client.uploadMarkdown(OpenVikingProjectionUris.SOURCE_FILE_NAME, bodyV1.getBytes(StandardCharsets.UTF_8));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            String tempFileId = uploaded.path("result").path("temp_file_id").asText();
            assertTrue(tempFileId.startsWith("upload_"));

            JsonNode added = client.postJson("/api/v1/resources", Map.of(
                    "temp_file_id", tempFileId,
                    "to", to,
                    "wait", false,
                    "create_parent", true,
                    "processing_mode", "semantic_and_vectors",
                    "args", Map.of("parse_mode", "no_split"),
                    "tags", OpenVikingProjectionUris.ownershipTags("1001", "1001", 1L, checksumV1),
                    "tag_mode", "replace"
            ));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            assertEquals("success", added.path("result").path("status").asText());
            assertEquals(to, added.path("result").path("root_uri").asText());
            String taskId = added.path("result").path("task_id").asText();
            JsonNode task = awaitTask(client, taskId);
            assertEquals("completed", task.path("result").path("status").asText());
            assertEquals(0, task.path("result").path("result").path("queue_status").path("Semantic").path("error_count").asInt());
            assertEquals(0, task.path("result").path("result").path("queue_status").path("Embedding").path("error_count").asInt());

            JsonNode attrs = client.get("/api/v1/fs/attrs", OpenVikingContractHttp.query("uri", to));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            String tags = attrs.path("result").path("attrs").path("tags").toString();
            assertTrue(tags.contains("rd.owner=rd-bot"));
            assertTrue(tags.contains("rd.sync_version=1"));
            assertTrue(tags.contains("rd.checksum=" + checksumV1));

            JsonNode abstractNode = client.get("/api/v1/content/abstract", OpenVikingContractHttp.query("uri", to));
            assertFalse(abstractNode.path("result").asText().isBlank());
            JsonNode overview = client.get("/api/v1/content/overview", OpenVikingContractHttp.query("uri", to));
            assertFalse(overview.path("result").asText().isBlank());
            JsonNode read = client.get("/api/v1/content/read", OpenVikingContractHttp.query("uri", l2));
            assertTrue(read.path("result").asText().contains("RD_WP0_LIVE_CONTRACT_V1"));

            JsonNode find = client.postJson("/api/v1/search/find", Map.of(
                    "query", "RD_WP0_LIVE_CONTRACT_V1",
                    "target_uri", runRoot,
                    "limit", 8,
                    "tags", List.of("rd.doc_id=1001")
            ));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            assertTrue(find.path("result").path("resources").size() >= 1);

            String bodyV2 = "# WP-0 live contract v2\n\nUnique token: `RD_WP0_LIVE_CONTRACT_V2`\n";
            String checksumV2 = sha256(bodyV2);
            JsonNode uploadedV2 = client.uploadMarkdown(
                    OpenVikingProjectionUris.SOURCE_FILE_NAME,
                    bodyV2.getBytes(StandardCharsets.UTF_8)
            );
            JsonNode updated = client.postJson("/api/v1/resources", Map.of(
                    "temp_file_id", uploadedV2.path("result").path("temp_file_id").asText(),
                    "to", to,
                    "wait", false,
                    "create_parent", true,
                    "processing_mode", "semantic_and_vectors",
                    "args", Map.of("parse_mode", "no_split"),
                    "tags", OpenVikingProjectionUris.ownershipTags("1001", "1001", 2L, checksumV2),
                    "tag_mode", "replace"
            ));
            assertEquals(to, updated.path("result").path("root_uri").asText());
            awaitTask(client, updated.path("result").path("task_id").asText());
            JsonNode attrsV2 = client.get("/api/v1/fs/attrs", OpenVikingContractHttp.query("uri", to));
            assertTrue(attrsV2.path("result").path("attrs").path("tags").toString().contains("rd.sync_version=2"));
            JsonNode readV2 = client.get("/api/v1/content/read", OpenVikingContractHttp.query("uri", l2));
            assertTrue(readV2.path("result").asText().contains("RD_WP0_LIVE_CONTRACT_V2"));

            JsonNode deleted = client.delete("/api/v1/fs", OpenVikingContractHttp.query("uri", to, "recursive", "true"));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            assertTrue(deleted.path("result").path("estimated_deleted_count").asInt() >= 1);
            JsonNode deletedAgain = client.delete("/api/v1/fs", OpenVikingContractHttp.query("uri", to, "recursive", "true"));
            assertEquals(200, client.statusOfLast, deletedAgain.toString());
            assertEquals(0, deletedAgain.path("result").path("estimated_deleted_count").asInt());
            JsonNode missing = client.get("/api/v1/fs/stat", OpenVikingContractHttp.query("uri", to));
            assertEquals(404, client.statusOfLast);
            assertEquals("NOT_FOUND", missing.path("error").path("code").asText());
        } finally {
            client.delete(
                    "/api/v1/fs",
                    OpenVikingContractHttp.query(
                            "uri",
                            OpenVikingProjectionUris.requireCleanupUri(runRoot.replaceAll("/+$", ""), runRoot),
                            "recursive",
                            "true"
                    )
            );
            JsonNode cleaned = client.get("/api/v1/fs/stat", OpenVikingContractHttp.query("uri", to));
            assertEquals(404, client.statusOfLast, cleaned.toString());
        }
    }

    @Test
    void shouldMatchRecordedErrorContractsForUnauthorizedBusyTask404AndTimeout() throws Exception {
        String baseUrl = System.getProperty("rd.openviking.smoke.base-url", "http://127.0.0.1:1933");
        String rootKey = System.getProperty(
                "rd.openviking.smoke.root-key",
                System.getenv().getOrDefault("OPENVIKING_ROOT_API_KEY", "rd-bot-local-openviking-root")
        );
        OpenVikingContractHttp anonymous = new OpenVikingContractHttp(
                baseUrl, "", Duration.ofSeconds(3), Duration.ofSeconds(15)
        );
        JsonNode unauthenticated = anonymous.get("/api/v1/fs/ls", OpenVikingContractHttp.query(
                "uri",
                "viking://resources/rd-bot/wp0-contract/auth-probe/"
        ));
        assertEquals(401, anonymous.statusOfLast);
        assertEquals("UNAUTHENTICATED", unauthenticated.path("error").path("code").asText());

        OpenVikingContractHttp bad = new OpenVikingContractHttp(
                baseUrl, "definitely-wrong", Duration.ofSeconds(3), Duration.ofSeconds(15)
        );
        JsonNode invalid = bad.get("/api/v1/fs/ls", OpenVikingContractHttp.query(
                "uri",
                "viking://resources/rd-bot/wp0-contract/auth-probe/"
        ));
        assertEquals(401, bad.statusOfLast);
        assertEquals("UNAUTHENTICATED", invalid.path("error").path("code").asText());

        OpenVikingContractHttp root = new OpenVikingContractHttp(
                baseUrl, rootKey, Duration.ofSeconds(3), Duration.ofSeconds(30)
        );
        JsonNode denied = root.get("/api/v1/fs/ls", OpenVikingContractHttp.query(
                "uri",
                "viking://resources/rd-bot/wp0-contract/auth-probe/"
        ));
        assertEquals(403, root.statusOfLast);
        assertEquals("PERMISSION_DENIED", denied.path("error").path("code").asText());
        assertTrue(denied.path("error").path("message").asText().contains("ROOT API keys cannot access tenant-scoped data APIs"));

        ensureAccount(root);
        String runId = "wp0e" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        JsonNode user = root.postJson("/api/v1/admin/accounts/rd-bot/users", Map.of("user_id", runId, "role", "user"));
        OpenVikingContractHttp client = new OpenVikingContractHttp(
                baseUrl,
                user.path("result").path("user_key").asText(),
                Duration.ofSeconds(3),
                Duration.ofSeconds(60)
        );
        String runRoot = OpenVikingProjectionUris.contractTestRoot(runId);
        String to = OpenVikingProjectionUris.contractTestDocumentRoot(runId, "2001");
        try {
            JsonNode missingTask = client.get("/api/v1/tasks/00000000-0000-4000-8000-000000000000");
            assertEquals(404, client.statusOfLast);
            assertEquals("NOT_FOUND", missingTask.path("error").path("code").asText());
            assertEquals("task", missingTask.path("error").path("details").path("type").asText());

            String body = "# busy\n";
            JsonNode firstUpload = client.uploadMarkdown("source.md", body.getBytes(StandardCharsets.UTF_8));
            JsonNode firstAdd = client.postJson("/api/v1/resources", Map.of(
                    "temp_file_id", firstUpload.path("result").path("temp_file_id").asText(),
                    "to", to,
                    "wait", false,
                    "create_parent", true,
                    "processing_mode", "semantic_and_vectors",
                    "args", Map.of("parse_mode", "no_split")
            ));
            assertEquals(200, client.statusOfLast, client.rawOfLast);
            awaitTask(client, firstAdd.path("result").path("task_id").asText());

            boolean sawBusy = false;
            JsonNode busy = null;
            for (int attempt = 0; attempt < 3 && !sawBusy; attempt++) {
                JsonNode uploadA = client.uploadMarkdown("source.md", ("# a-" + attempt + "\n").getBytes(StandardCharsets.UTF_8));
                JsonNode uploadB = client.uploadMarkdown("source.md", ("# b-" + attempt + "\n").getBytes(StandardCharsets.UTF_8));
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    String tempA = uploadA.path("result").path("temp_file_id").asText();
                    String tempB = uploadB.path("result").path("temp_file_id").asText();
                    Future<JsonNode> futureA = executor.submit(() -> postAdd(client, tempA, to));
                    JsonNode second = postAdd(client, tempB, to);
                    JsonNode first = futureA.get();
                    if ("CONFLICT".equals(first.path("error").path("code").asText())) {
                        sawBusy = true;
                        busy = first;
                    } else if ("CONFLICT".equals(second.path("error").path("code").asText())) {
                        sawBusy = true;
                        busy = second;
                    }
                }
            }
            assertTrue(sawBusy, "concurrent add_resource must reproduce path_busy CONFLICT");
            assertEquals("path_busy", busy.path("error").path("details").path("conflict_type").asText());
            assertTrue(busy.path("error").path("details").path("retryable").asBoolean());

            OpenVikingContractHttp tiny = new OpenVikingContractHttp(
                    "http://192.0.2.1:1933",
                    user.path("result").path("user_key").asText(),
                    Duration.ofMillis(200),
                    Duration.ofMillis(200)
            );
            Exception timeout = assertThrows(Exception.class, () -> tiny.get("/health"));
            assertTrue(
                    timeout instanceof HttpTimeoutException || timeout instanceof ConnectException,
                    "timeout contract must be a connect/request timeout, was " + timeout
            );
        } finally {
            client.delete(
                    "/api/v1/fs",
                    OpenVikingContractHttp.query(
                            "uri",
                            OpenVikingProjectionUris.requireCleanupUri(runRoot.replaceAll("/+$", ""), runRoot),
                            "recursive",
                            "true"
                    )
            );
        }
    }

    private static void ensureAccount(OpenVikingContractHttp root) throws Exception {
        JsonNode created = root.postJson("/api/v1/admin/accounts", Map.of(
                "account_id", "rd-bot",
                "admin_user_id", "wp0-admin"
        ));
        if (root.statusOfLast == 200) {
            assertEquals("rd-bot", created.path("result").path("account_id").asText());
            return;
        }
        assertEquals(409, root.statusOfLast, root.rawOfLast);
        assertEquals("ALREADY_EXISTS", created.path("error").path("code").asText());
    }

    private static JsonNode postAdd(OpenVikingContractHttp client, String tempFileId, String to) throws Exception {
        return client.postJson("/api/v1/resources", Map.of(
                "temp_file_id", tempFileId,
                "to", to,
                "wait", false,
                "create_parent", true,
                "processing_mode", "semantic_and_vectors",
                "args", Map.of("parse_mode", "no_split")
        ));
    }

    private static JsonNode awaitTask(OpenVikingContractHttp client, String taskId) throws Exception {
        JsonNode last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            last = client.get("/api/v1/tasks/" + taskId);
            String status = last.path("result").path("status").asText();
            if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
                return last;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("task did not settle: " + last);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
