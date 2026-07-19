package com.wish.rd.engine.control;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.control.model.RdTaskExecutionControlResult;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.rag.runtime.RagStreamTaskRegistry;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Coordinates external stop, latest-stage cancellation, and task cancellation as one use case. */
@Service
public final class RdTaskExecutionControlEngine {

    private static final Comparator<AgentStageRun> RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::updateTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    private final RagStreamTaskRegistry taskRegistry;
    private final AgentStageRunStore stageRunStore;
    private final RdTaskExecutionControlPort executionControlPort;
    private final RequirementDeliveryJobStore deliveryJobStore;

    public RdTaskExecutionControlEngine(
            RagStreamTaskRegistry taskRegistry,
            AgentStageRunStore stageRunStore,
            RdTaskExecutionControlPort executionControlPort
    ) {
        this(taskRegistry, stageRunStore, executionControlPort, (RequirementDeliveryJobStore) null);
    }

    @Autowired
    public RdTaskExecutionControlEngine(
            RagStreamTaskRegistry taskRegistry,
            AgentStageRunStore stageRunStore,
            RdTaskExecutionControlPort executionControlPort,
            ObjectProvider<RequirementDeliveryJobStore> deliveryJobStoreProvider
    ) {
        this(
                taskRegistry,
                stageRunStore,
                executionControlPort,
                deliveryJobStoreProvider == null ? null : deliveryJobStoreProvider.getIfAvailable()
        );
    }

    public RdTaskExecutionControlEngine(
            RagStreamTaskRegistry taskRegistry,
            AgentStageRunStore stageRunStore,
            RdTaskExecutionControlPort executionControlPort,
            RequirementDeliveryJobStore deliveryJobStore
    ) {
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry must not be null");
        this.stageRunStore = Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.executionControlPort = Objects.requireNonNull(executionControlPort, "executionControlPort must not be null");
        this.deliveryJobStore = deliveryJobStore;
    }

    public RdTaskExecutionControlResult stopTask(String taskId, String reason) {
        RdTask current = taskRegistry.getTask(taskId);
        if (current.status() == RdTaskStatus.CANCELLED) {
            return new RdTaskExecutionControlResult(current, false, "", "task already cancelled", 0);
        }
        if (!isCancellable(current.status())) {
            return new RdTaskExecutionControlResult(
                    current, false, "", "task status does not allow cancel: " + current.status(), 0);
        }
        RdTaskExecutionControlPort.ExternalStopResult external;
        try {
            external = executionControlPort.stop(taskId, reason);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            external = new RdTaskExecutionControlPort.ExternalStopResult(
                    false, "", "external stop failed: " + message);
        }
        cancelDeliveryJob(taskId, reason);
        int cancelledStages = cancelLatestActiveStages(taskId, reason);
        RdTask cancelled = taskRegistry.cancelTask(taskId, reason);
        return new RdTaskExecutionControlResult(
                cancelled, external.stopped(), external.containerName(), external.message(), cancelledStages);
    }

    private void cancelDeliveryJob(String taskId, String reason) {
        if (deliveryJobStore == null) {
            return;
        }
        deliveryJobStore.cancelByTask(taskId, reason == null ? "operator stop" : reason, System.currentTimeMillis());
    }

    private boolean isCancellable(RdTaskStatus status) {
        return status != RdTaskStatus.COMPLETED
                && status != RdTaskStatus.MERGED
                && status != RdTaskStatus.DEAD_LETTERED
                && status != RdTaskStatus.DELETED;
    }

    private int cancelLatestActiveStages(String taskId, String reason) {
        List<AgentStageRun> stages = stageRunStore.listByTask(taskId);
        int cancelled = 0;
        for (AgentRole role : AgentRole.values()) {
            Optional<AgentStageRun> latest = stages.stream()
                    .filter(stage -> stage.role() == role)
                    .max(RECENCY);
            if (latest.isPresent() && !latest.get().status().isTerminal()) {
                stageRunStore.transition(
                        latest.get().stageRunId(), AgentStageStatus.CANCELLED, "CANCELLED_BY_OPERATOR", reason,
                        System.currentTimeMillis());
                cancelled++;
            }
        }
        return cancelled;
    }
}
