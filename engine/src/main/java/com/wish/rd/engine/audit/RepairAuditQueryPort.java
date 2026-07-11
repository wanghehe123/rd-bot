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

    /**
     * Lists audit events for one RD task.
     *
     * @param taskId RD task ID
     * @return audit event snapshot
     */
    default List<RepairAuditEvent> eventsByTaskId(String taskId) {
        String safeTaskId = taskId == null ? "" : taskId.strip();
        return events().stream()
                .filter(event -> safeTaskId.equals(event.taskId()))
                .toList();
    }
}
