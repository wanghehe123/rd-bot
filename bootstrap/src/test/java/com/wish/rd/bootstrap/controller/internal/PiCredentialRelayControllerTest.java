package com.wish.rd.bootstrap.controller.internal;

import com.wish.rd.bootstrap.executor.PiCredentialRelayService;
import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PiCredentialRelayControllerTest {

    @Test
    void shouldProxyOnlyBoundAllowedLeaseRequestAndNeverReturnProviderCredential() throws Exception {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        String rawCredential = "super-secret";
        var lease = issuer.issue(
                "task-1",
                "stage-1",
                "provider-1",
                rawCredential,
                relayPolicy(1024, 1024),
                Duration.ofMinutes(5),
                1
        );
        AtomicReference<PiCredentialRelayService.UpstreamRequest> upstreamRequest = new AtomicReference<>();
        List<PiCredentialRelayService.RelayAuditEvent> auditEvents = new ArrayList<>();
        MockMvc mvc = standaloneSetup(new PiCredentialRelayController(
                new PiCredentialRelayService(
                        issuer,
                        request -> {
                            upstreamRequest.set(request);
                            return new PiCredentialRelayService.UpstreamResponse(
                                    200,
                                    Map.of("content-type", "application/json"),
                                    "{\"id\":\"response-1\"}".getBytes(StandardCharsets.UTF_8)
                            );
                        },
                        auditEvents::add
                )
        )).build();

        mvc.perform(post("/internal/pi/credential-relay/proxy")
                        .header("Authorization", "Bearer " + lease.token())
                        .header("X-RD-Pi-Relay-Task-Id", "task-1")
                        .header("X-RD-Pi-Relay-Stage-Run-Id", "stage-1")
                        .header("X-RD-Pi-Relay-Provider-Id", "provider-1")
                        .header("X-RD-Pi-Relay-Method", "POST")
                        .header("X-RD-Pi-Relay-Path", "/chat/completions")
                        .contentType("application/json")
                        .content("{\"model\":\"gpt-test\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("{\"id\":\"response-1\"}"));

        PiCredentialRelayService.UpstreamRequest forwarded = upstreamRequest.get();
        assertEquals("https://provider.example.test/v1/chat/completions", forwarded.uri().toString());
        assertEquals("Bearer " + rawCredential, forwarded.headers().get("Authorization"));
        assertFalse(forwarded.headers().containsKey("X-RD-Pi-Relay-Task-Id"));
        assertTrue(auditEvents.stream().noneMatch(event -> event.toString().contains(rawCredential)));

        mvc.perform(post("/internal/pi/credential-relay/proxy")
                        .header("Authorization", "Bearer " + lease.token())
                        .header("X-RD-Pi-Relay-Task-Id", "task-1")
                        .header("X-RD-Pi-Relay-Stage-Run-Id", "stage-1")
                        .header("X-RD-Pi-Relay-Provider-Id", "provider-1")
                        .header("X-RD-Pi-Relay-Method", "POST")
                        .header("X-RD-Pi-Relay-Path", "/chat/completions")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void shouldRejectCrossTaskAndDisallowedProxyRequestsWithoutASecretResponse() throws Exception {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue(
                "task-1",
                "stage-1",
                "provider-1",
                "super-secret",
                relayPolicy(32, 32),
                Duration.ofMinutes(5),
                5
        );
        MockMvc mvc = standaloneSetup(new PiCredentialRelayController(
                new PiCredentialRelayService(
                        issuer,
                        request -> new PiCredentialRelayService.UpstreamResponse(
                                200,
                                Map.of("content-type", "application/json"),
                                "response-body-that-exceeds-the-lease-limit".getBytes(StandardCharsets.UTF_8)
                        ),
                        event -> { }
                )
        )).build();

        mvc.perform(proxyRequest(lease.token(), "other-task", "/chat/completions", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        mvc.perform(proxyRequest(lease.token(), "task-1", "/v1/models", "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        mvc.perform(proxyRequest(lease.token(), "task-1", "/chat/completions", "{}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(""));

        mvc.perform(post("/internal/pi/credential-relay/redeem"))
                .andExpect(status().isNotFound());
    }

    private static PiCredentialLeaseIssuer.RelayPolicy relayPolicy(int maxRequestBytes, int maxResponseBytes) {
        return new PiCredentialLeaseIssuer.RelayPolicy(
                "https://provider.example.test/v1",
                List.of("POST"),
                List.of("/chat/completions"),
                true,
                maxRequestBytes,
                maxResponseBytes,
                Duration.ofSeconds(10)
        );
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder proxyRequest(
            String leaseToken,
            String taskId,
            String path,
            String body
    ) {
        return post("/internal/pi/credential-relay/proxy")
                .header("Authorization", "Bearer " + leaseToken)
                .header("X-RD-Pi-Relay-Task-Id", taskId)
                .header("X-RD-Pi-Relay-Stage-Run-Id", "stage-1")
                .header("X-RD-Pi-Relay-Provider-Id", "provider-1")
                .header("X-RD-Pi-Relay-Method", "POST")
                .header("X-RD-Pi-Relay-Path", path)
                .contentType("application/json")
                .content(body);
    }
}
