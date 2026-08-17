package com.wish.rd.bootstrap.observability;

import com.wish.rd.engine.admin.observability.DeliveryObservabilitySnapshotPort;
import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fallback when PostgreSQL is not the knowledge store. Queries stay available and report
 * collector failure instead of blocking application startup.
 */
@Component
@ConditionalOnMissingBean(DeliveryObservabilitySnapshotPort.class)
public final class UnavailableDeliveryObservabilitySnapshotAdapter implements DeliveryObservabilitySnapshotPort {

    @Override
    public DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query) {
        return DeliveryLedgerSnapshot.failed(Instant.now(), "delivery ledger unavailable");
    }
}
