package com.wish.rd.bootstrap.executor;

import com.wish.rd.engine.agent.AgentWorkflowAlert;
import com.wish.rd.engine.agent.AgentWorkflowAlertSinkPort;
import com.wish.rd.engine.agent.AgentWorkflowAlertType;
import com.wish.rd.exec.repair.alert.RepairAlert;
import com.wish.rd.exec.repair.alert.RepairAlertSinkPort;
import com.wish.rd.exec.repair.alert.RepairAlertType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 将 engine 多 Agent 告警桥接到 exec 通用修复告警端口。
 */
@Component
public final class EngineAgentWorkflowAlertSink implements AgentWorkflowAlertSinkPort {

    private final RepairAlertSinkPort delegate;

    public EngineAgentWorkflowAlertSink(RepairAlertSinkPort delegate) {
        this.delegate = delegate;
    }

    @Autowired
    public EngineAgentWorkflowAlertSink(ObjectProvider<RepairAlertSinkPort> delegateProvider) {
        this(delegateProvider == null ? null : delegateProvider.getIfAvailable());
    }

    @Override
    public void publish(AgentWorkflowAlert alert) {
        Objects.requireNonNull(alert, "alert must not be null");
        if (delegate == null) {
            return;
        }
        delegate.publish(new RepairAlert(
                alert.stageRunId().isBlank() ? alert.taskId() : alert.stageRunId(),
                alert.taskId(),
                toRepairAlertType(alert.type()),
                alert.message(),
                alert.metadata(),
                alert.createdAtEpochMillis()
        ));
    }

    private RepairAlertType toRepairAlertType(AgentWorkflowAlertType type) {
        return switch (type == null ? AgentWorkflowAlertType.STAGE_FAILED_NEEDS_HUMAN : type) {
            case STAGE_FAILED_RETRYABLE -> RepairAlertType.STAGE_FAILED_RETRYABLE;
            case STAGE_FAILED_NEEDS_HUMAN -> RepairAlertType.STAGE_FAILED_NEEDS_HUMAN;
            case PROVIDER_FALLBACK -> RepairAlertType.PROVIDER_FALLBACK;
            case QA_FAILED -> RepairAlertType.QA_FAILED;
            case DELIVERY_REVIEW_FAILED -> RepairAlertType.DELIVERY_REVIEW_FAILED;
            case PR_PUBLICATION_FAILED -> RepairAlertType.PR_PUBLICATION_FAILED;
            case EXPERIENCE_CAPTURE_FAILED -> RepairAlertType.EXPERIENCE_CAPTURE_FAILED;
        };
    }
}
