package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.draft.TaskDraftModelPort;
import com.wish.rd.engine.draft.model.TaskDraftRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Anthropic-compatible structured task-draft adapter with environment-only credentials. */
@Component
@ConditionalOnProperty(prefix = "rd.task-draft", name = "enabled", havingValue = "true")
public final class AnthropicTaskDraftModelAdapter implements TaskDraftModelPort {

    private static final String SYSTEM_PROMPT = """
            You complete RD task forms. Return one JSON object only, without markdown.
            BUG_FIX fields: actualBehavior, expectedBehavior, reproductionSteps, affectedScope,
            acceptanceCriteria, missingFields, evidence, confidence.
            REQUIREMENT fields: requirementBody, expectedResult, acceptanceCriteria,
            missingFields, evidence, confidence.
            Arrays contain strings. confidence is a number from 0 to 1. Never invent evidence.
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final String authTokenEnvironmentName;
    private final int maxTokens;

    public AnthropicTaskDraftModelAdapter(
            ObjectMapper objectMapper,
            @Value("${rd.task-draft.base-url:https://api.longcat.chat/anthropic}") String baseUrl,
            @Value("${rd.task-draft.model:}") String model,
            @Value("${rd.task-draft.auth-token-env:LONGCAT_API_KEY}") String authTokenEnvironmentName,
            @Value("${rd.task-draft.max-tokens:2048}") int maxTokens
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.model = model == null ? "" : model.strip();
        this.authTokenEnvironmentName = authTokenEnvironmentName == null ? "" : authTokenEnvironmentName.strip();
        this.maxTokens = Math.max(256, maxTokens);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ModelResponse complete(TaskDraftRequest request) {
        if (model.isBlank()) {
            return ModelResponse.unavailable("task draft model is not configured");
        }
        String token = authTokenEnvironmentName.isBlank() ? "" : System.getenv(authTokenEnvironmentName);
        if (token == null || token.isBlank()) {
            return ModelResponse.unavailable("task draft model credential is not configured");
        }
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", maxTokens,
                    "system", SYSTEM_PROMPT,
                    "messages", List.of(Map.of("role", "user", "content", requestJson))
            ));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-api-key", token)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ModelResponse.unavailable("task draft model HTTP status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("content").isArray() && !root.path("content").isEmpty()
                    ? root.path("content").get(0).path("text").asText("") : "";
            if (text.isBlank()) {
                return ModelResponse.unavailable("task draft model returned empty content");
            }
            return ModelResponse.available(text.strip());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ModelResponse.unavailable("task draft model request interrupted");
        } catch (Exception exception) {
            return ModelResponse.unavailable("task draft model request failed: " + exception.getClass().getSimpleName());
        }
    }

}
