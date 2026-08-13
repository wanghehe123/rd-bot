package com.wish.rd.bootstrap.openviking.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.bootstrap.openviking.OpenVikingErrorTranslator;
import com.wish.rd.bootstrap.openviking.OpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;
import com.wish.rd.rag.knowledge.projection.OpenVikingProjectionUris;
import com.wish.rd.rag.knowledge.projection.model.ExternalIndexFailureClass;
import com.wish.rd.rag.retrieval.navigator.ExternalKnowledgeNavigatorPort;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorContent;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorDocument;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorQuery;
import com.wish.rd.rag.retrieval.navigator.model.ExternalNavigatorSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenViking 只读导航适配器。请求形状固定为 WP-0 真机验证过的
 * {@code search/find}、{@code content/overview}、{@code content/read}，
 * 不按供应商文档补字段。
 *
 * <p>404 语义与写端口的 {@code fs/ls}/{@code fs/attrs} 对齐：那是确定的否定观测，
 * {@code failureClass=NONE}。检索树上没有候选、overview/正文不存在，都不能当成
 * 远端不可用，否则旁路探测会把"没召回"和"服务挂了"混为一谈。
 *
 * <p>{@code ready()} 与每次请求共用同一个 API key supplier，禁止各判一套。
 */
public final class OpenVikingRestNavigatorAdapter implements ExternalKnowledgeNavigatorPort {

    private static final Logger log = LoggerFactory.getLogger(OpenVikingRestNavigatorAdapter.class);

    private static final String PATH_FIND = "/api/v1/search/find";
    private static final String PATH_OVERVIEW = "/api/v1/content/overview";
    private static final String PATH_READ = "/api/v1/content/read";

    private final OpenVikingHttpExchange exchange;
    private final Supplier<String> apiKeySupplier;
    private final String ownedRoot;

    /**
     * @param exchange       与写端口共用的 HTTP 传输
     * @param apiKeySupplier 与写端口共用的 key 解析
     * @param ownedRoot      允许读取的 URI 前缀
     */
    public OpenVikingRestNavigatorAdapter(
            OpenVikingHttpExchange exchange,
            Supplier<String> apiKeySupplier,
            String ownedRoot
    ) {
        this.exchange = exchange;
        this.apiKeySupplier = apiKeySupplier;
        this.ownedRoot = ownedRoot;
    }

    /**
     * 与写端口同一套判定：无 key 不发请求；{@code /ready} 读未信封探针。
     *
     * @return 可检索时为 true
     */
    @Override
    public boolean ready() {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("openviking navigator has no API key; remote reads stay blocked");
            return false;
        }
        OpenVikingResponse response = exchange.get("/ready", Map.of());
        if (!response.successful()) {
            return false;
        }
        // /health 与 /ready 不走 {"result":...} 信封，见 contracts/ready.json。
        JsonNode probe = response.body();
        return "ready".equals(probe.path("status").asText())
                && "ok".equals(probe.path("checks").path("embedding").asText());
    }

    /**
     * L0：{@code POST /api/v1/search/find}。越界 URI 不发请求。
     *
     * @param query 查询与范围
     * @return 命中或已分类失败
     */
    @Override
    public ExternalNavigatorSearch searchAbstracts(ExternalNavigatorQuery query) {
        ExternalNavigatorQuery safeQuery = query == null
                ? ExternalNavigatorQuery.of("", List.of(), 1)
                : query;
        String targetUri = resolveTargetUri(safeQuery);
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(targetUri, ownedRoot)) {
            return ExternalNavigatorSearch.failed(
                    ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                    "ROOT_NOT_OWNED",
                    "refusing to search outside the owned namespace");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", safeQuery.query());
        request.put("target_uri", targetUri);
        request.put("limit", safeQuery.limit());
        request.put("tags", scopeTags(safeQuery.knowledgeBaseIds()));
        OpenVikingResponse response = exchange.postJson(PATH_FIND, request);
        if (response.transportFailed()) {
            return ExternalNavigatorSearch.failed(
                    OpenVikingErrorTranslator.classifyTransport(response.transportFailure(), response.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(response));
        }
        if (response.status() == 404) {
            return ExternalNavigatorSearch.of(List.of());
        }
        if (!response.successful()) {
            return ExternalNavigatorSearch.failed(
                    OpenVikingErrorTranslator.classify(response.status(), response.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(response.body()), "FIND_FAILED"),
                    describe(response));
        }
        JsonNode resources = response.result().path("resources");
        if (!resources.isArray()) {
            return ExternalNavigatorSearch.failed(
                    ExternalIndexFailureClass.MALFORMED_SUCCESS,
                    "FIND_RESOURCES_NOT_ARRAY",
                    "search/find returned 2xx without a resources array");
        }
        List<ExternalNavigatorSearch.Hit> hits = new ArrayList<>();
        for (JsonNode node : resources) {
            hits.add(new ExternalNavigatorSearch.Hit(
                    node.path("uri").asText(""),
                    node.path("level").asInt(0),
                    node.path("score").asDouble(0.0d),
                    node.path("abstract").asText(""),
                    stringList(node.path("tags"))));
        }
        return ExternalNavigatorSearch.of(hits);
    }

    /**
     * L1：{@code GET /api/v1/content/overview}。404 视为缺席。
     *
     * @param resourceUri 资源 URI
     * @return overview 或缺席/失败
     */
    @Override
    public ExternalNavigatorDocument readOverview(String resourceUri) {
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(resourceUri, ownedRoot)) {
            return ExternalNavigatorDocument.failed(
                    resourceUri,
                    ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                    "ROOT_NOT_OWNED",
                    "refusing to read outside the owned namespace");
        }
        OpenVikingResponse response = exchange.get(PATH_OVERVIEW, Map.of("uri", resourceUri));
        if (response.transportFailed()) {
            return ExternalNavigatorDocument.failed(
                    resourceUri,
                    OpenVikingErrorTranslator.classifyTransport(response.transportFailure(), response.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(response));
        }
        if (response.status() == 404) {
            return ExternalNavigatorDocument.absent(resourceUri);
        }
        if (!response.successful()) {
            return ExternalNavigatorDocument.failed(
                    resourceUri,
                    OpenVikingErrorTranslator.classify(response.status(), response.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(response.body()), "OVERVIEW_FAILED"),
                    describe(response));
        }
        return ExternalNavigatorDocument.of(resourceUri, response.result().asText(""));
    }

    /**
     * L2：{@code GET /api/v1/content/read}，只传 {@code uri}；offset/limit 本地切片。
     *
     * @param resourceUri 资源 URI
     * @param offset      本地切片起点
     * @param limit       本地切片长度
     * @return 正文或缺席/失败
     */
    @Override
    public ExternalNavigatorContent readContent(String resourceUri, int offset, int limit) {
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(resourceUri, ownedRoot)) {
            return ExternalNavigatorContent.failed(
                    resourceUri,
                    offset,
                    limit,
                    ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                    "ROOT_NOT_OWNED",
                    "refusing to read outside the owned namespace");
        }
        OpenVikingResponse response = exchange.get(PATH_READ, Map.of("uri", resourceUri));
        if (response.transportFailed()) {
            return ExternalNavigatorContent.failed(
                    resourceUri,
                    offset,
                    limit,
                    OpenVikingErrorTranslator.classifyTransport(response.transportFailure(), response.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(response));
        }
        if (response.status() == 404) {
            return ExternalNavigatorContent.absent(resourceUri, offset, limit);
        }
        if (!response.successful()) {
            return ExternalNavigatorContent.failed(
                    resourceUri,
                    offset,
                    limit,
                    OpenVikingErrorTranslator.classify(response.status(), response.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(response.body()), "READ_FAILED"),
                    describe(response));
        }
        return ExternalNavigatorContent.of(
                resourceUri, slice(response.result().asText(""), offset, limit), offset, limit);
    }

    private String resolveTargetUri(ExternalNavigatorQuery query) {
        if (!query.targetUri().isBlank()) {
            return query.targetUri();
        }
        if (query.knowledgeBaseIds().size() == 1) {
            try {
                return OpenVikingProjectionUris.knowledgeBaseRoot(query.knowledgeBaseIds().getFirst());
            } catch (IllegalArgumentException ignored) {
                return ownedRoot;
            }
        }
        return ownedRoot;
    }

    private static List<String> scopeTags(List<String> knowledgeBaseIds) {
        List<String> tags = new ArrayList<>();
        for (String knowledgeBaseId : knowledgeBaseIds) {
            try {
                OpenVikingProjectionUris.knowledgeBaseRoot(knowledgeBaseId);
                tags.add("rd.kb_id=" + knowledgeBaseId);
            } catch (IllegalArgumentException ignored) {
                // 非数字 ID 不能编进冻结合同的 tag 过滤器，交给 target_uri 收窄。
            }
        }
        return List.copyOf(tags);
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String slice(String value, int offset, int limit) {
        String text = value == null ? "" : value;
        int from = Math.min(Math.max(offset, 0), text.length());
        if (limit <= 0) {
            return text.substring(from);
        }
        return text.substring(from, Math.min(text.length(), from + limit));
    }

    private static String describe(OpenVikingResponse response) {
        if (response.transportFailed()) {
            return OpenVikingErrorTranslator.safeMessage(String.valueOf(response.transportFailure()));
        }
        String message = response.body().path("error").path("message").asText("");
        return OpenVikingErrorTranslator.safeMessage(
                message.isBlank() ? "http " + response.status() : "http " + response.status() + ": " + message);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
