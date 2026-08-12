package com.wish.rd.engine.retry;

import com.wish.rd.engine.retry.model.InitializeRequirementRetryCommand;
import com.wish.rd.engine.retry.model.RequirementRetryDispatchResult;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;

import java.util.function.Function;

/** Initializes one exact checkpoint-bound retry generation before it is scheduled. */
public interface RequirementRetryDispatchTransactionPort {

    /**
     * Creates the checkpoint first, then invokes the initializer only for the winning generation.
     * The callback runs inside the implementation's transaction boundary so attempt creation,
     * bindings, recovery state, and the first command cannot be split across transactions.
     */
    RequirementRetryDispatchResult initialize(
            TaskRetryCheckpoint proposed,
            Function<TaskRetryCheckpoint, InitializeRequirementRetryCommand> winnerInitializer
    );
}
