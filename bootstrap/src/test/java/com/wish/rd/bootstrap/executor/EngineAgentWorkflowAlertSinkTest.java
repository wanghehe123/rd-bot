package com.wish.rd.bootstrap.executor;

import com.wish.rd.bootstrap.executor.impl.EngineAgentWorkflowAlertSink;

import com.wish.rd.engine.agent.model.AgentWorkflowAlert;
import com.wish.rd.engine.agent.model.AgentWorkflowAlertType;
import com.wish.rd.exec.repair.alert.model.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.model.RepairAlertType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineAgentWorkflowAlertSinkTest {

    @Test
    void shouldMapAgentWorkflowAlertToRepairAlertSink() {
        RecordingRepairAlertSink repairAlertSink = new RecordingRepairAlertSink();
        EngineAgentWorkflowAlertSink sink = new EngineAgentWorkflowAlertSink(repairAlertSink);

        sink.publish(new AgentWorkflowAlert(
                "7478000000000000000",
                "7478000000000001001",
                AgentWorkflowAlertType.QA_FAILED,
                "QA 未通过真实验收",
                Map.of("role", "QA_AGENT"),
                1_783_000_000_000L
        ));

        RepairAlert alert = repairAlertSink.alerts().getFirst();
        assertEquals("7478000000000001001", alert.repairRecordId());
        assertEquals("7478000000000000000", alert.taskId());
        assertEquals(RepairAlertType.QA_FAILED, alert.type());
        assertEquals("QA_AGENT", alert.metadata().get("role"));
    }

    @Test
    void shouldMapProviderFallbackAlertToRepairAlertSink() {
        RecordingRepairAlertSink repairAlertSink = new RecordingRepairAlertSink();
        EngineAgentWorkflowAlertSink sink = new EngineAgentWorkflowAlertSink(repairAlertSink);

        sink.publish(new AgentWorkflowAlert(
                "7478000000000000000",
                "7478000000000001002",
                AgentWorkflowAlertType.PROVIDER_FALLBACK,
                "provider deepseek failed, fallback to claude",
                Map.of(
                        "role", "CODING_AGENT",
                        "failedProvider", "deepseek",
                        "activeProvider", "claude"
                ),
                1_783_000_000_000L
        ));

        RepairAlert alert = repairAlertSink.alerts().getFirst();
        assertEquals("7478000000000001002", alert.repairRecordId());
        assertEquals("7478000000000000000", alert.taskId());
        assertEquals(RepairAlertType.PROVIDER_FALLBACK, alert.type());
        assertEquals("deepseek", alert.metadata().get("failedProvider"));
        assertEquals("claude", alert.metadata().get("activeProvider"));
    }

    private static final class RecordingRepairAlertSink implements RepairAlertSinkPort {

        private final List<RepairAlert> alerts = new ArrayList<>();

        @Override
        public void publish(RepairAlert alert) {
            alerts.add(alert);
        }

        private List<RepairAlert> alerts() {
            return List.copyOf(alerts);
        }
    }
}
