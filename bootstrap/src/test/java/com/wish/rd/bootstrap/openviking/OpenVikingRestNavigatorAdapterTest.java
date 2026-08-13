package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.openviking.impl.DisabledExternalKnowledgeNavigatorPort;
import com.wish.rd.bootstrap.openviking.impl.OpenVikingRestNavigatorAdapter;
import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorContent;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读导航适配器的协议判定。响应形状取自 WP-0 真机合同，不按供应商文档补字段。
 */
class OpenVikingRestNavigatorAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOCUMENT_ROOT = "viking://resources/rd-bot/kb/1001/documents/2001";
    private static final String L2_URI = DOCUMENT_ROOT + "/source.md";

    @Test
    void shouldSearchAbstractsFromTheFrozenFindEnvelope() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/search/find", 200, fixture("find.json"));

        ExternalNavigatorSearch search = adapter(exchange).searchAbstracts(
                ExternalNavigatorQuery.of("RD_WP0_CONTRACT_SOURCE_NOSPLIT", List.of("1001"), 8));

        assertEquals(ExternalIndexFailureClass.NONE, search.failureClass());
        assertEquals(1, search.hits().size());
        ExternalNavigatorSearch.Hit hit = search.hits().getFirst();
        assertTrue(hit.uri().contains("source_v2.md"));
        assertEquals(2, hit.level());
        assertTrue(hit.score() > 0.0d);
        assertTrue(hit.abstractText().contains("RD-Bot mock abstract"));
        assertTrue(hit.tags().contains("rd.owner=rd-bot"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) exchange.bodies.get("POST /api/v1/search/find");
        assertEquals("RD_WP0_CONTRACT_SOURCE_NOSPLIT", body.get("query"));
        assertEquals(OpenVikingProjectionUris.knowledgeBaseRoot("1001"), body.get("target_uri"));
        assertEquals(8, body.get("limit"));
        assertEquals(List.of("rd.kb_id=1001"), body.get("tags"));
    }

    @Test
    void shouldReadOverviewFromTheFrozenEnvelope() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/content/overview", 200, fixture("overview.json"));

        ExternalNavigatorDocument document = adapter(exchange).readOverview(DOCUMENT_ROOT);

        assertEquals(ExternalIndexFailureClass.NONE, document.failureClass());
        assertTrue(document.exists());
        assertTrue(document.overview().contains("RD-Bot mock abstract"));
        assertEquals(DOCUMENT_ROOT, exchange.queries.get("GET /api/v1/content/overview").get("uri"));
        assertEquals(1, exchange.queries.get("GET /api/v1/content/overview").size(),
                "overview must only send the frozen uri query parameter");
    }

    @Test
    void shouldReadContentFromTheFrozenEnvelopeAndSliceLocally() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/content/read", 200, fixture("content_read.json"));
        String full = MAPPER.readTree(fixture("content_read.json")).path("result").asText();

        ExternalNavigatorContent content = adapter(exchange).readContent(L2_URI, 0, 0);

        assertEquals(ExternalIndexFailureClass.NONE, content.failureClass());
        assertTrue(content.exists());
        assertEquals(full, content.content());
        assertEquals(L2_URI, exchange.queries.get("GET /api/v1/content/read").get("uri"));
        assertEquals(1, exchange.queries.get("GET /api/v1/content/read").size(),
                "read must only send the frozen uri query parameter; offset/limit are local");

        FakeExchange sliced = new FakeExchange();
        sliced.enqueue("GET /api/v1/content/read", 200, fixture("content_read.json"));
        ExternalNavigatorContent window = adapter(sliced).readContent(L2_URI, 2, 4);
        assertEquals(full.substring(2, 6), window.content());
    }

    @Test
    void shouldRefuseAnOutOfOwnedRootUriWithoutIssuingARequest() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/content/overview", 200, "{\"result\":\"should not be read\"}");
        exchange.enqueue("GET /api/v1/content/read", 200, "{\"result\":\"should not be read\"}");
        exchange.enqueue("POST /api/v1/search/find", 200, "{\"result\":{\"resources\":[]}}");
        OpenVikingRestNavigatorAdapter navigator = adapter(exchange);
        String foreign = "viking://resources/other-tenant/documents/1";

        ExternalNavigatorDocument overview = navigator.readOverview(foreign);
        ExternalNavigatorContent content = navigator.readContent(foreign, 0, 16);
        ExternalNavigatorSearch search = navigator.searchAbstracts(
                ExternalNavigatorQuery.of("leak", List.of(), 8).withTargetUri(foreign));

        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED, overview.failureClass());
        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED, content.failureClass());
        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED, search.failureClass());
        assertTrue(exchange.calls.isEmpty(), "an out-of-root URI must never reach the wire");
    }

    @Test
    void shouldPropagateTransportFailureClassOnSearch() {
        FakeExchange notSent = new FakeExchange();
        notSent.enqueueTransportFailure("POST /api/v1/search/find", new ConnectException("refused"), false);
        ExternalNavigatorSearch neverIssued = adapter(notSent).searchAbstracts(
                ExternalNavigatorQuery.of("token", List.of("1001"), 8));
        assertEquals(ExternalIndexFailureClass.RETRYABLE_NOT_SENT, neverIssued.failureClass());

        FakeExchange issued = new FakeExchange();
        issued.enqueueTransportFailure("POST /api/v1/search/find", new IOException("read timeout"), true);
        ExternalNavigatorSearch maybeApplied = adapter(issued).searchAbstracts(
                ExternalNavigatorQuery.of("token", List.of("1001"), 8));
        assertEquals(ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT, maybeApplied.failureClass());
    }

    @Test
    void shouldTreatMissingOverviewOrContentAsANegativeObservation() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /api/v1/content/overview", 404, fixture("stat_not_found.json"));
        exchange.enqueue("GET /api/v1/content/read", 404, fixture("stat_not_found.json"));
        OpenVikingRestNavigatorAdapter navigator = adapter(exchange);

        ExternalNavigatorDocument overview = navigator.readOverview(DOCUMENT_ROOT);
        ExternalNavigatorContent content = navigator.readContent(L2_URI, 0, 16);

        assertFalse(overview.exists());
        assertFalse(content.exists());
        assertEquals(ExternalIndexFailureClass.NONE, overview.failureClass(),
                "404 on overview is a negative observation, not a failed call");
        assertEquals(ExternalIndexFailureClass.NONE, content.failureClass(),
                "404 on read is a negative observation, not a failed call");
    }

    @Test
    void shouldTreatAMissingSearchTreeAsEmptyHitsRatherThanAFailure() {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("POST /api/v1/search/find", 404,
                "{\"status\":\"error\",\"error\":{\"code\":\"NOT_FOUND\"}}");

        ExternalNavigatorSearch search = adapter(exchange).searchAbstracts(
                ExternalNavigatorQuery.of("token", List.of("1001"), 8));

        assertEquals(ExternalIndexFailureClass.NONE, search.failureClass());
        assertTrue(search.hits().isEmpty());
    }

    @Test
    void shouldNotBeReadyWithoutCredentialsAndMustNotCallTheWire() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /ready", 200, fixture("ready.json"));

        OpenVikingRestNavigatorAdapter blind = new OpenVikingRestNavigatorAdapter(
                exchange, () -> "", OpenVikingProjectionUris.OWNED_ROOT);

        assertFalse(blind.ready(), "dispatching without an API key would burn the retry budget on 401s");
        assertTrue(exchange.calls.isEmpty(), "a missing key is answerable locally");
    }

    @Test
    void shouldReadReadinessFromTheSameUnenvelopedProbeAsTheWritePort() throws Exception {
        FakeExchange exchange = new FakeExchange();
        exchange.enqueue("GET /ready", 200, fixture("ready.json"));

        assertTrue(adapter(exchange).ready());
    }

    @Test
    void disabledPortBlocksEveryCall() {
        DisabledExternalKnowledgeNavigatorPort disabled = new DisabledExternalKnowledgeNavigatorPort();
        assertFalse(disabled.ready());
        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                disabled.searchAbstracts(ExternalNavigatorQuery.of("q", List.of("1001"), 8)).failureClass());
        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                disabled.readOverview(DOCUMENT_ROOT).failureClass());
        assertEquals(ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                disabled.readContent(L2_URI, 0, 8).failureClass());
    }

    private static String fixture(String name) throws IOException {
        try (var input = OpenVikingRestNavigatorAdapterTest.class.getResourceAsStream(
                "/openviking/contracts/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OpenVikingRestNavigatorAdapter adapter(OpenVikingHttpExchange exchange) {
        return new OpenVikingRestNavigatorAdapter(exchange, () -> "user-key", OpenVikingProjectionUris.OWNED_ROOT);
    }

    private static final class FakeExchange implements OpenVikingHttpExchange {

        private final Map<String, Deque<OpenVikingResponse>> responses = new LinkedHashMap<>();
        private final Map<String, Object> bodies = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> queries = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        void enqueue(String key, int status, String json) {
            responses.computeIfAbsent(key, ignored -> new ArrayDeque<>())
                    .add(OpenVikingResponse.of(status, parse(json)));
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
