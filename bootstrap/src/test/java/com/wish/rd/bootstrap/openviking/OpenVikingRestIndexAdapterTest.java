package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wish.rd.bootstrap.openviking.impl.OpenVikingRestIndexAdapter;
import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeRemoval;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeSubmission;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskSnapshot;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeTaskState;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeUpsertCommand;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVerification;
import com.wish.rd.rag.knowledge.projection.model.ExternalKnowledgeVersionMarker;
import com.wish.rd.rag.knowledge.projection.model.ExternalResourceProbe;
import com.wish.rd.rag.knowledge.projection.model.ExternalTreeListing;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenViking 适配器的协议判定。所有响应形状取自 WP-0 真机采集的合同，
 * 不按供应商文档臆测字段。
 */
class OpenVikingRestIndexAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String CONTENT = "# frozen v1\n";
    private static final String CHECKSUM = sha256(CONTENT);

    @Test
    void shouldUploadThenAddResourceAtTheExactRequestedRoot() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 200,
                """
                {"result":{"temp_file_id":"upload_abc"}}""");
        exchange.enqueue("POST /api/v1/resources", 200,
                """
                {"result":{"status":"success","root_uri":"%s","task_id":"task-9"}}""".formatted(ROOT));

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertTrue(submission.accepted());
        assertEquals("task-9", submission.remoteTaskId());
        assertEquals(ROOT, submission.rootUri());
        assertEquals(CONTENT, new String(exchange.uploads.getFirst(), StandardCharsets.UTF_8));
        JsonNode add = MAPPER.valueToTree(exchange.bodies.get("POST /api/v1/resources"));
        assertEquals("upload_abc", add.path("temp_file_id").asText());
        assertEquals(ROOT, add.path("to").asText());
        assertFalse(add.path("wait").asBoolean(), "the write must be async so the worker never blocks on indexing");
        assertTrue(add.path("create_parent").asBoolean());
        assertEquals("semantic_and_vectors", add.path("processing_mode").asText());
        assertEquals("no_split", add.path("args").path("parse_mode").asText());
        assertEquals("replace", add.path("tag_mode").asText(),
                "version tags must replace, otherwise a stale rd.sync_version survives the update");
        assertTrue(add.path("tags").toString().contains("rd.sync_version=1"));
        assertTrue(add.path("tags").toString().contains("rd.checksum=" + CHECKSUM));
    }

    @Test
    void shouldTreatAFailedTempUploadAsNeverSent() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 500, """
                {"error":{"code":"INTERNAL"}}""");

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertEquals(ExternalIndexFailureClass.RETRYABLE, submission.failureClass(),
                "a temp upload never touches our resource root, so it is always safe to retry");
        assertTrue(submission.failureClass().safeToResubmit());
    }

    @Test
    void shouldTreatAConnectFailureOnAddResourceAsNeverSent() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 200, """
                {"result":{"temp_file_id":"upload_abc"}}""");
        exchange.enqueueTransportFailure("POST /api/v1/resources", new ConnectException("refused"), false);

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertEquals(ExternalIndexFailureClass.RETRYABLE_NOT_SENT, submission.failureClass());
    }

    @Test
    void shouldTreatAnIssuedRequestThatLostItsAnswerAsUnknown() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 200, """
                {"result":{"temp_file_id":"upload_abc"}}""");
        exchange.enqueueTransportFailure("POST /api/v1/resources", new IOException("read timeout"), true);

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertEquals(ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT, submission.failureClass(),
                "bytes already on the wire may have been applied, so this must never be replayed");
        assertFalse(submission.failureClass().safeToResubmit());
    }

    @Test
    void shouldClassifyPathBusyAsRetryable() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 200, """
                {"result":{"temp_file_id":"upload_abc"}}""");
        exchange.enqueue("POST /api/v1/resources", 409, """
                {"error":{"code":"CONFLICT","details":{"conflict_type":"path_busy","retryable":true}}}""");

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertEquals(ExternalIndexFailureClass.RETRYABLE_BUSY, submission.failureClass());
    }

    @Test
    void shouldRejectASuccessEnvelopeWhoseRootUriDiffers() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/resources/temp_upload", 200, """
                {"result":{"temp_file_id":"upload_abc"}}""");
        exchange.enqueue("POST /api/v1/resources", 200, """
                {"result":{"status":"success","root_uri":"viking://resources/rd-bot/kb/1001/documents/9999",
                "task_id":"task-9"}}""");

        ExternalKnowledgeSubmission submission = adapter(exchange).submitUpsert(command());

        assertEquals(ExternalIndexFailureClass.MALFORMED_SUCCESS, submission.failureClass(),
                "writing to a root we did not ask for is worse than failing");
    }

    @Test
    void shouldReportACompletedTaskWithItsQueueErrorCounts() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/tasks/task-9", 200, """
                {"result":{"status":"completed","result":{"queue_status":{
                "Semantic":{"error_count":0},"Embedding":{"error_count":2}}}}}""");

        ExternalKnowledgeTaskSnapshot snapshot = adapter(exchange).inspectTask("task-9");

        assertEquals(ExternalKnowledgeTaskState.COMPLETED, snapshot.state());
        assertEquals(2L, snapshot.embeddingErrorCount());
        assertFalse(snapshot.completedCleanly());
    }

    @Test
    void shouldReportAMissingTaskAsNotFoundRatherThanAnError() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/tasks/task-9", 404, """
                {"error":{"code":"NOT_FOUND","details":{"type":"task"}}}""");

        ExternalKnowledgeTaskSnapshot snapshot = adapter(exchange).inspectTask("task-9");

        assertEquals(ExternalKnowledgeTaskState.NOT_FOUND, snapshot.state());
        assertTrue(snapshot.failureClass().success(),
                "a retained-window expiry is a normal observation, not a call failure");
    }

    @Test
    void shouldVerifyTagsAndContentBeforeDeclaringSuccess() {
        FakeExchange exchange = verifiableRemote();

        ExternalKnowledgeVerification verification = adapter(exchange).verifyResource(marker(1L));

        assertTrue(verification.verified(), verification.failedChecks().toString());
        assertTrue(exchange.calls.stream().noneMatch(call -> call.contains("search")),
                "relevance ranking must never gate a version-verification decision");
    }

    @Test
    void shouldFailVerificationWhenTheRemoteStillCarriesAnOlderVersion() {
        FakeExchange exchange = verifiableRemote();
        exchange.replace("GET /api/v1/fs/attrs", 200, """
                {"result":{"attrs":{"tags":["rd.owner=rd-bot","rd.kb_id=1001","rd.doc_id=2001",
                "rd.sync_version=1","rd.checksum=%s"]}}}""".formatted(CHECKSUM));

        ExternalKnowledgeVerification verification = adapter(exchange).verifyResource(marker(2L));

        assertFalse(verification.verified());
        assertTrue(verification.failedChecks().contains(ExternalKnowledgeVerification.CHECK_SYNC_VERSION));
        assertEquals(ExternalIndexFailureClass.MALFORMED_SUCCESS, verification.failureClass());
    }

    /**
     * 真机实测：同一篇已正确落库的中文文档，短 token 查得到、200 字自身正文查 61 秒 0 命中。
     * 相关性打分依赖语料和嵌入模型，进了闸门就会把健康文档判成 NEEDS_HUMAN。
     */
    @Test
    void shouldNotLetAnEmptySearchResultBlockAVersionThatIsProvablyCorrect() {
        FakeExchange exchange = verifiableRemote();
        exchange.replace("POST /api/v1/search/find", 200, """
                {"result":{"resources":[]}}""");

        ExternalKnowledgeVerification verification = adapter(exchange).verifyResource(marker(1L));

        assertTrue(verification.verified(),
                "tags, L0/L1 artefacts and the L2 SHA-256 already prove the remote holds this version");
    }

    @Test
    void shouldReportVerificationUnavailableRatherThanMismatchedWhenTheRemoteIsDown() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/fs/attrs", 503, """
                {"error":{"code":"UNAVAILABLE"}}""");

        ExternalKnowledgeVerification verification = adapter(exchange).verifyResource(marker(1L));

        assertFalse(verification.verified());
        assertTrue(verification.failedChecks().isEmpty(),
                "a call that never answered must not be reported as a content mismatch");
        assertFalse(verification.failureClass().success());
    }

    /**
     * 就绪判定必须照着真机采集的 {@code contracts/ready.json} 解析。手写一个
     * {@code {"result":...}} 信封会让单测全绿而真机永远 not ready，投影一行都发不出去。
     */
    @Test
    void shouldReadReadinessFromTheRecordedUnenvelopedProbe() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /ready", 200, fixture("ready.json"));

        assertTrue(adapter(exchange).ready());
    }

    @Test
    void shouldNotBeReadyWithoutCredentials() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /ready", 200, fixture("ready.json"));

        OpenVikingRestIndexAdapter blind = new OpenVikingRestIndexAdapter(
                exchange, () -> "", OpenVikingProjectionUris.OWNED_ROOT);

        assertFalse(blind.ready(), "dispatching without an API key would burn the retry budget on 401s");
        assertTrue(exchange.calls.isEmpty(), "a missing key is answerable locally");
    }

    @Test
    void shouldRemoveAResourceAndReadDeletedCountFromTheFrozenEnvelope() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("DELETE /api/v1/fs", 200, fixture("delete.json"));

        ExternalKnowledgeRemoval removal = adapter(exchange).removeResource(ROOT, true, OpenVikingProjectionUris.OWNED_ROOT);

        assertTrue(removal.removed());
        assertEquals(3, removal.deletedCount());
        assertEquals(ExternalIndexFailureClass.NONE, removal.failureClass());
        assertTrue(removal.requestIssued());
        assertEquals(ROOT, exchange.queries.get("DELETE /api/v1/fs").get("uri"));
        assertEquals("true", exchange.queries.get("DELETE /api/v1/fs").get("recursive"));
    }

    @Test
    void shouldTreatAZeroDeletedCountAsAnIdempotentSuccess() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("DELETE /api/v1/fs", 200, fixture("delete_idempotent.json"));

        ExternalKnowledgeRemoval removal = adapter(exchange).removeResource(ROOT, true, OpenVikingProjectionUris.OWNED_ROOT);

        assertTrue(removal.removed(), "repeating a delete is success, not a missing-resource failure");
        assertEquals(0, removal.deletedCount());
        assertEquals(ExternalIndexFailureClass.NONE, removal.failureClass());
    }

    @Test
    void shouldRefuseToDeleteOutsideTheOwnedRootWithoutIssuingARequest() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("DELETE /api/v1/fs", 200, """
                {"result":{"estimated_deleted_count":9}}""");

        ExternalKnowledgeRemoval removal = adapter(exchange).removeResource(
                "viking://resources/rd-bot/kb/9999/documents/1",
                true,
                "viking://resources/rd-bot/kb/1001/");

        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED, removal.failureClass());
        assertFalse(removal.removed());
        assertFalse(removal.requestIssued());
        assertTrue(exchange.calls.isEmpty(), "an out-of-root URI must never reach the wire");
    }

    @Test
    void shouldPreserveRequestIssuedOnADeleteTransportFailure() {
        FakeExchange notSent = new FakeExchange();
        notSent.enqueueTransportFailure("DELETE /api/v1/fs", new ConnectException("refused"), false);
        ExternalKnowledgeRemoval neverIssued = adapter(notSent).removeResource(
                ROOT, false, OpenVikingProjectionUris.OWNED_ROOT);
        assertFalse(neverIssued.requestIssued());
        assertEquals(ExternalIndexFailureClass.RETRYABLE_NOT_SENT, neverIssued.failureClass());

        FakeExchange issued = new FakeExchange();
        issued.enqueueTransportFailure("DELETE /api/v1/fs", new IOException("read timeout"), true);
        ExternalKnowledgeRemoval maybeApplied = adapter(issued).removeResource(
                ROOT, false, OpenVikingProjectionUris.OWNED_ROOT);
        assertTrue(maybeApplied.requestIssued());
        assertEquals(ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT, maybeApplied.failureClass());
        assertFalse(maybeApplied.removed(), "an unknown delete must not be treated as success");
    }

    @Test
    void shouldReportAMissingResourceAsAbsentRatherThanACallFailure() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/fs/attrs", 404, fixture("stat_not_found.json"));

        ExternalResourceProbe probe = adapter(exchange).inspectResource(ROOT);

        assertFalse(probe.exists());
        assertTrue(probe.tags().isEmpty());
        assertEquals(ExternalIndexFailureClass.NONE, probe.failureClass(),
                "404 on attrs is a negative observation, not a failed call");
    }

    @Test
    void shouldListTreeEntriesFromTheFrozenLsEnvelope() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/fs/ls", 200, fixture("fs_ls.json"));

        ExternalTreeListing listing = adapter(exchange).listTree(OpenVikingProjectionUris.OWNED_ROOT);

        assertEquals(ExternalIndexFailureClass.NONE, listing.failureClass());
        assertEquals(1, listing.entries().size());
        ExternalTreeListing.Entry entry = listing.entries().getFirst();
        assertTrue(entry.uri().contains("source_v2.md"));
        assertEquals("source_v2.md", entry.name());
        assertFalse(entry.directory());
        assertEquals("viking://resources/rd-bot/", exchange.queries.get("GET /api/v1/fs/ls").get("uri"));
    }

    /**
     * owned root 不存在是确定答复「那里什么都没有」，和 attrs 的 404 同义。判成失败会让
     * 对账把「远端整卷丢失」当成「远端不可用」而跳过复核。
     */
    @Test
    void shouldTreatAMissingOwnedRootAsAnEmptyTreeRatherThanAFailure() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/fs/ls", 404,
                "{\"status\":\"error\",\"result\":null,\"error\":{\"code\":\"NOT_FOUND\","
                        + "\"message\":\"Directory not found: viking://resources/rd-bot/\"}}");

        ExternalTreeListing listing = adapter(exchange).listTree(OpenVikingProjectionUris.OWNED_ROOT);

        assertEquals(ExternalIndexFailureClass.NONE, listing.failureClass(),
                "404 on ls is a negative observation, not a failed call");
        assertTrue(listing.entries().isEmpty());
    }

    @Test
    void shouldNotBeReadyWhenTheEmbeddingBackendIsDown() throws Exception {
        ObjectNode degraded = (ObjectNode) MAPPER.readTree(fixture("ready.json"));
        degraded.put("status", "degraded");
        ((ObjectNode) degraded.path("checks")).put("embedding", "error");
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /ready", 200, degraded.toString());

        assertFalse(adapter(exchange).ready());
    }

    private static String fixture(String name) throws IOException {
        try (var input = OpenVikingRestIndexAdapterTest.class.getResourceAsStream(
                "/openviking/contracts/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OpenVikingRestIndexAdapter adapter(OpenVikingHttpExchange exchange) {
        return new OpenVikingRestIndexAdapter(exchange, () -> "user-key", OpenVikingProjectionUris.OWNED_ROOT);
    }

    private static ExternalKnowledgeUpsertCommand command() {
        return new ExternalKnowledgeUpsertCommand(
                "OPENVIKING", "1001", "2001", 1L, CHECKSUM, ROOT,
                OpenVikingProjectionUris.SOURCE_FILE_NAME, CONTENT,
                OpenVikingProjectionUris.ownershipMarker("1001", "2001"),
                OpenVikingProjectionUris.ownershipTags("1001", "2001", 1L, CHECKSUM),
                "OPENVIKING:doc:2001:1:UPSERT_DOCUMENT");
    }

    private static ExternalKnowledgeVersionMarker marker(long syncVersion) {
        return new ExternalKnowledgeVersionMarker(
                "OPENVIKING", ROOT, OpenVikingProjectionUris.ownershipMarker("1001", "2001"),
                "1001", "2001", syncVersion, CHECKSUM);
    }

    private static FakeExchange verifiableRemote() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/fs/attrs", 200, """
                {"result":{"attrs":{"tags":["rd.owner=rd-bot","rd.kb_id=1001","rd.doc_id=2001",
                "rd.sync_version=1","rd.checksum=%s"]}}}""".formatted(CHECKSUM));
        exchange.enqueue("GET /api/v1/content/abstract", 200, """
                {"result":"a frozen abstract"}""");
        exchange.enqueue("GET /api/v1/content/overview", 200, """
                {"result":"a frozen overview"}""");
        exchange.enqueue("GET /api/v1/content/read", 200,
                MAPPER.createObjectNode().put("result", CONTENT).toString());
        exchange.enqueue("POST /api/v1/search/find", 200, """
                {"result":{"resources":[{"uri":"%s"}]}}""".formatted(ROOT));
        return exchange;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class FakeExchange implements OpenVikingHttpExchange {

        private final Map<String, Deque<OpenVikingResponse>> responses = new LinkedHashMap<>();
        private final Map<String, Object> bodies = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> queries = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final List<byte[]> uploads = new ArrayList<>();

        void enqueue(String key, int status, String json) {
            responses.computeIfAbsent(key, ignored -> new ArrayDeque<>())
                    .add(OpenVikingResponse.of(status, parse(json)));
        }

        void replace(String key, int status, String json) {
            Deque<OpenVikingResponse> queue = new ArrayDeque<>();
            queue.add(OpenVikingResponse.of(status, parse(json)));
            responses.put(key, queue);
        }

        void enqueueTransportFailure(String key, Throwable failure, boolean requestIssued) {
            responses.computeIfAbsent(key, ignored -> new ArrayDeque<>())
                    .add(OpenVikingResponse.transportFailed(failure, requestIssued));
        }

        @Override
        public OpenVikingResponse get(String path, Map<String, String> query) {
            queries.put("GET " + path, Map.copyOf(query));
            return answer("GET " + path);
        }

        @Override
        public OpenVikingResponse delete(String path, Map<String, String> query) {
            queries.put("DELETE " + path, Map.copyOf(query));
            return answer("DELETE " + path);
        }

        @Override
        public OpenVikingResponse postJson(String path, Object body) {
            bodies.put("POST " + path, body);
            return answer("POST " + path);
        }

        @Override
        public OpenVikingResponse uploadMarkdown(String fileName, byte[] content) {
            uploads.add(content);
            return answer("POST /api/v1/resources/temp_upload");
        }

        private OpenVikingResponse answer(String key) {
            calls.add(key);
            Deque<OpenVikingResponse> queue = responses.get(key);
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("unexpected call: " + key + " after " + calls);
            }
            return queue.size() == 1 ? queue.peek() : queue.poll();
        }

        private static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }
}
