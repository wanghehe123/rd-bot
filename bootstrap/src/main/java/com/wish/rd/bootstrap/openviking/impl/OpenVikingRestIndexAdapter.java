package com.wish.rd.bootstrap.openviking.impl;

import com.wish.rd.bootstrap.openviking.OpenVikingErrorTranslator;
import com.wish.rd.bootstrap.openviking.OpenVikingHttpExchange;
import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.wish.rd.rag.knowledge.projection.ExternalKnowledgeIndexPort;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenViking v0.4.13 适配器。请求形状固定为 WP-0 真机验证过的那一套，
 * 不按供应商文档补字段。
 *
 * <p>三条判定规则是这里的全部价值：
 *
 * <ul>
 *   <li>temp_upload 不触碰我们的资源根，失败一律"没发出去"；发送边界只在
 *       {@code POST /api/v1/resources}。</li>
 *   <li>{@code 2xx} 且 {@code root_uri} 与请求的 {@code to} 精确相等才算受理；
 *       写到别的路径比失败更糟。</li>
 *   <li>核验读回 L2 正文并比对 SHA-256，而不是只看"非空"。
 *       {@code observed_version} 只能由真实读到的 tags 与内容推上去。</li>
 * </ul>
 */
public final class OpenVikingRestIndexAdapter implements ExternalKnowledgeIndexPort {

    private static final Logger log = LoggerFactory.getLogger(OpenVikingRestIndexAdapter.class);

    private static final String PATH_TEMP_UPLOAD = "/api/v1/resources/temp_upload";
    private static final String PATH_RESOURCES = "/api/v1/resources";
    private static final String PATH_TASKS = "/api/v1/tasks/";
    private static final String PATH_FS = "/api/v1/fs";
    private static final String PATH_LS = "/api/v1/fs/ls";
    private static final String PATH_ATTRS = "/api/v1/fs/attrs";
    private static final String PATH_ABSTRACT = "/api/v1/content/abstract";
    private static final String PATH_OVERVIEW = "/api/v1/content/overview";
    private static final String PATH_READ = "/api/v1/content/read";

    private final OpenVikingHttpExchange exchange;
    private final Supplier<String> apiKeySupplier;
    private final String ownedRoot;

    public OpenVikingRestIndexAdapter(
            OpenVikingHttpExchange exchange,
            Supplier<String> apiKeySupplier,
            String ownedRoot
    ) {
        this.exchange = exchange;
        this.apiKeySupplier = apiKeySupplier;
        this.ownedRoot = ownedRoot;
    }

    @Override
    public boolean ready() {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("openviking projection has no API key; dispatch stays paused");
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

    @Override
    public ExternalKnowledgeSubmission submitUpsert(ExternalKnowledgeUpsertCommand command) {
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(command.resourceRootUri(), ownedRoot)) {
            return ExternalKnowledgeSubmission.failed(ExternalIndexFailureClass.CONTRACT_OR_DATA_ERROR,
                    "ROOT_NOT_OWNED", "refusing to write outside the owned namespace");
        }
        OpenVikingResponse uploaded = exchange.uploadMarkdown(
                command.fileName(), command.canonicalContent().getBytes(StandardCharsets.UTF_8));
        if (!uploaded.successful()) {
            return ExternalKnowledgeSubmission.failed(
                    uploadFailureClass(uploaded),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(uploaded.body()), "TEMP_UPLOAD_FAILED"),
                    describe(uploaded));
        }
        String tempFileId = uploaded.result().path("temp_file_id").asText("");
        if (tempFileId.isBlank()) {
            return ExternalKnowledgeSubmission.failed(ExternalIndexFailureClass.MALFORMED_SUCCESS,
                    "MISSING_TEMP_FILE_ID", "temp_upload returned 2xx without a temp_file_id");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("temp_file_id", tempFileId);
        request.put("to", command.resourceRootUri());
        request.put("wait", false);
        request.put("create_parent", true);
        request.put("processing_mode", "semantic_and_vectors");
        request.put("args", Map.of("parse_mode", "no_split"));
        request.put("tags", command.tags());
        request.put("tag_mode", "replace");
        OpenVikingResponse added = exchange.postJson(PATH_RESOURCES, request);
        if (added.transportFailed()) {
            return ExternalKnowledgeSubmission.failed(
                    OpenVikingErrorTranslator.classifyTransport(added.transportFailure(), added.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(added));
        }
        if (!added.successful()) {
            return ExternalKnowledgeSubmission.failed(
                    OpenVikingErrorTranslator.classify(added.status(), added.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(added.body()), "ADD_RESOURCE_FAILED"),
                    describe(added));
        }
        JsonNode result = added.result();
        String rootUri = result.path("root_uri").asText("");
        String taskId = result.path("task_id").asText("");
        if (!command.resourceRootUri().equals(rootUri)) {
            return ExternalKnowledgeSubmission.failed(ExternalIndexFailureClass.MALFORMED_SUCCESS,
                    "ROOT_URI_MISMATCH", "the remote accepted a root we did not request");
        }
        if (taskId.isBlank()) {
            return ExternalKnowledgeSubmission.failed(ExternalIndexFailureClass.MALFORMED_SUCCESS,
                    "MISSING_TASK_ID", "add_resource returned 2xx without a task id");
        }
        return ExternalKnowledgeSubmission.accepted(taskId, rootUri);
    }

    @Override
    public ExternalKnowledgeTaskSnapshot inspectTask(String remoteTaskId) {
        OpenVikingResponse response = exchange.get(PATH_TASKS + remoteTaskId, Map.of());
        if (response.transportFailed()) {
            return new ExternalKnowledgeTaskSnapshot(remoteTaskId, ExternalKnowledgeTaskState.UNKNOWN, "",
                    0L, 0L, ExternalIndexFailureClass.RETRYABLE, "TRANSPORT_FAILURE", describe(response));
        }
        if (response.status() == 404
                && "task".equals(response.body().path("error").path("details").path("type").asText())) {
            return new ExternalKnowledgeTaskSnapshot(remoteTaskId, ExternalKnowledgeTaskState.NOT_FOUND, "",
                    0L, 0L, ExternalIndexFailureClass.NONE, "NOT_FOUND", "the task is past its retention window");
        }
        if (!response.successful()) {
            return new ExternalKnowledgeTaskSnapshot(remoteTaskId, ExternalKnowledgeTaskState.UNKNOWN, "",
                    0L, 0L, OpenVikingErrorTranslator.classify(response.status(), response.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(response.body()), "TASK_QUERY_FAILED"),
                    describe(response));
        }
        JsonNode result = response.result();
        String stage = result.path("status").asText("");
        JsonNode queues = result.path("result").path("queue_status");
        return new ExternalKnowledgeTaskSnapshot(
                remoteTaskId,
                taskState(stage),
                stage,
                queues.path("Semantic").path("error_count").asLong(0L),
                queues.path("Embedding").path("error_count").asLong(0L),
                ExternalIndexFailureClass.NONE,
                "",
                "");
    }

    /**
     * 版本核验只用确定性证据：ownership/sync_version/checksum 标签、L0/L1 语义产物存在、
     * L2 正文读回后的 SHA-256。这里刻意不查 {@code search/find}。
     *
     * <p>相关性检索不是正确性判据。真机实测：同一篇已正确落库的中文文档，用短
     * token 查得到（score 0.40，命中的还是派生的 {@code .abstract.md} 而不是 L2 正文），
     * 用它自己 200 字正文查连续 61 秒 0 命中；{@code tags} 是排序后的过滤器，兜不住这件事。
     * 把这种依赖语料和嵌入模型的打分放进闸门，等于让健康文档被判成 NEEDS_HUMAN，
     * 而且升级嵌入模型会变成一次投影停摆事故。检索健康度属于旁路探针，不属于每篇文档的核验。
     *
     * @param marker 期望的远端版本标识
     * @return 核验结果
     */
    @Override
    public ExternalKnowledgeVerification verifyResource(ExternalKnowledgeVersionMarker marker) {
        List<String> failed = new ArrayList<>();

        OpenVikingResponse attrs = exchange.get(PATH_ATTRS, Map.of("uri", marker.resourceRootUri()));
        if (attrs.status() == 404) {
            return ExternalKnowledgeVerification.mismatched(List.of(ExternalKnowledgeVerification.CHECK_ROOT_URI));
        }
        if (!attrs.successful()) {
            return unavailable(attrs, "ATTRS_UNAVAILABLE");
        }
        String tags = attrs.result().path("attrs").path("tags").toString();
        if (!tags.contains("rd.owner=rd-bot")
                || !tags.contains("rd.kb_id=" + marker.knowledgeBaseId())
                || !tags.contains("rd.doc_id=" + marker.documentId())) {
            failed.add(ExternalKnowledgeVerification.CHECK_OWNERSHIP);
        }
        if (!tags.contains("rd.sync_version=" + marker.syncVersion())) {
            failed.add(ExternalKnowledgeVerification.CHECK_SYNC_VERSION);
        }
        if (!tags.contains("rd.checksum=" + marker.checksum())) {
            failed.add(ExternalKnowledgeVerification.CHECK_CHECKSUM);
        }

        OpenVikingResponse abstractNode = exchange.get(PATH_ABSTRACT, Map.of("uri", marker.resourceRootUri()));
        if (!abstractNode.successful()) {
            return unavailable(abstractNode, "ABSTRACT_UNAVAILABLE");
        }
        if (abstractNode.result().asText("").isBlank()) {
            failed.add(ExternalKnowledgeVerification.CHECK_ABSTRACT);
        }

        OpenVikingResponse overview = exchange.get(PATH_OVERVIEW, Map.of("uri", marker.resourceRootUri()));
        if (!overview.successful()) {
            return unavailable(overview, "OVERVIEW_UNAVAILABLE");
        }
        if (overview.result().asText("").isBlank()) {
            failed.add(ExternalKnowledgeVerification.CHECK_OVERVIEW);
        }

        String l2 = OpenVikingProjectionUris.l2ContentUri(
                marker.resourceRootUri(), OpenVikingProjectionUris.SOURCE_FILE_NAME);
        OpenVikingResponse read = exchange.get(PATH_READ, Map.of("uri", l2));
        if (!read.successful()) {
            return unavailable(read, "CONTENT_UNAVAILABLE");
        }
        if (!sha256(read.result().asText("")).equals(marker.checksum())) {
            failed.add(ExternalKnowledgeVerification.CHECK_CONTENT);
        }

        return failed.isEmpty()
                ? ExternalKnowledgeVerification.passed()
                : ExternalKnowledgeVerification.mismatched(failed);
    }

    @Override
    public ExternalKnowledgeRemoval removeResource(String remoteUri, boolean recursive, String expectedOwnedRoot) {
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(remoteUri, expectedOwnedRoot)
                || !OpenVikingProjectionUris.isWithinOwnedRoot(remoteUri, ownedRoot)) {
            return ExternalKnowledgeRemoval.failed(
                    ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                    "ROOT_NOT_OWNED",
                    "refusing to write outside the owned namespace",
                    false);
        }
        OpenVikingResponse deleted = exchange.delete(PATH_FS, Map.of(
                "uri", remoteUri,
                "recursive", recursive ? "true" : "false"));
        if (deleted.transportFailed()) {
            return ExternalKnowledgeRemoval.failed(
                    OpenVikingErrorTranslator.classifyTransport(deleted.transportFailure(), deleted.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(deleted),
                    deleted.requestIssued());
        }
        if (!deleted.successful()) {
            return ExternalKnowledgeRemoval.failed(
                    OpenVikingErrorTranslator.classify(deleted.status(), deleted.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(deleted.body()), "DELETE_FAILED"),
                    describe(deleted),
                    deleted.requestIssued());
        }
        int deletedCount = Math.max(0, deleted.result().path("estimated_deleted_count").asInt(0));
        return ExternalKnowledgeRemoval.accepted(deletedCount);
    }

    @Override
    public ExternalResourceProbe inspectResource(String remoteUri) {
        OpenVikingResponse attrs = exchange.get(PATH_ATTRS, Map.of("uri", remoteUri == null ? "" : remoteUri));
        if (attrs.transportFailed()) {
            return ExternalResourceProbe.unavailable(
                    OpenVikingErrorTranslator.classifyTransport(attrs.transportFailure(), attrs.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(attrs));
        }
        if (attrs.status() == 404) {
            return ExternalResourceProbe.absent();
        }
        if (!attrs.successful()) {
            return ExternalResourceProbe.unavailable(
                    OpenVikingErrorTranslator.classify(attrs.status(), attrs.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(attrs.body()), "ATTRS_UNAVAILABLE"),
                    describe(attrs));
        }
        return ExternalResourceProbe.present(parseTags(attrs.result().path("attrs").path("tags")));
    }

    @Override
    public ExternalTreeListing listTree(String ownedRootUri) {
        if (!OpenVikingProjectionUris.isWithinOwnedRoot(ownedRootUri, ownedRoot)) {
            return ExternalTreeListing.failed(
                    ExternalIndexFailureClass.CONFIGURATION_BLOCKED,
                    "ROOT_NOT_OWNED",
                    "refusing to list outside the owned namespace");
        }
        OpenVikingResponse listed = exchange.get(PATH_LS, Map.of("uri", ownedRootUri));
        if (listed.transportFailed()) {
            return ExternalTreeListing.failed(
                    OpenVikingErrorTranslator.classifyTransport(listed.transportFailure(), listed.requestIssued()),
                    "TRANSPORT_FAILURE",
                    describe(listed));
        }
        if (listed.status() == 404) {
            // owned root 还不存在是确定答复「那里什么都没有」，与 fs/attrs 的 404 同义。
            // 判成失败会让对账把「远端整卷丢失」误当「远端不可用」而整轮跳过，
            // 于是所有 IN_SYNC 观测永远得不到复核。
            return ExternalTreeListing.of(List.of());
        }
        if (!listed.successful()) {
            return ExternalTreeListing.failed(
                    OpenVikingErrorTranslator.classify(listed.status(), listed.body()),
                    firstNonBlank(OpenVikingErrorTranslator.errorCode(listed.body()), "LS_FAILED"),
                    describe(listed));
        }
        JsonNode result = listed.result();
        if (!result.isArray()) {
            return ExternalTreeListing.failed(
                    ExternalIndexFailureClass.MALFORMED_SUCCESS,
                    "LS_NOT_ARRAY",
                    "fs/ls returned 2xx without an entry array");
        }
        List<ExternalTreeListing.Entry> entries = new ArrayList<>();
        for (JsonNode node : result) {
            String uri = node.path("uri").asText("");
            String name = firstNonBlank(node.path("rel_path").asText(""), lastSegment(uri));
            boolean directory = node.path("isDir").asBoolean(false) || node.path("is_dir").asBoolean(false);
            String owner = firstNonBlank(node.path("owner").asText(""), tagValue(node.path("tags"), "rd.owner"));
            entries.add(new ExternalTreeListing.Entry(uri, name, directory, owner));
        }
        return ExternalTreeListing.of(entries);
    }

    /**
     * temp_upload 的失败分类。它写的是临时区，没有触碰我们的资源根，
     * 因此即使 5xx 也只是"没发出去"，可以安全重投；把它归为未知会白白冻结一行。
     */
    private static ExternalIndexFailureClass uploadFailureClass(OpenVikingResponse response) {
        if (response.transportFailed()) {
            return ExternalIndexFailureClass.RETRYABLE_NOT_SENT;
        }
        ExternalIndexFailureClass classified =
                OpenVikingErrorTranslator.classify(response.status(), response.body());
        return classified == ExternalIndexFailureClass.UNKNOWN_REMOTE_RESULT
                ? ExternalIndexFailureClass.RETRYABLE
                : classified;
    }

    private static ExternalKnowledgeVerification unavailable(OpenVikingResponse response, String fallbackCode) {
        ExternalIndexFailureClass failureClass = response.transportFailed()
                ? ExternalIndexFailureClass.RETRYABLE
                : OpenVikingErrorTranslator.classify(response.status(), response.body());
        return ExternalKnowledgeVerification.unavailable(
                failureClass == ExternalIndexFailureClass.NONE ? ExternalIndexFailureClass.RETRYABLE : failureClass,
                firstNonBlank(OpenVikingErrorTranslator.errorCode(response.body()), fallbackCode),
                describe(response));
    }

    private static ExternalKnowledgeTaskState taskState(String stage) {
        return switch (stage.toLowerCase(Locale.ROOT)) {
            case "completed" -> ExternalKnowledgeTaskState.COMPLETED;
            case "failed", "cancelled" -> ExternalKnowledgeTaskState.FAILED;
            case "pending", "queued" -> ExternalKnowledgeTaskState.PENDING;
            case "running", "processing" -> ExternalKnowledgeTaskState.RUNNING;
            default -> ExternalKnowledgeTaskState.UNKNOWN;
        };
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

    private static String lastSegment(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        String stripped = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int slash = stripped.lastIndexOf('/');
        return slash < 0 ? stripped : stripped.substring(slash + 1);
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return tags;
        }
        if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText("")));
            return tags;
        }
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                putTag(tags, tag.asText(""));
            }
            return tags;
        }
        putTag(tags, tagsNode.asText(""));
        return tags;
    }

    private static String tagValue(JsonNode tagsNode, String key) {
        return parseTags(tagsNode).getOrDefault(key, "");
    }

    private static void putTag(Map<String, String> tags, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        int separator = raw.indexOf('=');
        if (separator <= 0) {
            return;
        }
        tags.put(raw.substring(0, separator), raw.substring(separator + 1));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must be available", ex);
        }
    }
}
