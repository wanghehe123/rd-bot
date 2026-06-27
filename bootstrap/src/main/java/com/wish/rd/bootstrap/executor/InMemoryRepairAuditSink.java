package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.audit.RepairAuditEvent;
import com.wish.rd.engine.audit.RepairAuditQueryPort;
import com.wish.rd.engine.audit.RepairAuditSinkPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-config repair audit sink for local startup and operations views.
 */
@Component
@ConditionalOnMissingBean(RepairAuditSinkPort.class)
public class InMemoryRepairAuditSink implements RepairAuditSinkPort, RepairAuditQueryPort {

    private final List<RepairAuditEvent> events = new ArrayList<>();

    @Override
    public synchronized void publish(RepairAuditEvent event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
    }

    /**
     * Returns all audit events ordered by publish time.
     *
     * @return audit event snapshot
     */
    public synchronized List<RepairAuditEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns audit events for one repair record.
     *
     * @param repairRecordId repair record ID
     * @return audit event snapshot
     */
    public synchronized List<RepairAuditEvent> eventsByRepairRecordId(String repairRecordId) {
        String normalized = repairRecordId == null ? "" : repairRecordId.strip();
        return events.stream()
                .filter(event -> normalized.equals(event.repairRecordId()))
                .toList();
    }
}
