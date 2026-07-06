package com.wish.rd.engine.audit;

import java.util.List;
import com.wish.rd.engine.audit.model.RepairAuditEvent;

/**
 * Query port for repair workflow audit events.
 */
public interface RepairAuditQueryPort {

    /**
     * Lists audit events.
     *
     * @return audit event snapshot
     */
    List<RepairAuditEvent> events();

    /**
     * Lists audit events for one repair record.
     *
     * @param repairRecordId repair record id
     * @return audit event snapshot
     */
    List<RepairAuditEvent> eventsByRepairRecordId(String repairRecordId);
}
