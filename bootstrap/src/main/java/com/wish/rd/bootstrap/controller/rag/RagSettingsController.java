package com.wish.rd.bootstrap.controller.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 运行时配置只读控制器。
 *
 * <p>对外暴露 /rag/settings 接口，聚合返回上传限制（文件/请求大小）、向量配置
 * （collection、维度、度量）、查询改写开关、全局限流、记忆摘要策略、AI 默认模型
 * （chat/embedding/rerank）及脱敏后的 provider 密钥等前端配置项。
 */
@RestController
public class RagSettingsController {

    private final DataSize maxFileSize;
    private final DataSize maxRequestSize;
    private final String collectionName;
    private final int dimension;
    private final String metricType;
    private final boolean queryRewriteEnabled;
    private final boolean rateLimitEnabled;
    private final int rateLimitMaxConcurrent;
    private final int rateLimitMaxWaitSeconds;
    private final int rateLimitLeaseSeconds;
    private final int rateLimitPollIntervalMs;
    private final int historyKeepTurns;
    private final boolean summaryEnabled;
    private final int summaryStartTurns;
    private final int summaryMaxChars;
    private final int titleMaxLength;
    private final String mockProviderApiKey;
    private final String chatDefaultModel;
    private final String chatDeepThinkingModel;
    private final String embeddingDefaultModel;
    private final String rerankDefaultModel;
    private final int streamMessageChunkSize;

    public RagSettingsController(
            @Value("${spring.servlet.multipart.max-file-size:50MB}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:100MB}") DataSize maxRequestSize,
            @Value("${rag.default.collection-name:rd_bot_collection}") String collectionName,
            @Value("${rag.default.dimension:768}") int dimension,
            @Value("${rag.default.metric-type:COSINE}") String metricType,
            @Value("${rag.query-rewrite.enabled:true}") boolean queryRewriteEnabled,
            @Value("${rag.rate-limit.global.enabled:false}") boolean rateLimitEnabled,
            @Value("${rag.rate-limit.global.max-concurrent:4}") int rateLimitMaxConcurrent,
            @Value("${rag.rate-limit.global.max-wait-seconds:20}") int rateLimitMaxWaitSeconds,
            @Value("${rag.rate-limit.global.lease-seconds:600}") int rateLimitLeaseSeconds,
            @Value("${rag.rate-limit.global.poll-interval-ms:200}") int rateLimitPollIntervalMs,
            @Value("${rag.memory.history-keep-turns:8}") int historyKeepTurns,
            @Value("${rag.memory.summary-enabled:false}") boolean summaryEnabled,
            @Value("${rag.memory.summary-start-turns:9}") int summaryStartTurns,
            @Value("${rag.memory.summary-max-chars:200}") int summaryMaxChars,
            @Value("${rag.memory.title-max-length:30}") int titleMaxLength,
            @Value("${rd.ai.providers.mock.api-key:}") String mockProviderApiKey,
            @Value("${rd.ai.chat.default-model:deterministic-repair-model}") String chatDefaultModel,
            @Value("${rd.ai.chat.deep-thinking-model:deterministic-repair-model}") String chatDeepThinkingModel,
            @Value("${rd.ai.embedding.default-model:deterministic-text-score}") String embeddingDefaultModel,
            @Value("${rd.ai.rerank.default-model:deterministic-score-sort}") String rerankDefaultModel,
            @Value("${rd.ai.stream.message-chunk-size:256}") int streamMessageChunkSize
    ) {
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.metricType = metricType;
        this.queryRewriteEnabled = queryRewriteEnabled;
        this.rateLimitEnabled = rateLimitEnabled;
        this.rateLimitMaxConcurrent = rateLimitMaxConcurrent;
        this.rateLimitMaxWaitSeconds = rateLimitMaxWaitSeconds;
        this.rateLimitLeaseSeconds = rateLimitLeaseSeconds;
        this.rateLimitPollIntervalMs = rateLimitPollIntervalMs;
        this.historyKeepTurns = historyKeepTurns;
        this.summaryEnabled = summaryEnabled;
        this.summaryStartTurns = summaryStartTurns;
        this.summaryMaxChars = summaryMaxChars;
        this.titleMaxLength = titleMaxLength;
        this.mockProviderApiKey = mockProviderApiKey;
        this.chatDefaultModel = chatDefaultModel;
        this.chatDeepThinkingModel = chatDeepThinkingModel;
        this.embeddingDefaultModel = embeddingDefaultModel;
        this.rerankDefaultModel = rerankDefaultModel;
        this.streamMessageChunkSize = streamMessageChunkSize;
    }

    @GetMapping("/rag/settings")
    public Object settings() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("upload", Map.of(
                "maxFileSize", maxFileSize.toBytes(),
                "maxRequestSize", maxRequestSize.toBytes()
        ));
        response.put("rag", ragSettings());
        response.put("ai", aiSettings());
        return response;
    }

    private Map<String, Object> ragSettings() {
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("default", Map.of(
                "collectionName", collectionName,
                "dimension", dimension,
                "metricType", metricType
        ));
        rag.put("queryRewrite", Map.of("enabled", queryRewriteEnabled));
        rag.put("rateLimit", Map.of("global", Map.of(
                "enabled", rateLimitEnabled,
                "maxConcurrent", rateLimitMaxConcurrent,
                "maxWaitSeconds", rateLimitMaxWaitSeconds,
                "leaseSeconds", rateLimitLeaseSeconds,
                "pollIntervalMs", rateLimitPollIntervalMs
        )));
        rag.put("memory", Map.of(
                "historyKeepTurns", historyKeepTurns,
                "summaryEnabled", summaryEnabled,
                "summaryStartTurns", summaryStartTurns,
                "summaryMaxChars", summaryMaxChars,
                "titleMaxLength", titleMaxLength
        ));
        return rag;
    }

    private Map<String, Object> aiSettings() {
        Map<String, Object> ai = new LinkedHashMap<>();
        Map<String, Object> providers = new LinkedHashMap<>();
        if (!mockProviderApiKey.isBlank()) {
            providers.put("mock", Map.of(
                    "url", "local://deterministic",
                    "apiKey", maskApiKey(mockProviderApiKey),
                    "endpoints", Map.of("chat", "/rag/v3/chat")
            ));
        }
        ai.put("providers", providers);
        ai.put("chat", Map.of(
                "defaultModel", chatDefaultModel,
                "deepThinkingModel", chatDeepThinkingModel,
                "candidates", java.util.List.of(Map.of(
                        "id", "local-chat",
                        "provider", "mock",
                        "model", chatDefaultModel,
                        "priority", 1,
                        "enabled", true,
                        "supportsThinking", false
                ))
        ));
        ai.put("embedding", Map.of(
                "defaultModel", embeddingDefaultModel,
                "candidates", java.util.List.of(Map.of(
                        "id", "local-embedding",
                        "provider", "mock",
                        "model", embeddingDefaultModel,
                        "dimension", dimension,
                        "priority", 1,
                        "enabled", true,
                        "supportsThinking", false
                ))
        ));
        ai.put("rerank", Map.of(
                "defaultModel", rerankDefaultModel,
                "candidates", java.util.List.of(Map.of(
                        "id", "local-rerank",
                        "provider", "mock",
                        "model", rerankDefaultModel,
                        "priority", 1,
                        "enabled", true,
                        "supportsThinking", false
                ))
        ));
        ai.put("selection", Map.of(
                "failureThreshold", 3,
                "openDurationMs", 30000
        ));
        ai.put("stream", Map.of("messageChunkSize", streamMessageChunkSize));
        return ai;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }
}
