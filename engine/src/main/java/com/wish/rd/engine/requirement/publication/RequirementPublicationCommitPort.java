package com.wish.rd.engine.requirement.publication;

import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

/**
 * Finalizes a confirmed requirement pull request together with its task state.
 *
 * <p>The production adapter owns one transaction spanning the fenced task snapshot/event write
 * and the {@code PR_CONFIRMED -> COMMITTED} publication compare-and-set.
 */
@FunctionalInterface
public interface RequirementPublicationCommitPort {

    /**
     * Commits the requirement task and its already-confirmed publication operation.
     *
     * @param command immutable task/publication finalization input
     * @return committed requirement task snapshot
     */
    RdRequirementTask commit(RequirementPublicationCommitCommand command);

    /**
     * Immutable input for one task/publication finalization operation.
     *
     * @param taskId requirement task id
     * @param operationId immutable publication operation id
     * @param pullRequestUrl confirmed pull request URL
     * @param executionResultJson durable task execution result
     * @param expectedVersion version captured from the stage snapshot
     * @param expectedStatus status captured from the stage snapshot
     * @param expectedFencingToken fencing token captured from the stage snapshot
     */
    record RequirementPublicationCommitCommand(
            String taskId,
            String operationId,
            String pullRequestUrl,
            String executionResultJson,
            long expectedVersion,
            RdTaskStatus expectedStatus,
            long expectedFencingToken
    ) {

        public RequirementPublicationCommitCommand {
            taskId = require(taskId, "taskId");
            operationId = require(operationId, "operationId");
            pullRequestUrl = require(pullRequestUrl, "pullRequestUrl");
            executionResultJson = executionResultJson == null || executionResultJson.isBlank()
                    ? "{}"
                    : executionResultJson.strip();
            if (expectedVersion < 0L) {
                throw new IllegalArgumentException("expectedVersion must not be negative");
            }
            if (expectedStatus == null) {
                throw new IllegalArgumentException("expectedStatus must not be null");
            }
            if (expectedFencingToken <= 0L) {
                throw new IllegalArgumentException("expectedFencingToken must be positive");
            }
        }

        private static String require(String value, String fieldName) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
