package com.wish.rd.exec.repair.health.impl;

import com.wish.rd.exec.repair.health.ModelHealthStateStore;
import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;
import com.wish.rd.exec.repair.model.ModelHealthState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-process circuit state for unit tests and explicit local execution mode. */
public final class InMemoryModelHealthStateStore implements ModelHealthStateStore {

    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /** Creates an empty local health-state store. */
    public InMemoryModelHealthStateStore() {
    }

    @Override
    public boolean isUnavailable(String modelId, long nowEpochMillis) {
        ModelHealth health = healthById.get(normalizeId(modelId));
        if (health == null) {
            return false;
        }
        if (health.state == ModelHealthState.OPEN) {
            return health.openUntil > nowEpochMillis;
        }
        return health.state == ModelHealthState.HALF_OPEN
                && health.halfOpenInFlight
                && health.halfOpenLeaseUntil > nowEpochMillis;
    }

    @Override
    public boolean tryAcquireCall(
            String modelId,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        String normalized = normalizeId(modelId);
        AtomicBoolean allowed = new AtomicBoolean(false);
        healthById.compute(normalized, (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            if (health.state == ModelHealthState.OPEN) {
                if (health.openUntil > nowEpochMillis) {
                    return health;
                }
                health.state = ModelHealthState.HALF_OPEN;
                acquireHalfOpen(health, policy, nowEpochMillis);
                allowed.set(true);
                return health;
            }
            if (health.state == ModelHealthState.HALF_OPEN) {
                if (health.halfOpenInFlight && health.halfOpenLeaseUntil > nowEpochMillis) {
                    return health;
                }
                acquireHalfOpen(health, policy, nowEpochMillis);
                allowed.set(true);
                return health;
            }
            allowed.set(true);
            return health;
        });
        return allowed.get();
    }

    @Override
    public void markSuccess(String modelId) {
        healthById.compute(normalizeId(modelId), (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            health.state = ModelHealthState.CLOSED;
            health.consecutiveFailures = 0;
            health.openUntil = 0L;
            health.halfOpenInFlight = false;
            health.halfOpenLeaseUntil = 0L;
            return health;
        });
    }

    @Override
    public void markFailure(
            String modelId,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        healthById.compute(normalizeId(modelId), (key, value) -> {
            ModelHealth health = value == null ? new ModelHealth() : value;
            if (health.state == ModelHealthState.HALF_OPEN) {
                open(health, policy, nowEpochMillis);
                return health;
            }
            health.consecutiveFailures++;
            if (health.consecutiveFailures >= policy.failureThreshold()) {
                open(health, policy, nowEpochMillis);
            }
            return health;
        });
    }

    @Override
    public ModelHealthSnapshot snapshot(String modelId, long nowEpochMillis) {
        String normalized = normalizeId(modelId);
        ModelHealth health = healthById.get(normalized);
        return health == null
                ? closedSnapshot(normalized)
                : health.snapshot(normalized, nowEpochMillis);
    }

    @Override
    public Map<String, ModelHealthSnapshot> snapshots(long nowEpochMillis) {
        Map<String, ModelHealthSnapshot> snapshots = new LinkedHashMap<>();
        healthById.keySet().stream()
                .sorted()
                .forEach(id -> snapshots.put(id, snapshot(id, nowEpochMillis)));
        return Map.copyOf(snapshots);
    }

    private static void acquireHalfOpen(
            ModelHealth health,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        health.halfOpenInFlight = true;
        health.halfOpenLeaseUntil = nowEpochMillis + policy.openDurationMillis();
    }

    private static void open(
            ModelHealth health,
            ModelCircuitBreakerPolicy policy,
            long nowEpochMillis
    ) {
        health.state = ModelHealthState.OPEN;
        health.openUntil = nowEpochMillis + policy.openDurationMillis();
        health.consecutiveFailures = 0;
        health.halfOpenInFlight = false;
        health.halfOpenLeaseUntil = 0L;
    }

    private static ModelHealthSnapshot closedSnapshot(String modelId) {
        return new ModelHealthSnapshot(modelId, ModelHealthState.CLOSED, 0, 0L, false);
    }

    private static String normalizeId(String modelId) {
        return modelId == null ? "" : modelId.strip();
    }

    private static final class ModelHealth {
        private int consecutiveFailures;
        private long openUntil;
        private boolean halfOpenInFlight;
        private long halfOpenLeaseUntil;
        private ModelHealthState state = ModelHealthState.CLOSED;

        private ModelHealthSnapshot snapshot(String modelId, long nowEpochMillis) {
            boolean liveProbe = halfOpenInFlight && halfOpenLeaseUntil > nowEpochMillis;
            return new ModelHealthSnapshot(modelId, state, consecutiveFailures, openUntil, liveProbe);
        }
    }
}
