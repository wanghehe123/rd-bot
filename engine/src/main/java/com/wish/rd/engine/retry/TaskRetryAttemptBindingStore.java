package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;

import java.util.List;
import java.util.Optional;

/** Store port for immutable checkpoint-scoped retry attempt bindings. */
public interface TaskRetryAttemptBindingStore {

    TaskRetryAttemptBinding save(TaskRetryAttemptBinding binding);

    Optional<TaskRetryAttemptBinding> findById(String bindingId);

    List<TaskRetryAttemptBinding> listByCheckpoint(String checkpointId);

    /**
     * Resolves the sole top-level binding for a checkpoint/kind/role identity.
     *
     * <p>Implementations must fail closed if more than one ordinal exists for that identity;
     * callers that require an ordinal-specific target must use a narrower lookup.
     */
    Optional<TaskRetryAttemptBinding> findPrimary(
            String checkpointId, TaskRetryAttemptKind kind, AgentRole role
    );

    Optional<TaskRetryAttemptBinding> findChild(
            String checkpointId,
            String parentBindingId,
            TaskRetryAttemptKind kind,
            int ordinal
    );

    /**
     * Returns the sole binding that already claims this agent-stage attempt.
     *
     * <p>{@code rd_task_retry_attempt_bindings.stage_run_id} is globally unique, so a PENDING
     * attempt bound to an earlier checkpoint cannot join a later checkpoint.
     */
    Optional<TaskRetryAttemptBinding> findByStageRunId(String stageRunId);
}
