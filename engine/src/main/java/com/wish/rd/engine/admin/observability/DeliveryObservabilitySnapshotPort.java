package com.wish.rd.engine.admin.observability;

import com.wish.rd.engine.admin.observability.model.DeliveryLedgerSnapshot;
import com.wish.rd.engine.admin.observability.model.DeliveryObservabilityQuery;
import com.wish.rd.engine.scheduling.RequirementDeliveryMetrics;

/**
 * Loads durable delivery ledgers and optional live scheduler snapshots.
 *
 * <p>Implementations live in bootstrap. Engine never issues SQL.
 */
public interface DeliveryObservabilitySnapshotPort {

    /**
     * Loads task/stage/command rows for the normalized query.
     *
     * @param query normalized query
     * @return ledger snapshot; {@code available=false} on query failure without throwing when
     *         the adapter can classify the error
     */
    DeliveryLedgerSnapshot loadLedger(DeliveryObservabilityQuery query);

    /**
     * Loads the process-window scheduler snapshot. Never required for durable backlog.
     *
     * @return scheduler snapshot or {@code null} when the dispatcher is not running
     */
    default RequirementDeliveryMetrics.Snapshot loadScheduler() {
        return null;
    }
}
