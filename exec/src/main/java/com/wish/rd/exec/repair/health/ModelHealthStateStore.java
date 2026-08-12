package com.wish.rd.exec.repair.health;

import com.wish.rd.exec.repair.model.ModelCircuitBreakerPolicy;
import com.wish.rd.exec.repair.model.ModelHealthSnapshot;

import java.util.Map;

/**
 * Atomic shared-state port for Provider circuit-breaker transitions.
 *
 * <p>Production adapters use Redis so multiple executor instances share OPEN and HALF_OPEN state.
 * The in-memory adapter is reserved for tests and explicit local mode.
 */
public interface ModelHealthStateStore {

    /**
     * Reports whether the Provider must currently be rejected.
     *
     * @param modelId Provider or model identifier
     * @param nowEpochMillis current Host time
     * @return {@code true} while OPEN or while a live HALF_OPEN probe owns the lease
     */
    boolean isUnavailable(String modelId, long nowEpochMillis);

    /**
     * Atomically acquires permission for a normal call or the single HALF_OPEN probe.
     *
     * @param modelId Provider or model identifier
     * @param policy circuit-breaker policy
     * @param nowEpochMillis current Host time
     * @return whether the call is permitted
     */
    boolean tryAcquireCall(String modelId, ModelCircuitBreakerPolicy policy, long nowEpochMillis);

    /**
     * Closes the circuit after a successful Provider call.
     *
     * @param modelId Provider or model identifier
     */
    void markSuccess(String modelId);

    /**
     * Records a failed call and opens or reopens the circuit when required.
     *
     * @param modelId Provider or model identifier
     * @param policy circuit-breaker policy
     * @param nowEpochMillis current Host time
     */
    void markFailure(String modelId, ModelCircuitBreakerPolicy policy, long nowEpochMillis);

    /**
     * Returns one immutable health snapshot.
     *
     * @param modelId Provider or model identifier
     * @param nowEpochMillis current Host time
     * @return current snapshot
     */
    ModelHealthSnapshot snapshot(String modelId, long nowEpochMillis);

    /**
     * Returns immutable snapshots for all observed Providers.
     *
     * @param nowEpochMillis current Host time
     * @return snapshots keyed by normalized Provider id
     */
    Map<String, ModelHealthSnapshot> snapshots(long nowEpochMillis);
}
