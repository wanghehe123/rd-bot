package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结 WP-0 从真实 OpenViking v0.4.13 采集的 JSON 字段，禁止后续 DTO 按文档猜测。
 */
class OpenVikingContractJsonFixturesTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldFreezeHealthReadyAndAuthEnvelopes() throws Exception {
        JsonNode health = fixture("health.json");
        assertEquals("ok", health.path("status").asText());
        assertTrue(health.path("healthy").asBoolean());
        assertEquals("v0.4.13", health.path("version").asText());
        assertEquals("api_key", health.path("auth_mode").asText());

        JsonNode ready = fixture("ready.json");
        assertEquals("ready", ready.path("status").asText());
        assertEquals("ok", ready.path("checks").path("embedding").asText());
        assertEquals("not_configured", ready.path("checks").path("ollama").asText());

        JsonNode missing = fixture("unauthenticated.json");
        assertEquals("error", missing.path("status").asText());
        assertEquals("UNAUTHENTICATED", missing.path("error").path("code").asText());
        assertTrue(missing.path("result").isNull());

        JsonNode invalid = fixture("invalid_api_key.json");
        assertEquals("UNAUTHENTICATED", invalid.path("error").path("code").asText());
        assertEquals("Invalid API Key", invalid.path("error").path("message").asText());
    }

    @Test
    void shouldFreezeAddResourceTaskBusyDeleteAndFindContracts() throws Exception {
        JsonNode upload = fixture("temp_upload.json");
        assertTrue(upload.path("result").path("temp_file_id").asText().startsWith("upload_"));

        JsonNode added = fixture("add_resource_wait_false.json");
        assertEquals("success", added.path("result").path("status").asText());
        assertTrue(added.path("result").path("root_uri").asText().startsWith("viking://resources/rd-bot/wp0-contract/"));
        assertFalse(added.path("result").path("task_id").asText().isBlank());
        assertTrue(added.path("result").path("errors").isArray());

        JsonNode task = fixture("task_completed.json");
        assertEquals("add_resource", task.path("result").path("task_type").asText());
        assertEquals("completed", task.path("result").path("status").asText());
        assertEquals("completed", task.path("result").path("stage").asText());
        assertEquals(0, task.path("result").path("result").path("queue_status").path("Semantic").path("error_count").asInt());
        assertEquals(0, task.path("result").path("result").path("queue_status").path("Embedding").path("error_count").asInt());

        JsonNode missingTask = fixture("task_not_found.json");
        assertEquals("NOT_FOUND", missingTask.path("error").path("code").asText());
        assertEquals("task", missingTask.path("error").path("details").path("type").asText());

        JsonNode busy = fixture("conflict_busy.json");
        assertEquals("CONFLICT", busy.path("error").path("code").asText());
        assertEquals("path_busy", busy.path("error").path("details").path("conflict_type").asText());
        assertTrue(busy.path("error").path("details").path("retryable").asBoolean());

        JsonNode deleted = fixture("delete.json");
        assertTrue(deleted.path("result").has("estimated_deleted_count"));
        assertEquals("queued", deleted.path("result").path("semantic_status").asText());
        JsonNode idempotent = fixture("delete_idempotent.json");
        assertEquals(0, idempotent.path("result").path("estimated_deleted_count").asInt());

        JsonNode find = fixture("find.json");
        JsonNode resource = find.path("result").path("resources").get(0);
        assertEquals("resource", resource.path("context_type").asText());
        assertEquals(2, resource.path("level").asInt());
        assertTrue(resource.path("tags").toString().contains("rd.checksum="));

        JsonNode abstractNode = fixture("abstract.json");
        assertEquals("ok", abstractNode.path("status").asText());
        assertTrue(abstractNode.path("result").isTextual());
        assertFalse(abstractNode.path("result").asText().isBlank());

        JsonNode overview = fixture("overview.json");
        assertTrue(overview.path("result").isTextual());
        assertFalse(overview.path("result").asText().isBlank());

        JsonNode read = fixture("content_read.json");
        assertTrue(read.path("result").isTextual());
        assertTrue(read.path("result").asText().contains("RD_WP0_CONTRACT_SOURCE_NOSPLIT"));

        JsonNode attrs = fixture("attrs.json");
        assertTrue(attrs.path("result").path("attrs").path("tags").toString().contains("rd.owner=rd-bot"));
        assertTrue(attrs.path("result").path("attrs").path("tags").toString().contains("rd.checksum="));

        JsonNode listing = fixture("fs_ls.json");
        assertTrue(listing.path("result").isArray());
        assertFalse(listing.path("result").get(0).path("uri").asText().isBlank());
        assertFalse(listing.path("result").get(0).path("rel_path").asText().isBlank());
        assertTrue(listing.path("result").get(0).has("isDir"));
    }

    @Test
    void shouldFreezeErrorCodesUsedByWp0Classification() throws Exception {
        assertEquals("INVALID_URI", fixture("invalid_uri.json").path("error").path("code").asText());
        assertEquals("PERMISSION_DENIED", fixture("permission_denied_root_data.json").path("error").path("code").asText());
        assertEquals("PERMISSION_DENIED", fixture("permission_denied_private_network.json").path("error").path("code").asText());
        assertEquals("INVALID_ARGUMENT", fixture("add_resource_missing_source.json").path("error").path("code").asText());
        assertEquals("NOT_FOUND", fixture("stat_not_found.json").path("error").path("code").asText());
        assertEquals("ALREADY_EXISTS", fixture("account_already_exists.json").path("error").path("code").asText());
        JsonNode timeout = fixture("client_timeout.json");
        assertEquals("no_http_body", timeout.path("observed").asText());
        assertTrue(timeout.path("client_exceptions").toString().contains("HttpTimeoutException"));
        assertTrue(timeout.path("client_exceptions").toString().contains("ConnectException"));
        assertFalse(fixture("account_created.json").path("result").has("user_key"));
        assertFalse(fixture("user_created.json").path("result").has("user_key"));
    }

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream input = OpenVikingContractJsonFixturesTest.class.getResourceAsStream(
                "/openviking/contracts/" + name
        )) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return OBJECT_MAPPER.readTree(input);
        }
    }
}
