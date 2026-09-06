package com.wish.rd.engine.requirement.answer;

/**
 * Digest- and concurrency-bound operator answer.
 *
 * @param taskId task id
 * @param expectedTaskVersion snapshot version
 * @param expectedTaskFence fencing token
 * @param decisionHash latest ASK decision hash
 * @param answerRequestId idempotency key
 * @param answerText operator material
 */
public record AnswerRequirementCommand(
        String taskId,
        long expectedTaskVersion,
        long expectedTaskFence,
        String decisionHash,
        String answerRequestId,
        String answerText
) {
    public AnswerRequirementCommand {
        taskId = require(taskId, "taskId");
        if (expectedTaskVersion < 0L || expectedTaskFence <= 0L) {
            throw new IllegalArgumentException("answer requires a positive task fence");
        }
        decisionHash = require(decisionHash, "decisionHash");
        answerRequestId = require(answerRequestId, "answerRequestId");
        answerText = answerText == null ? "" : answerText.strip();
        if (answerText.isBlank()) {
            throw new IllegalArgumentException("answerText is required");
        }
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
