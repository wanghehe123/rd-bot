package com.wish.rd.bootstrap.threading.impl;

import com.wish.rd.engine.requirement.RequirementDeliveryEngine;
import com.wish.rd.engine.requirement.job.RequirementStageExecutor;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.model.RequirementDeliveryResult;
import org.springframework.stereotype.Component;

/**
 * Host adapter that runs a leased stage through the requirement engine.
 *
 * <p>Keeping this adapter outside the dispatcher makes the durable queue boundary explicit and
 * leaves room for role/stage-specific handlers without changing the scheduler contract.
 */
@Component
public final class EngineRequirementStageExecutor implements RequirementStageExecutor {

    private final RequirementDeliveryEngine deliveryEngine;

    public EngineRequirementStageExecutor(RequirementDeliveryEngine deliveryEngine) {
        this.deliveryEngine = deliveryEngine;
    }

    @Override
    public RequirementDeliveryResult execute(RequirementStageCommand command) {
        return deliveryEngine.executeStage(command);
    }

    @Override
    public RequirementStageExecutionPlan plan(RequirementStageCommand command) {
        return deliveryEngine.planStage(command);
    }
}
