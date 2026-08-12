package com.wish.rd.engine.requirement.job.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;

/**
 * Durable saga marker spanning a stage task mutation and command finalization.
 *
 * <p>A marker is prepared before a stage executor may mutate the task. If the process stops after
 * that mutation, recovery can distinguish the already-applied stage from an ordinary stale lease.
 */
public record RequirementStageFinalization(
        String commandId,
        int attemptNo,
        String taskId,
        long expectedTaskVersion,
        long expectedFencingToken,
        RdTaskStatus expectedTaskStatus,
        String stage,
        State state,
        RdTaskStatus outcomeStatus,
        String resultJson,
        String errorMessage,
        String outcomePlanJson,
        String outcomePlanDigest,
        String nextCommandId,
        long preparedAtEpochMillis,
        long finalizedAtEpochMillis
) {

    /** Lifecycle states for one immutable leased stage attempt. */
    public enum State {
        /** The Host has admitted execution but has not finalized its command outcome. */
        PREPARED,
        /** A non-mutating execution plan is durable and must be resumed without re-execution. */
        OUTCOME_RECORDED,
        /** The command outcome and continuation were finalized together. */
        FINALIZED
    }

    /**
     * Normalizes marker fields so recovery decisions never rely on null or negative metadata.
     */
    public RequirementStageFinalization {
        commandId = require(commandId, "commandId");
        attemptNo = Math.max(0, attemptNo);
        taskId = require(taskId, "taskId");
        expectedTaskVersion = Math.max(0L, expectedTaskVersion);
        expectedFencingToken = Math.max(0L, expectedFencingToken);
        expectedTaskStatus = expectedTaskStatus == null ? RdTaskStatus.CREATED : expectedTaskStatus;
        stage = require(stage, "stage");
        state = state == null ? State.PREPARED : state;
        resultJson = safe(resultJson);
        errorMessage = safe(errorMessage);
        outcomePlanJson = safe(outcomePlanJson);
        outcomePlanDigest = safe(outcomePlanDigest);
        nextCommandId = safe(nextCommandId);
        preparedAtEpochMillis = Math.max(0L, preparedAtEpochMillis);
        finalizedAtEpochMillis = Math.max(0L, finalizedAtEpochMillis);
    }

    /**
     * Creates the marker written before invoking a task-mutating stage executor.
     *
     * @param command leased stage command
     * @param nowEpochMillis Host timestamp
     * @return prepared finalization marker
     */
    public static RequirementStageFinalization prepared(
            RequirementStageCommand command,
            RdTaskStatus expectedTaskStatus,
            long nowEpochMillis
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("prepared finalization requires a positive fencingToken");
        }
        return new RequirementStageFinalization(
                command.commandId(),
                command.attemptNo(),
                command.taskId(),
                command.taskVersion(),
                command.fencingToken(),
                expectedTaskStatus,
                command.stage(),
                State.PREPARED,
                null,
                "",
                "",
                "",
                "",
                "",
                nowEpochMillis,
                0L
        );
    }

    /**
     * Returns a final marker carrying the accepted task outcome and scheduled continuation.
     *
     * @param outcome accepted stage result
     * @param nextCommandId continuation command id, or blank for terminal outcomes
     * @param nowEpochMillis Host timestamp
     * @return finalization snapshot
     */
    public RequirementStageFinalization finalized(
            com.wish.rd.engine.requirement.model.RequirementDeliveryResult outcome,
            String nextCommandId,
            long nowEpochMillis
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        return new RequirementStageFinalization(
                commandId,
                attemptNo,
                taskId,
                expectedTaskVersion,
                expectedFencingToken,
                expectedTaskStatus,
                stage,
                State.FINALIZED,
                outcome.status(),
                outcome.resultJson(),
                outcome.errorMessage(),
                outcomePlanJson,
                outcomePlanDigest,
                nextCommandId,
                preparedAtEpochMillis,
                nowEpochMillis
        );
    }

    /**
     * Returns a durable checkpoint containing the Host-serialized non-mutating execution plan.
     *
     * @param status status after the recorded plan
     * @param planJson canonical serialized execution plan
     * @param nowEpochMillis Host timestamp
     * @return recoverable outcome marker
     */
    public RequirementStageFinalization outcomeRecorded(
            RdTaskStatus status,
            String planJson,
            String planDigest,
            long nowEpochMillis
    ) {
        if (!isPrepared()) {
            throw new IllegalStateException("only a prepared marker can record an outcome: " + commandId);
        }
        return new RequirementStageFinalization(
                commandId, attemptNo, taskId, expectedTaskVersion, expectedFencingToken,
                expectedTaskStatus, stage, State.OUTCOME_RECORDED,
                status, "", "", safe(planJson), safe(planDigest), "", preparedAtEpochMillis, 0L);
    }

    /**
     * Returns whether this marker is still recoverable before command finalization.
     *
     * @return true only for prepared markers
     */
    public boolean isPrepared() {
        return state == State.PREPARED;
    }

    /**
     * Returns whether an immutable execution plan is ready for transactional replay.
     *
     * @return true when the marker persists an outcome plan
     */
    public boolean isOutcomeRecorded() {
        return state == State.OUTCOME_RECORDED;
    }

    /**
     * Returns whether this marker still requires Host recovery.
     *
     * @return true for pre-execution or outcome-recorded markers
     */
    public boolean isRecoverable() {
        return state == State.PREPARED || state == State.OUTCOME_RECORDED;
    }

    /**
     * Returns whether this marker belongs to the supplied immutable command attempt.
     *
     * @param command stage command to compare
     * @return true when command, attempt, task, fencing, and stage identities match
     */
    public boolean matches(RequirementStageCommand command) {
        return command != null
                && commandId.equals(command.commandId())
                && attemptNo == command.attemptNo()
                && matchesCommandIdentity(command);
    }

    /**
     * Returns whether this marker describes the command's immutable task-stage identity, allowing
     * a later lease reclaim to have a higher attempt number while it finalizes this marker.
     *
     * @param command current reclaimed command
     * @return true when task, version, fencing, and stage identities match
     */
    public boolean matchesCommandIdentity(RequirementStageCommand command) {
        return command != null
                && commandId.equals(command.commandId())
                && taskId.equals(command.taskId())
                && expectedTaskVersion == command.taskVersion()
                && expectedFencingToken == command.fencingToken()
                && stage.equals(command.stage());
    }

    /**
     * Compares persisted prepared-marker identity while deliberately excluding clock columns.
     *
     * <p>PostgreSQL timestamp precision is not an ownership token. The immutable command/task
     * identity and PREPARED state are the compare-and-set boundary; {@code preparedAt} is audit
     * metadata only and may be rounded by the database.
     *
     * @param other marker read under the transaction lock
     * @return true when both values designate the same still-prepared marker
     */
    public boolean samePreparedIdentity(RequirementStageFinalization other) {
        return other != null
                && state == State.PREPARED
                && other.state == State.PREPARED
                && commandId.equals(other.commandId)
                && attemptNo == other.attemptNo
                && taskId.equals(other.taskId)
                && expectedTaskVersion == other.expectedTaskVersion
                && expectedFencingToken == other.expectedFencingToken
                && expectedTaskStatus == other.expectedTaskStatus
                && stage.equals(other.stage);
    }

    /**
     * Compares the immutable recorded outcome checkpoint before transactional replay closes it.
     *
     * @param other marker read under the finalization lock
     * @return true when both values designate the same recorded plan and marker identity
     */
    public boolean sameOutcomeRecordedIdentity(RequirementStageFinalization other) {
        return other != null
                && state == State.OUTCOME_RECORDED
                && other.state == State.OUTCOME_RECORDED
                && commandId.equals(other.commandId)
                && attemptNo == other.attemptNo
                && taskId.equals(other.taskId)
                && expectedTaskVersion == other.expectedTaskVersion
                && expectedFencingToken == other.expectedFencingToken
                && expectedTaskStatus == other.expectedTaskStatus
                && stage.equals(other.stage)
                && outcomeStatus == other.outcomeStatus
                && !outcomePlanJson.isBlank()
                && !other.outcomePlanJson.isBlank()
                && !outcomePlanDigest.isBlank()
                && outcomePlanDigest.equals(other.outcomePlanDigest);
    }

    private static String require(String value, String name) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
