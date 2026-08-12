package com.wish.rd.exec.repair.pi.impl;

import com.wish.rd.exec.repair.pi.PiCredentialLeaseIssuer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory opaque credential lease store for Pi relay mode.
 * Suitable for single-JVM / test wiring; production may swap a durable issuer later.
 */
public final class InMemoryPiCredentialLeaseIssuer implements PiCredentialLeaseIssuer {

    private final ConcurrentHashMap<String, Entry> leases = new ConcurrentHashMap<>();

    @Override
    public PiCredentialLease issue(
            String taskId,
            String stageRunId,
            String providerId,
            String rawCredential,
            Duration ttl,
            int maxCalls
    ) {
        return issue(
                taskId,
                stageRunId,
                providerId,
                rawCredential,
                legacyRelayPolicy(),
                ttl,
                maxCalls
        );
    }

    @Override
    public PiCredentialLease issue(
            String taskId,
            String stageRunId,
            String providerId,
            String rawCredential,
            RelayPolicy relayPolicy,
            Duration ttl,
            int maxCalls
    ) {
        String safeTask = require(taskId, "taskId");
        String safeStage = require(stageRunId, "stageRunId");
        String safeProvider = require(providerId, "providerId");
        String secret = require(rawCredential, "rawCredential");
        RelayPolicy safeRelayPolicy = Objects.requireNonNull(relayPolicy, "relayPolicy must not be null");
        Duration effectiveTtl = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(45)
                : ttl;
        int budget = Math.max(1, maxCalls);
        Instant expiresAt = Instant.now().plus(effectiveTtl);
        String token = "pcl_" + UUID.randomUUID().toString().replace("-", "");
        leases.put(token, new Entry(
                secret,
                safeRelayPolicy,
                expiresAt,
                new AtomicInteger(budget),
                safeTask,
                safeStage,
                safeProvider
        ));
        return new PiCredentialLease(token, expiresAt, budget, safeTask, safeStage, safeProvider);
    }

    @Override
    public Optional<String> redeem(String token) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken.isBlank()) {
            return Optional.empty();
        }
        Entry entry = leases.get(normalizedToken);
        if (entry == null) {
            return Optional.empty();
        }
        if (!Instant.now().isBefore(entry.expiresAt)) {
            leases.remove(normalizedToken, entry);
            return Optional.empty();
        }
        return consume(normalizedToken, entry);
    }

    @Override
    public Optional<String> redeem(String token, String taskId, String stageRunId, String providerId) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken.isBlank()) {
            return Optional.empty();
        }
        Entry entry = leases.get(normalizedToken);
        if (entry == null || !Instant.now().isBefore(entry.expiresAt)) {
            if (entry != null) {
                leases.remove(normalizedToken, entry);
            }
            return Optional.empty();
        }
        if (!entry.matches(taskId, stageRunId, providerId)) {
            return Optional.empty();
        }
        return consume(normalizedToken, entry);
    }

    @Override
    public Optional<RelayGrant> authorize(
            String token,
            String taskId,
            String stageRunId,
            String providerId,
            String method,
            String path,
            int requestBytes
    ) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken.isBlank()) {
            return Optional.empty();
        }
        Entry entry = leases.get(normalizedToken);
        if (entry == null || !Instant.now().isBefore(entry.expiresAt)) {
            if (entry != null) {
                leases.remove(normalizedToken, entry);
            }
            return Optional.empty();
        }
        if (!entry.matches(taskId, stageRunId, providerId)
                || !entry.relayPolicy.allows(method, path, requestBytes)) {
            return Optional.empty();
        }
        int remaining = entry.remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        if (remaining <= 0) {
            leases.remove(normalizedToken, entry);
            return Optional.empty();
        }
        return Optional.of(new RelayGrant(
                entry.rawCredential,
                entry.relayPolicy,
                entry.taskId,
                entry.stageRunId,
                entry.providerId
        ));
    }

    private Optional<String> consume(String token, Entry entry) {
        int remaining = entry.remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0);
        if (remaining <= 0) {
            leases.remove(token, entry);
            return Optional.empty();
        }
        return Optional.of(entry.rawCredential);
    }

    private static String normalizeToken(String token) {
        return token == null ? "" : token.strip();
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static RelayPolicy legacyRelayPolicy() {
        return new RelayPolicy(
                "https://legacy.invalid",
                List.of("POST"),
                List.of("/"),
                true,
                1,
                1,
                Duration.ofSeconds(1)
        );
    }

    private record Entry(
            String rawCredential,
            RelayPolicy relayPolicy,
            Instant expiresAt,
            AtomicInteger remaining,
            String taskId,
            String stageRunId,
            String providerId
    ) {
        private Entry {
            Objects.requireNonNull(rawCredential, "rawCredential");
            Objects.requireNonNull(relayPolicy, "relayPolicy");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(remaining, "remaining");
        }

        private boolean matches(String taskId, String stageRunId, String providerId) {
            return Objects.equals(this.taskId, normalizeBinding(taskId))
                    && Objects.equals(this.stageRunId, normalizeBinding(stageRunId))
                    && Objects.equals(this.providerId, normalizeBinding(providerId));
        }

        private static String normalizeBinding(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
