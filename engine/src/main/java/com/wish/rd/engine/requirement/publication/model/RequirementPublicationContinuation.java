package com.wish.rd.engine.requirement.publication.model;

import java.util.regex.Pattern;

/**
 * Immutable recovery work captured after publication reconciliation proves that a remote
 * branch or pull request exists.
 *
 * <p>The operation id becomes part of the durable stage identity. This keeps a reconciled
 * continuation distinct from a prior terminal {@code PUBLICATION} command while allowing the
 * stage-command store's task/role/stage uniqueness constraint to deduplicate concurrent
 * scheduler instances.
 */
public record RequirementPublicationContinuation(
        String operationId,
        String taskId,
        long taskVersion,
        long fencingToken,
        String projectId,
        String priority
) {

    private static final String STAGE_PREFIX = "PUBLICATION:";
    private static final Pattern OPERATION_ID = Pattern.compile("sha256:[a-f0-9]{64}");

    public RequirementPublicationContinuation {
        operationId = requireOperationId(operationId);
        taskId = require(taskId, "taskId");
        if (taskVersion < 0L) {
            throw new IllegalArgumentException("taskVersion must not be negative");
        }
        if (fencingToken <= 0L) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        projectId = projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
        priority = priority == null || priority.isBlank() ? "P2" : priority.strip().toUpperCase();
    }

    /** Returns the durable stage key for this immutable publication operation. */
    public String stage() {
        return stageFor(operationId);
    }

    /**
     * Builds a stage key that fits the database {@code VARCHAR(128)} stage column.
     *
     * @param operationId stable sha256 publication operation identity
     * @return {@code PUBLICATION:<operationId>}
     */
    public static String stageFor(String operationId) {
        String stage = STAGE_PREFIX + requireOperationId(operationId);
        if (stage.length() > 128) {
            throw new IllegalArgumentException("publication continuation stage exceeds 128 characters");
        }
        return stage;
    }

    private static String requireOperationId(String operationId) {
        String normalized = require(operationId, "operationId").toLowerCase();
        if (!OPERATION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("operationId must be a sha256 digest");
        }
        return normalized;
    }

    private static String require(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
