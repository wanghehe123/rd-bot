package com.wish.rd.exec.repair.pi;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Host-side issuer for short-lived Pi provider credential leases.
 * Raw provider secrets stay on the host; containers receive only the opaque lease token.
 */
public interface PiCredentialLeaseIssuer {

    /**
     * Issues a lease bound to task/stage/provider identity.
     *
     * @param taskId       RD task id
     * @param stageRunId   stage run id
     * @param providerId   provider id
     * @param rawCredential host-held provider secret (never returned to callers of {@link #issue})
     * @param ttl          lease lifetime
     * @param maxCalls     max redeem/use budget
     * @return opaque lease for container env injection
     */
    PiCredentialLease issue(
            String taskId,
            String stageRunId,
            String providerId,
            String rawCredential,
            Duration ttl,
            int maxCalls
    );

    /**
     * Redeems a lease for the host relay path. Returns empty when expired, exhausted, or unknown.
     *
     * @param token opaque lease token
     * @return raw credential when valid
     */
    Optional<String> redeem(String token);

    /**
     * Redeems a lease only when the request carries the identity to which the lease was bound.
     *
     * @param token      opaque lease token
     * @param taskId     expected RD task id
     * @param stageRunId expected stage run id
     * @param providerId expected provider id
     * @return raw credential when the token, binding, expiry, and budget are valid
     */
    Optional<String> redeem(String token, String taskId, String stageRunId, String providerId);

    /**
     * Opaque lease metadata exposed to the container (never includes the raw secret).
     *
     * @param token     opaque token
     * @param expiresAt expiry instant
     * @param maxCalls  call budget
     * @param taskId    bound task
     * @param stageRunId bound stage
     * @param providerId bound provider
     */
    record PiCredentialLease(
            String token,
            Instant expiresAt,
            int maxCalls,
            String taskId,
            String stageRunId,
            String providerId
    ) {
    }
}
