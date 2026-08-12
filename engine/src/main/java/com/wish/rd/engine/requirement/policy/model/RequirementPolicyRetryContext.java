package com.wish.rd.engine.requirement.policy.model;

import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;

/**
 * Immutable checkpoint lineage attached to a checkpoint-bound policy control operation.
 *
 * <p>Normal policy operations use {@link #empty()}; retry callers must carry every identity
 * needed by later policy control transactions without reconstructing it from task-wide state.
 */
public record RequirementPolicyRetryContext(
        String checkpointId,
        long businessGeneration,
        String targetRetryBindingId,
        String sourcePolicyRunId,
        String sourcePlanDigest,
        TaskRetryCheckpointStatus expectedCheckpointStatus
) {
    private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

    /** Normalizes and validates either the explicit empty context or one exact retry lineage. */
    public RequirementPolicyRetryContext {
        checkpointId = safe(checkpointId);
        targetRetryBindingId = safe(targetRetryBindingId);
        sourcePolicyRunId = safe(sourcePolicyRunId);
        sourcePlanDigest = safe(sourcePlanDigest);
        if (checkpointId.isBlank()) {
            if (businessGeneration != 0L || !targetRetryBindingId.isBlank() || !sourcePolicyRunId.isBlank()
                    || !sourcePlanDigest.isBlank() || expectedCheckpointStatus != null) {
                throw new IllegalArgumentException("empty policy retry context must not carry retry identity");
            }
        } else {
        if (businessGeneration <= 0L || targetRetryBindingId.isBlank() || sourcePolicyRunId.isBlank()
                || !sourcePlanDigest.matches(DIGEST_PATTERN) || expectedCheckpointStatus == null) {
                throw new IllegalArgumentException("retry policy context requires complete checkpoint lineage");
            }
            try {
                if (Long.parseLong(checkpointId) != businessGeneration) {
                    throw new IllegalArgumentException("retry policy businessGeneration must equal checkpointId");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("retry policy checkpointId must be numeric", exception);
            }
            if (expectedCheckpointStatus != TaskRetryCheckpointStatus.CREATED
                    && expectedCheckpointStatus != TaskRetryCheckpointStatus.DISPATCHED
                    && expectedCheckpointStatus != TaskRetryCheckpointStatus.WAITING_APPROVAL) {
                throw new IllegalArgumentException("retry policy context has an invalid expected checkpoint status");
            }
        }
    }

    /** Returns the explicit context used by every normal, non-retry policy operation. */
    public static RequirementPolicyRetryContext empty() {
        return new RequirementPolicyRetryContext("", 0L, "", "", "", null);
    }

    /** Returns whether this is the normal-operation context. */
    public boolean isEmpty() {
        return checkpointId.isBlank();
    }

    /** Rejects a policy-control command whose retry generation differs from this lineage. */
    public void requireControlCommandIdentity(
            String commandCheckpointId, long commandGeneration, String commandTargetBindingId
    ) {
        if (isEmpty()) {
            if (!safe(commandCheckpointId).isBlank() || commandGeneration != 0L
                    || !safe(commandTargetBindingId).isBlank()) {
                throw new IllegalArgumentException("normal policy context cannot consume a retry command");
            }
        } else if (!checkpointId.equals(safe(commandCheckpointId)) || businessGeneration != commandGeneration
                || !safe(commandTargetBindingId).isBlank()) {
            throw new IllegalArgumentException("policy control command does not match retry context");
        }
    }

    /** Rejects a role continuation whose retry generation or target binding differs from this lineage. */
    public void requireContinuationIdentity(
            String commandCheckpointId, long commandGeneration, String commandTargetBindingId
    ) {
        if (isEmpty()) {
            if (!safe(commandCheckpointId).isBlank() || commandGeneration != 0L
                    || !safe(commandTargetBindingId).isBlank()) {
                throw new IllegalArgumentException("normal policy context cannot produce a retry continuation");
            }
        } else if (!checkpointId.equals(safe(commandCheckpointId)) || businessGeneration != commandGeneration
                || !targetRetryBindingId.equals(safe(commandTargetBindingId))) {
            throw new IllegalArgumentException("policy continuation does not match retry context");
        }
    }

    /** Requires the supplied frozen plan digest to match this retry source when present. */
    public void requireSourcePlanDigest(String planDigest) {
        if (!isEmpty() && !sourcePlanDigest.equals(safe(planDigest))) {
            throw new IllegalArgumentException("policy retry context source plan digest conflicts with policy plan");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
