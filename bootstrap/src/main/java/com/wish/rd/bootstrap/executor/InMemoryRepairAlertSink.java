package com.wish.rd.bootstrap.executor;

import com.wish.rd.exec.repair.alert.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-config repair alert sink for local startup and tests.
 */
@Component
@ConditionalOnMissingBean(RepairAlertSinkPort.class)
public class InMemoryRepairAlertSink implements RepairAlertSinkPort {

    private final List<RepairAlert> alerts = new ArrayList<>();

    @Override
    public synchronized void publish(RepairAlert alert) {
        alerts.add(Objects.requireNonNull(alert, "alert must not be null"));
    }

    /**
     * Returns a point-in-time immutable snapshot of published alerts.
     *
     * @return published alert snapshot
     */
    public synchronized List<RepairAlert> alerts() {
        return List.copyOf(alerts);
    }
}
