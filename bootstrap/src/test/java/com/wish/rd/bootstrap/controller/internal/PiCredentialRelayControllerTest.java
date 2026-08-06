package com.wish.rd.bootstrap.controller.internal;

import com.wish.rd.bootstrap.executor.PiCredentialRelayService;
import com.wish.rd.exec.repair.pi.impl.InMemoryPiCredentialLeaseIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PiCredentialRelayControllerTest {

    @Test
    void shouldRedeemOnlyForBoundBearerLeaseAndExhaustTheBudget() throws Exception {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue("task-1", "stage-1", "provider-1", "super-secret", Duration.ofMinutes(5), 1);
        MockMvc mvc = standaloneSetup(new PiCredentialRelayController(
                new PiCredentialRelayService(issuer)
        )).build();

        mvc.perform(post("/internal/pi/credential-relay/redeem")
                        .header("Authorization", "Bearer " + lease.token())
                        .contentType("application/json")
                        .content("""
                                {"taskId":"task-1","stageRunId":"stage-1","providerId":"provider-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.credential").value("super-secret"));

        mvc.perform(post("/internal/pi/credential-relay/redeem")
                        .header("Authorization", "Bearer " + lease.token())
                        .contentType("application/json")
                        .content("""
                                {"taskId":"task-1","stageRunId":"stage-1","providerId":"provider-1"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnknownOrMismatchedLeaseWithoutReturningCredential() throws Exception {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue("task-1", "stage-1", "provider-1", "super-secret", Duration.ofMinutes(5), 1);
        MockMvc mvc = standaloneSetup(new PiCredentialRelayController(
                new PiCredentialRelayService(issuer)
        )).build();

        mvc.perform(post("/internal/pi/credential-relay/redeem")
                        .header("Authorization", "Bearer pcl_unknown")
                        .contentType("application/json")
                        .content("""
                                {"taskId":"task-1","stageRunId":"stage-1","providerId":"provider-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        mvc.perform(post("/internal/pi/credential-relay/redeem")
                        .header("Authorization", "Bearer " + lease.token())
                        .contentType("application/json")
                        .content("""
                                {"taskId":"other-task","stageRunId":"stage-1","providerId":"provider-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void shouldRejectExpiredLease() throws Exception {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue("task-1", "stage-1", "provider-1", "super-secret", Duration.ofNanos(1), 1);
        while (Instant.now().isBefore(lease.expiresAt())) {
            Thread.onSpinWait();
        }
        MockMvc mvc = standaloneSetup(new PiCredentialRelayController(
                new PiCredentialRelayService(issuer)
        )).build();

        mvc.perform(post("/internal/pi/credential-relay/redeem")
                        .header("Authorization", "Bearer " + lease.token())
                        .contentType("application/json")
                        .content("""
                                {"taskId":"task-1","stageRunId":"stage-1","providerId":"provider-1"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
