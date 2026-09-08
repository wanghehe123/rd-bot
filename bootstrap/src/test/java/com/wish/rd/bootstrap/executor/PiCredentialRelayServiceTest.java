package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.security.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCredentialRelayServiceTest {

    @Test
    void boundedErrorBodyExcerptRedactsSecretsAndHonorsByteBudget() {
        String secret = "super-secret-credential";
        String body = "Authorization: Bearer " + secret
                + " api_key=" + secret
                + " upstream said unknown variant developer "
                + "x".repeat(600);
        String excerpt = PiCredentialRelayService.boundedErrorBodyExcerpt(
                body.getBytes(StandardCharsets.UTF_8),
                512
        );
        assertFalse(excerpt.contains(secret));
        assertTrue(excerpt.contains(SecretRedactor.REDACTED) || excerpt.contains("<redacted>"));
        assertTrue(excerpt.getBytes(StandardCharsets.UTF_8).length <= 512);
        assertTrue(excerpt.endsWith("..."));
    }

    @Test
    void proxyForwardsNon2xxBodyToClientAndKeepsCredentialOutOfAudit() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        String rawCredential = "super-secret";
        var lease = issuer.issue(
                "task-1",
                "stage-1",
                "provider-1",
                rawCredential,
                new PiCredentialLeaseIssuer.RelayPolicy(
                        "https://provider.example.test/v1",
                        List.of("POST"),
                        List.of("/chat/completions"),
                        true,
                        1024,
                        1024,
                        Duration.ofSeconds(10)
                ),
                Duration.ofMinutes(5),
                1
        );
        String errorBody = "{\"type\":\"invalid_request_error\",\"message\":\"unknown variant developer\"}";
        List<PiCredentialRelayService.RelayAuditEvent> auditEvents = new ArrayList<>();
        PiCredentialRelayService service = new PiCredentialRelayService(
                issuer,
                request -> new PiCredentialRelayService.UpstreamResponse(
                        400,
                        Map.of("content-type", "application/json"),
                        errorBody.getBytes(StandardCharsets.UTF_8)
                ),
                auditEvents::add
        );

        PiCredentialRelayService.ProxyResponse response = service.proxy(
                lease.token(),
                "task-1",
                "stage-1",
                "provider-1",
                "POST",
                "/chat/completions",
                Map.of("content-type", "application/json"),
                "{\"model\":\"deepseek-v4-flash\"}".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(400, response.status());
        assertEquals(errorBody, new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(1, auditEvents.size());
        assertEquals("FORWARDED", auditEvents.getFirst().outcome());
        assertEquals(400, auditEvents.getFirst().status());
        assertTrue(auditEvents.stream().noneMatch(event -> event.toString().contains(rawCredential)));
    }

    @Test
    void openCodeUpstreamGetsStableSessionAndRelayUserAgent() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue(
                "task-opencode",
                "stage-opencode",
                "opencode-go",
                "super-secret",
                new PiCredentialLeaseIssuer.RelayPolicy(
                        "https://opencode.ai/zen/go",
                        List.of("POST"),
                        List.of("/v1/messages"),
                        false,
                        1024,
                        1024,
                        Duration.ofSeconds(10)
                ),
                Duration.ofMinutes(5),
                1
        );
        AtomicReferenceCapture capture = new AtomicReferenceCapture();
        PiCredentialRelayService service = new PiCredentialRelayService(
                issuer,
                request -> {
                    capture.request = request;
                    return new PiCredentialRelayService.UpstreamResponse(
                            200,
                            Map.of("content-type", "application/json"),
                            "{}".getBytes(StandardCharsets.UTF_8)
                    );
                },
                event -> { }
        );

        PiCredentialRelayService.ProxyResponse response = service.proxy(
                lease.token(),
                "task-opencode",
                "stage-opencode",
                "opencode-go",
                "POST",
                "/v1/messages",
                Map.of("content-type", "application/json"),
                "{\"model\":\"qwen3.8-flash\"}".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(200, response.status());
        assertEquals("stage-opencode", capture.request.headers().get("x-opencode-session"));
        assertEquals("rd-bot-pi-relay/1.0", capture.request.headers().get("User-Agent"));
        assertEquals("super-secret", capture.request.headers().get("X-Api-Key"));
    }

    @Test
    void openCodePreservesPiSuppliedSessionHeader() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue(
                "task-opencode",
                "stage-opencode",
                "opencode-go",
                "super-secret",
                new PiCredentialLeaseIssuer.RelayPolicy(
                        "https://opencode.ai/zen/go",
                        List.of("POST"),
                        List.of("/v1/messages"),
                        false,
                        1024,
                        1024,
                        Duration.ofSeconds(10)
                ),
                Duration.ofMinutes(5),
                1
        );
        AtomicReferenceCapture capture = new AtomicReferenceCapture();
        PiCredentialRelayService service = new PiCredentialRelayService(
                issuer,
                request -> {
                    capture.request = request;
                    return new PiCredentialRelayService.UpstreamResponse(
                            200,
                            Map.of(),
                            new byte[0]
                    );
                },
                event -> { }
        );

        service.proxy(
                lease.token(),
                "task-opencode",
                "stage-opencode",
                "opencode-go",
                "POST",
                "/v1/messages",
                Map.of(
                        "content-type", "application/json",
                        "x-opencode-session", "pi-session-42",
                        "user-agent", "pi-agent/1.0"
                ),
                "{}".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals("pi-session-42", capture.request.headers().get("x-opencode-session"));
        assertEquals("pi-agent/1.0", capture.request.headers().get("User-Agent"));
    }

    @Test
    void nonOpenCodeUpstreamDoesNotInjectSessionHeader() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        PiCredentialRelayService.ensureOpenCodeRoutingHeaders(
                headers,
                "https://provider.example.test/v1",
                "provider-1",
                "task-1",
                "stage-1"
        );
        assertFalse(headers.containsKey("x-opencode-session"));
        assertFalse(headers.containsKey("User-Agent"));
    }

    private static final class AtomicReferenceCapture {
        private PiCredentialRelayService.UpstreamRequest request;
    }
}
