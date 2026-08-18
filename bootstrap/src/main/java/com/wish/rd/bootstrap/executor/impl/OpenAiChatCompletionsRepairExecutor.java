package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.exec.repair.execution.model.RepairExecutionResult;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStatus;
import com.wish.rd.exec.repair.execution.RepairExecutorPort;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.result.model.AgentRoleResultValidation;
import com.wish.rd.exec.repair.result.AgentRoleResultValidator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenAI-compatible chat completions executor for non-coding Agent roles.
 */
public final class OpenAiChatCompletionsRepairExecutor implements RepairExecutorPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROTOCOL = "openai-chat-completions";

    private final Configuration configuration;
    private final HttpClient httpClient;
    private final Supplier<String> apiKeySupplier;
    private final AgentRoleResultValidator roleResultValidator;

    public OpenAiChatCompletionsRepairExecutor(Configuration configuration) {
        this(configuration, HttpClient.newHttpClient(), () -> System.getenv(configuration.apiKeyEnv()));
    }

    public OpenAiChatCompletionsRepairExecutor(
            Configuration configuration,
            HttpClient httpClient,
            Supplier<String> apiKeySupplier
    ) {
        this.configuration = configuration;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.apiKeySupplier = apiKeySupplier == null ? () -> "" : apiKeySupplier;
        this.roleResultValidator = new AgentRoleResultValidator();
    }

    @Override
    public RepairExecutionResult execute(RepairJobCommand command) {
        long startedAtEpochMillis = System.currentTimeMillis();
        RepairExecutionResult result = executeOnce(command);
        return withProviderAttempt(result, startedAtEpochMillis, System.currentTimeMillis());
    }

    private RepairExecutionResult executeOnce(RepairJobCommand command) {
        String role = command.contextJson().getOrDefault("agentRole", "");
        if ("CODING_AGENT".equals(role)) {
            return failed("openai-chat-completions executor does not support repository coding", Map.of());
        }
        String apiKey = safe(apiKeySupplier.get());
        if (apiKey.isBlank()) {
            return failed("missing API key env: " + configuration.apiKeyEnv(), Map.of());
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request(command, apiKey),
                    HttpResponse.BodyHandlers.ofString()
            );
            Map<String, String> metadata = responseMetadata(response);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failed("openai chat completions request failed: " + response.statusCode()
                        + " " + responseError(response.body()), metadata);
            }
            return parseSuccessfulResponse(role, response.body(), metadata);
        } catch (IOException exception) {
            return failed("openai chat completions request failed: " + exception.getMessage(), Map.of());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed("openai chat completions request interrupted", Map.of());
        }
    }

    private RepairExecutionResult withProviderAttempt(
            RepairExecutionResult result,
            long startedAtEpochMillis,
            long finishedAtEpochMillis
    ) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("provider", configuration.providerName());
        attempt.put("model", configuration.model());
        attempt.put("protocol", configuration.protocol());
        attempt.put("attempt", 1);
        attempt.put("status", result.status() == RepairExecutionStatus.SUCCESS
                ? "SUCCESS" : result.status().name());
        attempt.put("startedAtEpochMillis", startedAtEpochMillis);
        attempt.put("finishedAtEpochMillis", Math.max(startedAtEpochMillis, finishedAtEpochMillis));
        if (result.status() != RepairExecutionStatus.SUCCESS) {
            attempt.put("errorCategory", result.status().name());
            attempt.put("errorMessage", safe(result.errorMessage()));
        }
        Map<String, String> metadata = new LinkedHashMap<>(result.dockerMetadataJson());
        metadata.put("provider", configuration.providerName());
        try {
            metadata.put("providerAttemptsJson", OBJECT_MAPPER.writeValueAsString(List.of(attempt)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize provider attempt", exception);
        }
        return new RepairExecutionResult(
                result.status(), result.summary(), result.pullRequestUrl(), result.artifacts(),
                result.rawResultJson(), metadata, result.githubMetadataJson(), result.testMetadataJson(),
                result.riskMetadataJson(), result.errorMessage()
        );
    }

    private HttpRequest request(RepairJobCommand command, String apiKey) throws JsonProcessingException {
        Map<String, Object> body = configuration.anthropicCompatible()
                ? anthropicRequestBody(command)
                : openAiRequestBody(command);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(configuration.endpoint()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)));
        if (!configuration.timeout().isZero()) {
            builder.timeout(configuration.timeout());
        }
        if (configuration.anthropicCompatible()) {
            builder.header("anthropic-version", "2023-06-01");
        }
        return builder.build();
    }

    private Map<String, Object> openAiRequestBody(RepairJobCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuration.model());
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of(
                        "role", "system",
                        "content", systemPrompt(command.contextJson().getOrDefault("agentRole", ""))
                ),
                Map.of(
                        "role", "user",
                        "content", userPrompt(command)
                )
        ));
        return body;
    }

    private Map<String, Object> anthropicRequestBody(RepairJobCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", configuration.model());
        body.put("temperature", 0);
        body.put("max_tokens", 4096);
        body.put("system", systemPrompt(command.contextJson().getOrDefault("agentRole", "")));
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", userPrompt(command)
        )));
        return body;
    }

    private RepairExecutionResult parseSuccessfulResponse(
            String role,
            String responseBody,
            Map<String, String> metadata
    ) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        String content = responseContent(root);
        String agentResultJson = extractJsonObject(content);
        if (agentResultJson.isBlank()) {
            return failed("openai chat completions response did not contain a JSON object", metadata);
        }
        AgentRoleResultValidation validation = roleResultValidator.validate(role, agentResultJson);
        if (!validation.valid()) {
            return new RepairExecutionResult(
                    RepairExecutionStatus.FAILED_VALIDATION,
                    "agent result validation failed",
                    "",
                    List.of(),
                    Map.of(EngineRequirementExecutorAdapter.AGENT_RESULT_JSON_FIELD, agentResultJson),
                    metadata,
                    Map.of(),
                    Map.of(),
                    Map.of("needHumanAction", "true"),
                    String.join("; ", validation.errors())
            );
        }
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(EngineRequirementExecutorAdapter.AGENT_RESULT_JSON_FIELD, agentResultJson);
        JsonNode agentRoot = OBJECT_MAPPER.readTree(agentResultJson);
        raw.put("summary", agentRoot.path("summary").asText("openai-compatible role result"));
        raw.put("status", agentRoot.path("status").asText("SUCCESS"));
        return new RepairExecutionResult(
                RepairExecutionStatus.SUCCESS,
                raw.get("summary"),
                "",
                List.<RepairArtifact>of(),
                raw,
                metadata,
                Map.of(),
                Map.of(),
                Map.of("riskLevel", "LOW", "needHumanAction", "false"),
                ""
        );
    }

    private Map<String, String> responseMetadata(HttpResponse<String> response) throws JsonProcessingException {
        Map<String, String> metadata = baseMetadata();
        metadata.put("httpStatus", String.valueOf(response.statusCode()));
        JsonNode root = OBJECT_MAPPER.readTree(response.body() == null ? "{}" : response.body());
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            String promptTokens = firstNonBlank(
                    usage.path("prompt_tokens").asText(""),
                    usage.path("input_tokens").asText("")
            );
            String completionTokens = firstNonBlank(
                    usage.path("completion_tokens").asText(""),
                    usage.path("output_tokens").asText("")
            );
            metadata.put("promptTokens", promptTokens);
            metadata.put("completionTokens", completionTokens);
            metadata.put("totalTokens", usage.path("total_tokens").asText(totalTokens(promptTokens, completionTokens)));
        }
        return metadata;
    }

    private Map<String, String> baseMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("provider", configuration.providerName());
        metadata.put("protocol", PROTOCOL);
        metadata.put("modelProtocol", configuration.protocol());
        metadata.put("model", configuration.model());
        metadata.put("endpoint", configuration.endpoint());
        return metadata;
    }

    private RepairExecutionResult failed(String message, Map<String, String> metadata) {
        return new RepairExecutionResult(
                RepairExecutionStatus.FAILED,
                message,
                "",
                List.of(),
                Map.of("status", "FAILED", "summary", message),
                metadata == null || metadata.isEmpty() ? baseMetadata() : metadata,
                Map.of(),
                Map.of(),
                Map.of("riskLevel", "HIGH", "needHumanAction", "true"),
                message
        );
    }

    private String responseError(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody == null ? "" : responseBody);
            return root.path("error").path("message").asText("");
        } catch (JsonProcessingException ignored) {
            return "";
        }
    }

    private String responseContent(JsonNode root) {
        if (configuration.anthropicCompatible()) {
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
            return "";
        }
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    private static String totalTokens(String promptTokens, String completionTokens) {
        try {
            int prompt = Integer.parseInt(firstNonBlank(promptTokens, "0"));
            int completion = Integer.parseInt(firstNonBlank(completionTokens, "0"));
            return Integer.toString(prompt + completion);
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String systemPrompt(String role) {
        return """
                You are RD-Bot's %s stage executor.
                Return only one JSON object. Do not include markdown fences.
                The JSON must conform to the role contract used by RD-Bot.
                """.formatted(role);
    }

    private String userPrompt(RepairJobCommand command) {
        return """
                Task title:
                %s

                Prompt:
                %s

                Role context:
                %s

                Upstream result:
                %s
                """.formatted(
                command.ticketTitle(),
                command.prompt(),
                command.contextJson().getOrDefault("roleContextJson", "{}"),
                command.contextJson().getOrDefault("upstreamResultJson", "[]")
        );
    }

    private static String extractJsonObject(String content) {
        String normalized = safe(content);
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .strip();
        }
        int first = normalized.indexOf('{');
        int last = normalized.lastIndexOf('}');
        if (first < 0 || last <= first) {
            return "";
        }
        return normalized.substring(first, last + 1);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    public record Configuration(
            String providerName,
            String model,
            String baseUrl,
            String apiKeyEnv,
            Duration timeout,
            String protocol
    ) {

        public Configuration(
                String providerName,
                String model,
                String baseUrl,
                String apiKeyEnv,
                Duration timeout
        ) {
            this(providerName, model, baseUrl, apiKeyEnv, timeout, "openai-chat-completions");
        }

        public Configuration {
            providerName = requireText(providerName, "providerName");
            model = requireText(model, "model");
            baseUrl = requireText(baseUrl, "baseUrl");
            apiKeyEnv = requireText(apiKeyEnv, "apiKeyEnv");
            timeout = timeout == null || timeout.isNegative()
                    ? Duration.ZERO
                    : timeout;
            protocol = normalizeProtocol(protocol);
        }

        private String endpoint() {
            if (anthropicCompatible()) {
                return anthropicEndpoint();
            }
            return chatCompletionsEndpoint();
        }

        private boolean anthropicCompatible() {
            return "anthropic-compatible".equals(protocol) || "anthropic".equals(protocol);
        }

        private String chatCompletionsEndpoint() {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            if (normalized.endsWith("/chat/completions")) {
                return normalized;
            }
            return normalized + "/chat/completions";
        }

        private String anthropicEndpoint() {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            if (normalized.endsWith("/v1/messages")) {
                return normalized;
            }
            if (normalized.endsWith("/v1")) {
                return normalized + "/messages";
            }
            return normalized + "/v1/messages";
        }

        private static String normalizeProtocol(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            return normalized.isBlank() ? "openai-chat-completions" : normalized;
        }

        private static String requireText(String value, String fieldName) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
