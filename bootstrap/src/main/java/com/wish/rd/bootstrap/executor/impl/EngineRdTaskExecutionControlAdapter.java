package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.control.RdTaskExecutionControlPort;
import com.wish.rd.exec.repair.execution.RepairExecutionControlPort;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopCommand;
import com.wish.rd.exec.repair.execution.model.RepairExecutionStopResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Adapts the executor stop capability to the engine control-plane port. */
@Component
public final class EngineRdTaskExecutionControlAdapter implements RdTaskExecutionControlPort {

    private final RepairExecutionControlPort delegate;

    public EngineRdTaskExecutionControlAdapter(ObjectProvider<RepairExecutionControlPort> delegateProvider) {
        this.delegate = delegateProvider.getIfAvailable(() -> command -> new RepairExecutionStopResult(
                command.taskId(), command.containerName(), false, "execution control unavailable"));
    }

    @Override
    public ExternalStopResult stop(String taskId, String reason) {
        RepairExecutionStopResult result = delegate.stop(new RepairExecutionStopCommand("", taskId, "", reason));
        return new ExternalStopResult(result.stopped(), result.containerName(), result.message());
    }
}
