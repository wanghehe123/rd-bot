package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Adapts the Host Pi lease issuer for the short-lived HTTP redemption path.
 * Raw provider credentials remain inside the issuer and are returned only after
 * the lease binding has been checked.
 */
@Service
public class PiCredentialRelayService {

    private final PiCredentialLeaseIssuer issuer;

    /**
     * Creates the Host relay service backed by the configured lease issuer.
     *
     * @param issuer issuer that owns lease expiry, binding, and call-budget semantics
     */
    public PiCredentialRelayService(PiCredentialLeaseIssuer issuer) {
        if (issuer == null) {
            throw new IllegalArgumentException("issuer must not be null");
        }
        this.issuer = issuer;
    }

    /**
     * Redeems a bound lease without exposing invalid-request details to callers.
     *
     * @param token      bearer lease token
     * @param taskId     expected RD task id
     * @param stageRunId expected stage run id
     * @param providerId expected provider id
     * @return the raw provider credential only when the lease is valid and bound
     */
    public Optional<String> redeem(String token, String taskId, String stageRunId, String providerId) {
        if (blank(token) || blank(taskId) || blank(stageRunId) || blank(providerId)) {
            return Optional.empty();
        }
        return issuer.redeem(token, taskId, stageRunId, providerId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
