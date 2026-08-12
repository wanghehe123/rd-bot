package com.wish.rd.exec.repair.pi;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
     * Issues a lease together with the Host-owned upstream policy that a relay may use.
     * Implementations that do not support proxy authorization return a normal opaque lease,
     * then fail closed from {@link #authorize(String, String, String, String, String, String, int)}.
     *
     * @param taskId       RD task id
     * @param stageRunId   stage run id
     * @param providerId   provider id
     * @param rawCredential Host-held provider credential
     * @param relayPolicy  immutable upstream and request-boundary policy
     * @param ttl          lease lifetime
     * @param maxCalls     allowed relay-call budget
     * @return opaque lease for the untrusted Pi runtime
     */
    default PiCredentialLease issue(
            String taskId,
            String stageRunId,
            String providerId,
            String rawCredential,
            RelayPolicy relayPolicy,
            Duration ttl,
            int maxCalls
    ) {
        return issue(taskId, stageRunId, providerId, rawCredential, ttl, maxCalls);
    }

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
     * Authorizes one relay request without handing a credential to the Pi container.
     * The returned grant is Host-only and contains the selected upstream policy.
     *
     * @param token         opaque bearer lease from the Pi runtime
     * @param taskId        sidecar-bound task id
     * @param stageRunId    sidecar-bound stage run id
     * @param providerId    sidecar-bound provider id
     * @param method        requested HTTP method
     * @param path          requested provider-relative path
     * @param requestBytes  incoming request byte count
     * @return an authorized Host-only relay grant, or empty when validation fails
     */
    default Optional<RelayGrant> authorize(
            String token,
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            int requestBytes
    ) {
        return Optional.empty();
    }

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

    /**
     * Immutable Host policy for a leased provider relay route.
     * The policy is created from the frozen execution profile, never from Pi input.
     *
     * @param upstreamBaseUrl      HTTP(S) upstream base URL owned by the Host profile
     * @param allowedMethods       normalized exact HTTP methods
     * @param allowedPaths         normalized exact provider-relative paths
     * @param upstreamAuthHeader   whether the upstream uses {@code Authorization: Bearer}
     * @param maxRequestBytes      maximum Pi request body size
     * @param maxResponseBytes     maximum upstream response body size
     * @param requestTimeout       Host-to-provider deadline
     */
    record RelayPolicy(
            String upstreamBaseUrl,
            List<String> allowedMethods,
            List<String> allowedPaths,
            boolean upstreamAuthHeader,
            int maxRequestBytes,
            int maxResponseBytes,
            Duration requestTimeout
    ) {

        /** Normalizes and validates the immutable relay boundary. */
        public RelayPolicy {
            upstreamBaseUrl = normalizeUpstreamBaseUrl(upstreamBaseUrl);
            allowedMethods = normalizeMethods(allowedMethods);
            allowedPaths = normalizePaths(allowedPaths);
            maxRequestBytes = positive(maxRequestBytes, "maxRequestBytes");
            maxResponseBytes = positive(maxResponseBytes, "maxResponseBytes");
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
        }

        /**
         * Checks every request property that is part of the frozen lease policy.
         *
         * @param method       caller method
         * @param path         caller path
         * @param requestBytes caller body size
         * @return true only for an exact method/path and bounded body
         */
        public boolean allows(String method, String path, int requestBytes) {
            String safeMethod = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
            String safePath;
            try {
                safePath = normalizePath(path);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            return requestBytes >= 0
                    && requestBytes <= maxRequestBytes
                    && allowedMethods.contains(safeMethod)
                    && allowedPaths.contains(safePath);
        }

        private static String normalizeUpstreamBaseUrl(String value) {
            String normalized = value == null ? "" : value.strip();
            URI uri;
            try {
                uri = URI.create(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("upstreamBaseUrl must be an absolute HTTP(S) URL", exception);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme))
                    || uri.getHost() == null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("upstreamBaseUrl must be an absolute HTTP(S) URL without query, fragment, or user info");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            try {
                return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null).toString();
            } catch (Exception exception) {
                throw new IllegalArgumentException("upstreamBaseUrl is invalid", exception);
            }
        }

        private static List<String> normalizeMethods(List<String> values) {
            List<String> normalized = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    String method = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
                    if (!method.matches("[A-Z]+")) {
                        throw new IllegalArgumentException("allowedMethods must contain HTTP method tokens only");
                    }
                    if (!normalized.contains(method)) {
                        normalized.add(method);
                    }
                }
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("allowedMethods must not be empty");
            }
            return List.copyOf(normalized);
        }

        private static List<String> normalizePaths(List<String> values) {
            List<String> normalized = new ArrayList<>();
            if (values != null) {
                for (String value : values) {
                    String path = normalizePath(value);
                    if (!normalized.contains(path)) {
                        normalized.add(path);
                    }
                }
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("allowedPaths must not be empty");
            }
            return List.copyOf(normalized);
        }

        private static String normalizePath(String value) {
            String path = value == null ? "" : value.strip();
            if (path.isBlank()
                    || !path.startsWith("/")
                    || path.contains("//")
                    || path.contains("..")
                    || path.contains("?")
                    || path.contains("#")
                    || path.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("relay paths must be normalized absolute paths without query, fragment, or traversal");
            }
            return path;
        }

        private static int positive(int value, String field) {
            if (value <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }

    /**
     * Host-only authorization result. Its raw credential must only be used to construct
     * an outbound upstream request and must never be serialized or returned to Pi.
     *
     * @param rawCredential Host-held provider credential
     * @param relayPolicy   frozen relay route policy
     * @param taskId        bound task identity
     * @param stageRunId    bound stage identity
     * @param providerId    bound provider identity
     */
    record RelayGrant(
            String rawCredential,
            RelayPolicy relayPolicy,
            String taskId,
            String stageRunId,
            String providerId
    ) {
    }
}
