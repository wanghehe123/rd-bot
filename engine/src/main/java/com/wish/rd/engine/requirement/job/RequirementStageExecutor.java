package com.wish.rd.engine.requirement.job;

import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;

/**
 * Executes one leased requirement stage command.
 *
 * <p>The dispatcher depends on this narrow port so queue ownership, leases and fairness remain
 * independent from the delivery workflow implementation. A production adapter may delegate to
 * the existing engine while the stage handlers are migrated incrementally; the dispatch boundary
 * still prevents request/recovery code from bypassing durable command claims.
 */
@FunctionalInterface
public interface RequirementStageExecutor {

    /**
     * Produces a non-mutating stage plan for Host-owned durable finalization.
     *
     * <p>Implementations that still expose the legacy result method must explicitly override
     * this method before they can be used by the durable dispatcher.
     *
     * @param command leased command with task snapshot fencing and stage identity
     * @return immutable task-mutation plan
     */
    default RequirementStageExecutionPlan plan(RequirementStageCommand command) {
        throw new UnsupportedOperationException("stage executor does not support non-mutating plans: "
                + (command == null ? "null" : command.commandId()));
    }

    /**
     * Executes the bounded action represented by a leased command.
     *
     * @param command command with task snapshot fencing and stage identity
     * @return stage outcome used to persist command/job state
     */
    RequirementDeliveryResult execute(RequirementStageCommand command);
}
