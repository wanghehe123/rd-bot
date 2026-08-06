package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPiCredentialLeaseIssuerTest {

    @Test
    void shouldIssueOpaqueTokenWithoutEmbeddingSecret() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        PiCredentialLeaseIssuer.PiCredentialLease lease = issuer.issue(
                "task-1",
                "stage-1",
                "provider-a",
                "super-secret-key",
                Duration.ofMinutes(10),
                3
        );

        assertTrue(lease.token().startsWith("pcl_"));
        assertNotEquals("super-secret-key", lease.token());
        assertTrue(!lease.token().contains("super-secret"));
        assertEquals("task-1", lease.taskId());
        assertEquals(3, lease.maxCalls());
    }

    @Test
    void shouldRedeemUntilBudgetExhausted() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue("t", "s", "p", "secret", Duration.ofMinutes(5), 2);

        assertEquals(Optional.of("secret"), issuer.redeem(lease.token()));
        assertEquals(Optional.of("secret"), issuer.redeem(lease.token()));
        assertEquals(Optional.empty(), issuer.redeem(lease.token()));
    }

    @Test
    void shouldRejectRedemptionWhenLeaseBindingDoesNotMatch() {
        InMemoryPiCredentialLeaseIssuer issuer = new InMemoryPiCredentialLeaseIssuer();
        var lease = issuer.issue("task-1", "stage-1", "provider-1", "secret", Duration.ofMinutes(5), 1);

        assertEquals(
                Optional.empty(),
                issuer.redeem(lease.token(), "other-task", "stage-1", "provider-1")
        );
        assertEquals(
                Optional.of("secret"),
                issuer.redeem(lease.token(), "task-1", "stage-1", "provider-1")
        );
    }
}
