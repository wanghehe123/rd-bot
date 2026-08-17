package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.DeliveryAlertCandidate;

/**
 * Narrow sink for delivery SLO candidates. Implementations must not submit, retry,
 * approve, resize capacity, renew a lease, or advance task state.
 */
public interface DeliverySloAlertSinkPort {

    /**
     * Emits one candidate. Default production wiring is a no-op until notifications
     * are explicitly approved.
     *
     * @param candidate candidate
     */
    void emit(DeliveryAlertCandidate candidate);

    /**
     * @return sink that drops every candidate
     */
    static DeliverySloAlertSinkPort noop() {
        return candidate -> {
        };
    }
}
