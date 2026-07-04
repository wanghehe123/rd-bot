package com.wish.rd.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider preflight production smoke. Disabled by default and calls real model provider endpoints when enabled.
 */
@EnabledIfSystemProperty(named = "rd.integration.provider-preflight.enabled", matches = "true")
class ProviderPreflightRealSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void probesRealProvidersBeforeLongRunningMultiAgentSmoke() throws Exception {
        ProviderPreflightProductionAcceptanceReport report =
                ProviderPreflightProductionAcceptanceReport.fromSystemProperties();
        Map<String, String> properties = ProviderPreflightProductionAcceptanceProfile.systemProperties();
        List<String> missing = ProviderPreflightProductionAcceptanceProfile.missingRequiredProperties(properties);
        if (!missing.isEmpty()) {
            Path skippedReport = report.writeSkipped(missing);
            ProductionSmokePreconditions.requireReady("provider-preflight", missing, skippedReport);
        }
        ProviderPreflightProductionAcceptanceProfile profile =
                ProviderPreflightProductionAcceptanceProfile.from(properties);
        List<ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence> probes = new ArrayList<>();
        ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence evidence = evidence(profile, probes);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(profile.requestTimeout())
                    .build();
            for (ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider : profile.providers()) {
                probes.add(probe(client, provider, profile));
                evidence = evidence(profile, probes);
            }
            assertTrue(probes.stream().allMatch(
                            ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence::success),
                    "all configured providers must pass real preflight: " + probes);
            Path reportPath = report.writePassed(evidence);
            System.out.println("[smoke] provider-preflight providerCount=" + probes.size()
                    + " report=" + reportPath.toAbsolutePath());
        } catch (Throwable failure) {
            Path reportPath = report.writeFailed(evidence, failure);
            System.out.println("[smoke] provider-preflight failure report=" + reportPath.toAbsolutePath());
            throw failure;
        }
    }

    private static ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence probe(
            HttpClient client,
            ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider,
            ProviderPreflightProductionAcceptanceProfile profile
    ) {
        String apiKey = System.getenv(provider.apiKeyEnv());
        try {
            HttpRequest request = provider.openAiChatCompletions()
                    ? openAiRequest(provider, apiKey, profile)
                    : anthropicRequest(provider, apiKey, profile);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300
                    && providerResponded(provider, body);
            return new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                    provider.name(),
                    provider.protocol(),
                    provider.adapterName(),
                    provider.baseUrl(),
                    provider.apiKeyEnv(),
                    provider.model(),
                    response.statusCode(),
                    success,
                    fingerprint(body),
                    success ? "" : "PROVIDER_HTTP_OR_SCHEMA_FAILED",
                    success ? "ok" : redactedError(body, profile.secretScanNeedles())
            );
        } catch (Exception exception) {
            return new ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence(
                    provider.name(),
                    provider.protocol(),
                    provider.adapterName(),
                    provider.baseUrl(),
                    provider.apiKeyEnv(),
                    provider.model(),
                    -1,
                    false,
                    "",
                    "PROVIDER_REQUEST_FAILED",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private static HttpRequest openAiRequest(
            ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider,
            String apiKey,
            ProviderPreflightProductionAcceptanceProfile profile
    ) throws Exception {
        Map<String, Object> body = Map.of(
                "model", provider.model(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", "Return only a compact JSON object."),
                        Map.of("role", "user", "content", "Return {\"status\":\"SUCCESS\",\"summary\":\"provider preflight ok\"}.")
                )
        );
        return baseRequest(provider, openAiEndpoint(provider.baseUrl()), apiKey, profile)
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
    }

    private static HttpRequest anthropicRequest(
            ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider,
            String apiKey,
            ProviderPreflightProductionAcceptanceProfile profile
    ) throws Exception {
        Map<String, Object> body = Map.of(
                "model", provider.model(),
                "max_tokens", 64,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", "Return only this JSON object: {\"status\":\"SUCCESS\",\"summary\":\"provider preflight ok\"}"
                ))
        );
        return baseRequest(provider, anthropicEndpoint(provider.baseUrl()), apiKey, profile)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();
    }

    private static HttpRequest.Builder baseRequest(
            ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider,
            String endpoint,
            String apiKey,
            ProviderPreflightProductionAcceptanceProfile profile
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(profile.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "rd-bot-provider-preflight/" + Instant.now().toEpochMilli());
        if ("x-api-key".equals(provider.authHeader())) {
            return builder.header("x-api-key", apiKey);
        }
        return builder.header("Authorization", "Bearer " + apiKey);
    }

    private static boolean providerResponded(
            ProviderPreflightProductionAcceptanceProfile.ProviderSpec provider,
            String responseBody
    ) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody == null ? "" : responseBody);
            if (provider.openAiChatCompletions()) {
                return !root.path("choices").path(0).path("message").path("content").asText("").isBlank();
            }
            JsonNode content = root.path("content");
            return content.isArray() && content.size() > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String openAiEndpoint(String baseUrl) {
        String normalized = trimSlash(baseUrl);
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    private static String anthropicEndpoint(String baseUrl) {
        String normalized = trimSlash(baseUrl);
        if (normalized.endsWith("/v1/messages")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/messages";
        }
        return normalized + "/v1/messages";
    }

    private static String trimSlash(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence evidence(
            ProviderPreflightProductionAcceptanceProfile profile,
            List<ProviderPreflightProductionAcceptanceReport.ProviderProbeEvidence> probes
    ) {
        return new ProviderPreflightProductionAcceptanceReport.ProviderPreflightEvidence(
                profile.rdBotVersion(),
                profile.environmentId(),
                profile.executedBy(),
                profile.expectedProviderCount(),
                probes,
                profile.secretScanNeedles()
        );
    }

    private static String fingerprint(String body) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        return "body-sha256:" + HexFormat.of().formatHex(digest);
    }

    private static String redactedError(String value, List<String> secretNeedles) {
        String redacted = value == null ? "" : value;
        for (String needle : secretNeedles == null ? List.<String>of() : secretNeedles) {
            if (needle != null && !needle.isBlank()) {
                redacted = redacted.replace(needle, "***");
            }
        }
        return redacted.length() > 300 ? redacted.substring(0, 300) : redacted;
    }
}
