package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.retry.RequirementRetryDispatchTransactionPort;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.TaskRetryTaskPort;
import com.wish.rd.engine.retry.model.InitializeRequirementRetryCommand;
import com.wish.rd.engine.retry.model.RequirementRetryDispatchResult;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.util.Set;
import java.util.function.Function;

/** Synchronized memory parity implementation of first-command retry initialization. */
public class InMemoryRequirementRetryDispatchTransactionAdapter implements RequirementRetryDispatchTransactionPort {

    protected final TaskRetryTaskPort taskPort;
    protected final TaskRetryCheckpointStore checkpointStore;
    protected final RequirementStageCommandStore commandStore;
    protected final TaskRetryAttemptBindingStore bindingStore;

    public InMemoryRequirementRetryDispatchTransactionAdapter(
            TaskRetryTaskPort taskPort,
            TaskRetryCheckpointStore checkpointStore,
            RequirementStageCommandStore commandStore,
            TaskRetryAttemptBindingStore bindingStore
    ) {
        this.taskPort = java.util.Objects.requireNonNull(taskPort, "taskPort must not be null");
        this.checkpointStore = java.util.Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.commandStore = java.util.Objects.requireNonNull(commandStore, "commandStore must not be null");
        this.bindingStore = java.util.Objects.requireNonNull(bindingStore, "bindingStore must not be null");
    }

    @Override
    public synchronized RequirementRetryDispatchResult initialize(
            TaskRetryCheckpoint proposed,
            Function<TaskRetryCheckpoint, InitializeRequirementRetryCommand> winnerInitializer
    ) {
        if (proposed == null || winnerInitializer == null) {
            throw new IllegalArgumentException("retry checkpoint initializer must not be null");
        }
        TaskRetryCheckpointStore.CreateResult created = checkpointStore.createOrGet(proposed);
        if (!created.created()) {
            return replay(created.checkpoint());
        }
        InitializeRequirementRetryCommand request = winnerInitializer.apply(created.checkpoint());
        if (request == null || !created.checkpoint().equals(request.checkpoint())) {
            throw new IllegalStateException("retry initializer must preserve the winning checkpoint identity");
        }
        request.plannedBindings().forEach(bindingStore::save);
        RdRequirementTask recovering = taskPort.markRecovering(
                request.checkpoint().taskId(), "checkpoint-bound retry: " + request.route().firstStage());
        String policyRunId = inheritsSourcePolicyRunId(request.route())
                ? request.checkpoint().sourcePolicyRunId()
                : "";
        RequirementStageCommand requestedCommand = RequirementStageCommand.pending(
                request.commandId(), recovering.taskId(), recovering.version(), recovering.fencingToken(),
                request.route().role(), request.route().firstStage(), 0, request.maxAttempts(),
                request.deadlineEpochMillis(), com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC,
                Set.of(com.wish.rd.engine.scheduling.model.ScheduleResourceClass.GENERIC), request.projectId(),
                request.providerId(), request.priority(), policyRunId, request.checkpoint().checkpointId(),
                request.checkpoint().businessGeneration(), request.targetRetryBindingId(), request.nowEpochMillis());
        RequirementStageCommand effective = commandStore.enqueue(requestedCommand);
        requireExactCommand(requestedCommand, effective);
        TaskRetryCheckpoint dispatched = checkpointStore.dispatch(
                request.checkpoint().checkpointId(), recovering.version(), recovering.fencingToken(),
                effective.commandId(), request.nowEpochMillis());
        return new RequirementRetryDispatchResult(dispatched, effective, false);
    }

    private RequirementRetryDispatchResult replay(TaskRetryCheckpoint checkpoint) {
        if (checkpoint.status() != com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus.DISPATCHED
                || checkpoint.dispatchCommandId().isBlank()) {
            throw new IllegalStateException("retry checkpoint replay has no durable dispatched command: "
                    + checkpoint.checkpointId());
        }
        RequirementStageCommand command = commandStore.findById(checkpoint.dispatchCommandId())
                .orElseThrow(() -> new IllegalStateException("retry checkpoint dispatch command is missing: "
                        + checkpoint.dispatchCommandId()));
        if (!command.retryCheckpointId().equals(checkpoint.checkpointId())
                || command.businessGeneration() != checkpoint.businessGeneration()) {
            throw new IllegalStateException("retry checkpoint replay command identity differs from requested route");
        }
        return new RequirementRetryDispatchResult(checkpoint, command, true);
    }

    private static boolean inheritsSourcePolicyRunId(TaskRetryRoute route) {
        if (route.primaryAttemptKind() == com.wish.rd.engine.retry.model.TaskRetryAttemptKind.AGENT_STAGE) {
            return true;
        }
        String stage = route.firstStage() == null ? "" : route.firstStage();
        return "HOST_VERIFY".equals(stage) || stage.startsWith("PUBLICATION");
    }

    private static void requireExactCommand(RequirementStageCommand requested, RequirementStageCommand effective) {
        if (!requested.equals(effective)) {
            throw new IllegalStateException("retry first command immutable identity conflict: "
                    + requested.commandId());
        }
    }
}
