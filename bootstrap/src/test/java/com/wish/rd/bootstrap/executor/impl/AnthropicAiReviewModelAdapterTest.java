package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.executor.AiReviewProperties;
import com.wish.rd.engine.requirement.review.AiReviewModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicAiReviewModelAdapterTest {

    @Test
    void springCanInstantiateTheEnabledAdapterWithItsProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AiReviewProperties.class, AiReviewProperties::new)
                .withUserConfiguration(AnthropicAiReviewModelAdapter.class)
                .withPropertyValues("rd.ai-review.enabled=true")
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .hasSingleBean(AnthropicAiReviewModelAdapter.class));
    }

    @Test
    void sendsAnthropicCompatibleStrictReviewRequestWithEnvironmentCredential() throws Exception {
        AiReviewProperties properties = properties();
        AtomicReference<AnthropicAiReviewModelAdapter.TransportRequest> captured = new AtomicReference<>();
        ObjectMapper objectMapper = new ObjectMapper();
        String finalJson = """
                {"decision":"OK","score":95,"summary":"accepted","retryFromRole":"","dimensions":[],"findings":[]}
                """.strip();
        AnthropicAiReviewModelAdapter adapter = new AnthropicAiReviewModelAdapter(
                objectMapper, properties, ignored -> "secret-from-environment",
                request -> {
                    captured.set(request);
                    return new AnthropicAiReviewModelAdapter.TransportResponse(
                            200, objectMapper.writeValueAsString(java.util.Map.of(
                                    "content", List.of(java.util.Map.of("type", "text", "text", finalJson))
                            )));
                });

        var response = adapter.review(new AiReviewModelPort.ModelRequest(
                "run-1", "task-1", "FINAL", 1, 1, List.of("qa-result"), "evidence payload"));

        assertTrue(response.available());
        assertEquals("review-model", response.modelName());
        assertEquals(finalJson, response.rawJson());
        assertEquals("https://review.example/anthropic/v1/messages", captured.get().uri().toString());
        assertEquals("secret-from-environment", captured.get().headers().get("x-api-key"));
        assertTrue(captured.get().body().contains("qa-result"));
        assertTrue(captured.get().body().contains("untrusted evidence"));
        assertEquals("disabled", objectMapper.readTree(captured.get().body())
                .path("thinking").path("type").asText());
        assertFalse(captured.get().body().contains("secret-from-environment"));
    }

    @Test
    void explainsAnthropicThinkingOnlyResponseInsteadOfReportingAnOpaqueEmptyBody() {
        AiReviewProperties properties = properties();
        AnthropicAiReviewModelAdapter adapter = new AnthropicAiReviewModelAdapter(
                new ObjectMapper(), properties, ignored -> "secret",
                request -> new AnthropicAiReviewModelAdapter.TransportResponse(200, """
                        {"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":"internal"}]}
                        """));

        var response = adapter.review(new AiReviewModelPort.ModelRequest(
                "run-1", "task-1", "FINAL", 1, 1, List.of(), "evidence"));

        assertFalse(response.available());
        assertEquals("PROVIDER_EMPTY", response.errorCategory());
        assertTrue(response.reason().contains("stopReason=max_tokens"));
        assertTrue(response.reason().contains("contentTypes=thinking"));
        assertFalse(response.reason().contains("internal"));
    }

    @Test
    void missingCredentialAndHttpFailureRemainRetryableProviderFailures() {
        AiReviewProperties properties = properties();
        AnthropicAiReviewModelAdapter missing = new AnthropicAiReviewModelAdapter(
                new ObjectMapper(), properties, ignored -> "",
                request -> new AnthropicAiReviewModelAdapter.TransportResponse(200, "{}"));
        var missingResponse = missing.review(new AiReviewModelPort.ModelRequest(
                "run-1", "task-1", "FINAL", 1, 1, List.of(), "evidence"));
        assertFalse(missingResponse.available());
        assertEquals("AUTH_MISSING", missingResponse.errorCategory());

        AnthropicAiReviewModelAdapter failed = new AnthropicAiReviewModelAdapter(
                new ObjectMapper(), properties, ignored -> "secret",
                request -> new AnthropicAiReviewModelAdapter.TransportResponse(503, "unavailable"));
        var failedResponse = failed.review(new AiReviewModelPort.ModelRequest(
                "run-1", "task-1", "FINAL", 1, 1, List.of(), "evidence"));
        assertFalse(failedResponse.available());
        assertEquals("PROVIDER_HTTP", failedResponse.errorCategory());
        assertTrue(failedResponse.reason().contains("503"));
    }

    @Test
    void supportsBearerAuthorizationWithoutPuttingTheTokenInTheBody() {
        AiReviewProperties properties = properties();
        properties.setAuthMode("authorization-bearer");
        AtomicReference<AnthropicAiReviewModelAdapter.TransportRequest> captured = new AtomicReference<>();
        AnthropicAiReviewModelAdapter adapter = new AnthropicAiReviewModelAdapter(
                new ObjectMapper(), properties, ignored -> "bearer-secret",
                request -> {
                    captured.set(request);
                    return new AnthropicAiReviewModelAdapter.TransportResponse(
                            200, "{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}");
                });

        adapter.review(new AiReviewModelPort.ModelRequest(
                "run-1", "task-1", "FINAL", 1, 1, List.of(), "evidence"));

        assertEquals("Bearer bearer-secret", captured.get().headers().get("Authorization"));
        assertFalse(captured.get().headers().containsKey("x-api-key"));
        assertFalse(captured.get().body().contains("bearer-secret"));
    }

    private static AiReviewProperties properties() {
        AiReviewProperties properties = new AiReviewProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://review.example/anthropic/");
        properties.setModel("review-model");
        properties.setAuthTokenEnv("REVIEW_TOKEN");
        properties.setMaxTokens(4096);
        properties.setTimeout(Duration.ofSeconds(30));
        return properties;
    }
}
