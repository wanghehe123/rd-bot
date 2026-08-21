package com.wish.rd.engine.requirement.job.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.requirement.job.RequirementDeliveryJobStore;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort;
import com.wish.rd.engine.requirement.job.model.ExternalEffectReceipt;
import com.wish.rd.engine.requirement.job.model.PiQaRemediationIntent;
import com.wish.rd.engine.requirement.job.model.RequirementDeliveryJob;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.job.model.RequirementStageExecutionPlan;
import com.wish.rd.engine.requirement.job.model.RequirementStageFinalization;
import com.wish.rd.engine.requirement.job.model.RequirementTaskMutation;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.engine.requirement.policy.RequirementPolicyRunStore;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRun;
import com.wish.rd.engine.requirement.policy.model.RequirementPolicyRunState;
import com.wish.rd.engine.retry.HostVerifyFailureJson;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.TaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryCheckpointStore;
import com.wish.rd.engine.retry.impl.InMemoryTaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.runtime.RdTaskStatePersistence;
import com.wish.rd.rag.runtime.RdTaskStore;
import com.wish.rd.rag.runtime.impl.InMemoryRdTaskStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTask;
import com.wish.rd.rag.runtime.model.RdTaskEventTrigger;
import com.wish.rd.rag.runtime.model.RdTaskStatusEvent;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory finalization boundary for focused tests and explicit memory mode.
 *
 * <p>Production multi-instance safety is supplied by the PostgreSQL implementation; this class
 * preserves the same lease and recovery contract for a single in-memory runtime.
 */
public final class InMemoryRequirementStageFinalizationPort implements RequirementStageFinalizationPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RequirementStageCommandStore stageCommandStore;
    private final RequirementDeliveryJobStore jobStore;
    private final RdTaskStore taskStore;
    private final RdTaskStatePersistence taskStatePersistence;
    private final SnowflakeIdGenerator eventIdGenerator;
    private final RequirementPublicationStore publicationStore;
    private final RequirementPolicyRunStore policyRunStore;
    private final TaskRetryFailureProvenanceStore failureProvenanceStore;
    private final TaskRetryCheckpointStore checkpointStore;
    private final TaskRetryAttemptBindingStore bindingStore;
    private final AgentStageRunStore stageRunStore;
    private final Map<String, RequirementStageFinalization> finalizations = new LinkedHashMap<>();
    private final Map<String, RequirementStageExecutionPlan> outcomePlans = new LinkedHashMap<>();

    /**
     * Creates the in-memory finalization boundary.
     *
     * @param stageCommandStore stage command lease store
     * @param jobStore umbrella delivery job store
     */
    public InMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore
    ) {
        this(stageCommandStore, jobStore, null, null, SnowflakeIdGenerator.defaultGenerator());
    }

    /**
     * Creates the memory-mode finalization boundary with the same task mutation dependencies as
     * production. The legacy two-argument constructor remains source compatible but fails closed
     * for non-empty plans until the caller supplies this task boundary.
     *
     * @param stageCommandStore stage command lease store
     * @param jobStore umbrella delivery job store
     * @param taskStore task snapshot store
     * @param taskStatePersistence task snapshot/event CAS boundary
     * @param eventIdGenerator task event identifier source
     */
    public InMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore,
            RdTaskStore taskStore,
            RdTaskStatePersistence taskStatePersistence,
            SnowflakeIdGenerator eventIdGenerator
    ) {
        this(stageCommandStore, jobStore, taskStore, taskStatePersistence, eventIdGenerator, null, null);
    }

    /**
     * Creates the memory-mode finalizer with the optional in-memory publication ledger.
     *
     * <p>Publication receipts fail closed when this dependency is absent, preserving the
     * production contract for local and focused memory-mode runs.
     *
     * @param publicationStore publication ledger used to verify and commit PR receipts
     */
    public InMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore,
            RdTaskStore taskStore,
            RdTaskStatePersistence taskStatePersistence,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationStore publicationStore
    ) {
        this(stageCommandStore, jobStore, taskStore, taskStatePersistence, eventIdGenerator, publicationStore, null);
    }

    /**
     * Creates the memory-mode finalizer with policy-run preparation for control continuations.
     *
     * @param policyRunStore authoritative single-runtime policy ledger
     */
    public InMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore,
            RdTaskStore taskStore,
            RdTaskStatePersistence taskStatePersistence,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationStore publicationStore,
            RequirementPolicyRunStore policyRunStore
    ) {
        this(stageCommandStore, jobStore, taskStore, taskStatePersistence, eventIdGenerator,
                publicationStore, policyRunStore, null, null, null, null);
    }

    /**
     * Creates the memory-mode finalizer with atomic technical-exhaustion dependencies.
     *
     * <p>Exhaustion fails closed when the provenance store is absent: silently skipping the
     * provenance write is the production bug this boundary exists to prevent.
     *
     * @param failureProvenanceStore structured failure provenance writer
     * @param checkpointStore exact checkpoint settlement store
     * @param bindingStore checkpoint-scoped attempt bindings
     * @param stageRunStore agent-stage attempts cancelled when still PENDING
     */
    public InMemoryRequirementStageFinalizationPort(
            RequirementStageCommandStore stageCommandStore,
            RequirementDeliveryJobStore jobStore,
            RdTaskStore taskStore,
            RdTaskStatePersistence taskStatePersistence,
            SnowflakeIdGenerator eventIdGenerator,
            RequirementPublicationStore publicationStore,
            RequirementPolicyRunStore policyRunStore,
            TaskRetryFailureProvenanceStore failureProvenanceStore,
            TaskRetryCheckpointStore checkpointStore,
            TaskRetryAttemptBindingStore bindingStore,
            AgentStageRunStore stageRunStore
    ) {
        this.stageCommandStore = Objects.requireNonNull(stageCommandStore, "stageCommandStore must not be null");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore must not be null");
        if ((taskStore == null) != (taskStatePersistence == null)) {
            throw new IllegalArgumentException("taskStore and taskStatePersistence must be supplied together");
        }
        this.taskStore = taskStore;
        this.taskStatePersistence = taskStatePersistence;
        this.eventIdGenerator = Objects.requireNonNull(eventIdGenerator, "eventIdGenerator must not be null");
        this.publicationStore = publicationStore;
        this.policyRunStore = policyRunStore;
        this.failureProvenanceStore = failureProvenanceStore;
        this.checkpointStore = checkpointStore;
        this.bindingStore = bindingStore;
        this.stageRunStore = stageRunStore;
    }

    /**
     * Records a marker only after verifying the current command lease.
     *
     * @param command currently leased stage command
     * @param leaseOwner expected lease owner
     * @param nowEpochMillis Host timestamp
     * @return durable prepared marker
     */
    @Override
    public synchronized RequirementStageFinalization prepare(
            RequirementStageCommand command,
            String leaseOwner,
            RdTaskStatus expectedTaskStatus,
            long nowEpochMillis
    ) {
        requirePositiveFence(command);
        requireOwnedRunning(command, leaseOwner, nowEpochMillis);
        String key = key(command.commandId(), command.attemptNo());
        RequirementStageFinalization existing = finalizations.get(key);
        if (existing != null) {
            if (!existing.matches(command)) {
                throw new IllegalStateException("stage finalization identity mismatch: " + command.commandId());
            }
            return existing;
        }
        RequirementStageFinalization prepared = RequirementStageFinalization.prepared(
                command, expectedTaskStatus, nowEpochMillis);
        finalizations.put(key, prepared);
        return prepared;
    }

    /**
     * Finds the newest unfinished marker for a command.
     *
     * @param commandId stage command id
     * @return latest prepared marker when present
     */
    @Override
    public synchronized Optional<RequirementStageFinalization> findLatestPrepared(String commandId) {
        String expectedCommandId = safe(commandId);
        if (expectedCommandId.isBlank()) {
            return Optional.empty();
        }
        return finalizations.values().stream()
                .filter(RequirementStageFinalization::isRecoverable)
                .filter(marker -> marker.commandId().equals(expectedCommandId))
                .max(Comparator.comparingLong(RequirementStageFinalization::preparedAtEpochMillis)
                        .thenComparingInt(RequirementStageFinalization::attemptNo));
    }

    /** Records the immutable plan so a reclaimed command can resume without repeating external work. */
    @Override
    public synchronized RequirementStageFinalization recordOutcome(
            RequirementStageFinalization marker,
            RequirementStageCommand stageCommand,
            String leaseOwner,
            RequirementStageExecutionPlan plan,
            long nowEpochMillis
    ) {
        Objects.requireNonNull(marker, "marker must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        requireOwnedRunning(stageCommand, leaseOwner, nowEpochMillis);
        if (!marker.isPrepared() || !marker.matchesCommandIdentity(stageCommand)
                || !marker.taskId().equals(plan.taskId())
                || marker.expectedTaskVersion() != plan.expectedVersion()
                || marker.expectedFencingToken() != plan.expectedFencingToken()
                || marker.expectedTaskStatus() != plan.expectedStatus()
                || !plan.externalEffectReceipt().allowsOutcomeRecording(plan.commandDisposition())) {
            throw new IllegalStateException("stage outcome plan conflicts with prepared marker: "
                    + stageCommand.commandId());
        }
        String key = key(marker.commandId(), marker.attemptNo());
        RequirementStageFinalization persisted = finalizations.get(key);
        if (persisted == null || !persisted.equals(marker)) {
            throw new IllegalStateException("stage finalization marker is stale: " + marker.commandId());
        }
        plan = assignRemediationRound(plan);
        RequirementStageFinalization recorded = marker.outcomeRecorded(plan.postStatus(), "{}", "memory", nowEpochMillis);
        finalizations.put(key, recorded);
        outcomePlans.put(key, plan);
        return recorded;
    }

    private RequirementStageExecutionPlan assignRemediationRound(RequirementStageExecutionPlan plan) {
        PiQaRemediationIntent intent = plan.piQaRemediationIntent();
        if (intent == null) {
            return plan;
        }
        java.util.Set<Integer> occupied = new java.util.TreeSet<>();
        for (RequirementStageExecutionPlan recorded : outcomePlans.values()) {
            if (recorded == null || !intent.sourceTaskId().equals(recorded.taskId())) {
                continue;
            }
            PiQaRemediationIntent recordedIntent = recorded.piQaRemediationIntent();
            if (recordedIntent != null && recordedIntent.kind() == intent.kind()) {
                occupied.add(recordedIntent.remediationNo());
            }
        }
        int proposed = intent.remediationNo();
        if (!occupied.contains(proposed)) {
            return plan;
        }
        int maximum = intent.kind().maximumRounds();
        for (int candidate = 1; candidate <= maximum; candidate++) {
            if (!occupied.contains(candidate)) {
                return plan.withRemediationIntent(intent.withAssignedRemediationNo(candidate));
            }
        }
        throw new IllegalStateException(intent.kind() + " remediation limit has been reached for task "
                + intent.sourceTaskId());
    }

    @Override
    public synchronized RequirementStageExecutionPlan decodeOutcomePlan(RequirementStageFinalization marker) {
        if (marker == null || !marker.isOutcomeRecorded()) {
            throw new IllegalStateException("stage marker has no recorded plan");
        }
        RequirementStageExecutionPlan plan = outcomePlans.get(key(marker.commandId(), marker.attemptNo()));
        if (plan == null || !marker.taskId().equals(plan.taskId())
                || marker.expectedTaskVersion() != plan.expectedVersion()
                || marker.expectedFencingToken() != plan.expectedFencingToken()
                || marker.expectedTaskStatus() != plan.expectedStatus()
                || !plan.externalEffectReceipt().allowsOutcomeRecording(plan.commandDisposition())) {
            throw new IllegalStateException("stage outcome plan conflicts with marker: " + marker.commandId());
        }
        return plan;
    }

    /**
     * Finalizes a previously recorded plan in recovery-safe order.
     *
     * @param command finalization input
     * @return durable finalization result
     */
    @Override
    public synchronized FinalizationResult finalize(FinalizationCommand command) {
        if (publicationStore == null) {
            return finalizeLocked(command);
        }
        synchronized (publicationStore) {
            return finalizeLocked(command);
        }
    }

    /**
     * Atomically exhausts one leased stage command under the generic-finalizer lock order.
     *
     * <p>Lock order: command -> task -> checkpoint -> bindings/attempts. Provenance is written
     * immediately after the task CAS using the post-transition version/fence. Missing provenance,
     * checkpoint, or binding dependencies fail closed rather than skipping the provenance write.
     * An already-{@code DEAD_LETTERED} command is resumable: command terminalization is skipped and
     * the remaining dispositions still run. When task failure and provenance are already durable
     * for that command attempt, the call is a no-op that returns the existing state.
     *
     * @param command exhaustion input
     * @return durable exhaustion result
     */
    @Override
    public synchronized ExhaustionResult exhaustCommand(ExhaustionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (failureProvenanceStore == null) {
            throw new UnsupportedOperationException("stage command exhaustion is not configured: "
                    + command.stageCommand().commandId());
        }
        if (taskStore == null || taskStatePersistence == null) {
            throw new UnsupportedOperationException("stage command exhaustion is not configured: "
                    + command.stageCommand().commandId());
        }
        RequirementStageCommand beforeCommand = stageCommandStore.findById(command.stageCommand().commandId())
                .orElseThrow(() -> new IllegalStateException(
                        "stage command not found: " + command.stageCommand().commandId()));
        boolean resumeDeadLettered = beforeCommand.status() == RequirementStageCommand.Status.DEAD_LETTERED;
        if (beforeCommand.status() == RequirementStageCommand.Status.SUCCEEDED
                || beforeCommand.status() == RequirementStageCommand.Status.CANCELLED) {
            throw new IllegalStateException("stage command is already terminal: " + beforeCommand.commandId());
        }
        if (!resumeDeadLettered) {
            requireOwnedRunning(command.stageCommand(), command.leaseOwner(), command.nowEpochMillis());
        }
        RdTask currentTask = taskStore.findTask(beforeCommand.taskId()).orElseThrow(
                () -> new IllegalStateException("stage task snapshot is missing: " + beforeCommand.taskId()));
        if (!(currentTask instanceof RdRequirementTask beforeTask)) {
            throw new IllegalStateException("stage task snapshot is not a requirement task: " + currentTask.taskId());
        }
        if (resumeDeadLettered && isTerminalFailureStatus(beforeTask.status())) {
            return resumeAlreadyDurableExhaustion(beforeCommand, beforeTask, command);
        }
        if (beforeTask.status() != command.expectedTaskStatus()
                || beforeTask.version() != command.expectedTaskVersion()
                || beforeTask.fencingToken() != command.expectedTaskFencingToken()) {
            throw new IllegalStateException("exhaustion task snapshot mismatch: " + beforeTask.taskId());
        }
        String checkpointId = beforeCommand.retryCheckpointId();
        TaskRetryCheckpoint beforeCheckpoint = null;
        if (!checkpointId.isBlank()) {
            if (checkpointStore == null || bindingStore == null) {
                throw new IllegalStateException(
                        "checkpoint-bound exhaustion requires checkpoint and binding stores: " + checkpointId);
            }
            beforeCheckpoint = checkpointStore.find(checkpointId).orElseThrow(
                    () -> new IllegalStateException("retry checkpoint not found: " + checkpointId));
            if (beforeCheckpoint.status().isTerminal()) {
                beforeCheckpoint = null;
            } else if (beforeCheckpoint.status() != TaskRetryCheckpointStatus.DISPATCHED
                    && beforeCheckpoint.status() != TaskRetryCheckpointStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("retry checkpoint is not settleable: " + checkpointId);
            }
        }
        long nextVersion = command.expectedTaskVersion() + 1L;
        long nextFence = command.expectedTaskFencingToken() + 1L;
        if (failureProvenanceStore instanceof InMemoryTaskRetryFailureProvenanceStore memoryProvenance) {
            memoryProvenance.findByTaskVersionFence(beforeTask.taskId(), nextVersion, nextFence)
                    .ifPresent(existing -> {
                        throw new IllegalStateException(
                                "failure provenance identity conflict: " + existing.provenanceId());
                    });
        }
        RequirementStageCommand completedCommand = beforeCommand;
        RdRequirementTask failedTask = null;
        TaskRetryFailureProvenance writtenProvenance = null;
        TaskRetryCheckpoint settledCheckpoint = null;
        List<AgentStageRun> cancelledAttempts = new ArrayList<>();
        try {
            if (!resumeDeadLettered) {
                completedCommand = stageCommandStore.fail(
                        beforeCommand, command.leaseOwner(), command.errorMessage(), command.nowEpochMillis());
            }
            if (completedCommand.status() != RequirementStageCommand.Status.DEAD_LETTERED) {
                throw new IllegalStateException("exhausted command did not become DEAD_LETTERED: "
                        + completedCommand.commandId());
            }
            RdRequirementTask nextTask = beforeTask.withState(
                    command.targetTaskStatus(),
                    null,
                    command.resultJson(),
                    null,
                    command.errorMessage(),
                    command.nowEpochMillis());
            RdTaskStatusEvent event = new RdTaskStatusEvent(
                    eventIdGenerator.nextIdString(),
                    beforeTask.taskId(),
                    command.targetTaskStatus().name(),
                    beforeTask.title(),
                    command.errorMessage(),
                    command.nowEpochMillis(),
                    0L,
                    RdTaskEventTrigger.SYSTEM.name());
            RdTask saved = taskStatePersistence.saveWithEventCas(
                    nextTask, event, command.expectedTaskVersion(), command.expectedTaskFencingToken(),
                    command.expectedTaskStatus());
            if (!(saved instanceof RdRequirementTask requirementTask)) {
                throw new IllegalStateException("exhaustion task CAS returned a non-requirement task: "
                        + saved.taskId());
            }
            failedTask = requirementTask;
            ExhaustionProvenanceDraft draft = command.provenanceDraft();
            writtenProvenance = failureProvenanceStore.save(new TaskRetryFailureProvenance(
                    draft.provenanceId(),
                    failedTask.taskId(),
                    completedCommand.commandId(),
                    completedCommand.attemptNo(),
                    draft.failedStage(),
                    draft.failurePhase(),
                    failedTask.status(),
                    failedTask.version(),
                    failedTask.fencingToken(),
                    draft.failedStageRunId(),
                    draft.failedRetrievalRunId(),
                    draft.failedAiReviewRunId(),
                    draft.sourcePolicyRunId(),
                    draft.sourcePlanDigest(),
                    draft.publicationOperationId(),
                    draft.failureKind(),
                    command.nowEpochMillis()));
            if (beforeCheckpoint != null) {
                TaskRetryCheckpointStatus target = resolveExhaustionCheckpointTarget(
                        beforeCheckpoint.status(), command.targetCheckpointStatus());
                settledCheckpoint = checkpointStore.transition(
                        beforeCheckpoint.checkpointId(),
                        beforeCheckpoint.status(),
                        target,
                        command.errorMessage(),
                        command.nowEpochMillis());
                cancelledAttempts.addAll(cancelPendingBoundAttempts(beforeCheckpoint.checkpointId(),
                        command.nowEpochMillis()));
            }
            return new ExhaustionResult(
                    completedCommand, failedTask, writtenProvenance, settledCheckpoint);
        } catch (RuntimeException failure) {
            if (!resumeDeadLettered) {
                restoreExhaustionSnapshots(
                        beforeCommand, beforeTask, beforeCheckpoint, writtenProvenance, cancelledAttempts);
            } else {
                restoreExhaustionSnapshots(
                        completedCommand, beforeTask, beforeCheckpoint, writtenProvenance, cancelledAttempts);
            }
            throw failure;
        }
    }

    private ExhaustionResult resumeAlreadyDurableExhaustion(
            RequirementStageCommand completedCommand,
            RdRequirementTask failedTask,
            ExhaustionCommand command
    ) {
        TaskRetryFailureProvenance existing = null;
        if (failureProvenanceStore instanceof InMemoryTaskRetryFailureProvenanceStore memoryProvenance) {
            existing = memoryProvenance.findByCommandAttempt(
                    completedCommand.commandId(), completedCommand.attemptNo()).orElse(null);
        }
        if (existing == null) {
            existing = failureProvenanceStore.findExact(
                    failedTask.taskId(), failedTask.status(), failedTask.version(), failedTask.fencingToken())
                    .orElse(null);
        }
        if (existing == null) {
            throw new IllegalStateException(
                    "terminal task lacks durable failure provenance for exhausted command: "
                            + completedCommand.commandId());
        }
        TaskRetryCheckpoint settledCheckpoint = null;
        String checkpointId = completedCommand.retryCheckpointId();
        if (!checkpointId.isBlank() && checkpointStore != null) {
            settledCheckpoint = checkpointStore.find(checkpointId).orElse(null);
        }
        return new ExhaustionResult(completedCommand, failedTask, existing, settledCheckpoint);
    }

    private static boolean isTerminalFailureStatus(RdTaskStatus status) {
        return status == RdTaskStatus.FAILED_RETRYABLE
                || status == RdTaskStatus.FAILED_NEEDS_HUMAN
                || status == RdTaskStatus.DEAD_LETTERED
                || status == RdTaskStatus.REJECTED
                || status == RdTaskStatus.CANCELLED;
    }

    private static TaskRetryCheckpointStatus resolveExhaustionCheckpointTarget(
            TaskRetryCheckpointStatus currentStatus,
            TaskRetryCheckpointStatus requestedTarget
    ) {
        if (currentStatus == TaskRetryCheckpointStatus.WAITING_APPROVAL
                && requestedTarget == TaskRetryCheckpointStatus.FAILED_RETRYABLE) {
            return TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN;
        }
        return requestedTarget;
    }

    private List<AgentStageRun> cancelPendingBoundAttempts(String checkpointId, long nowEpochMillis) {
        if (stageRunStore == null) {
            throw new IllegalStateException(
                    "checkpoint-bound exhaustion requires an agent-stage store to cancel PENDING attempts: "
                            + checkpointId);
        }
        List<AgentStageRun> cancelled = new ArrayList<>();
        for (TaskRetryAttemptBinding binding : bindingStore.listByCheckpoint(checkpointId)) {
            if (binding.kind() != com.wish.rd.engine.retry.model.TaskRetryAttemptKind.AGENT_STAGE) {
                continue;
            }
            AgentStageRun attempt = stageRunStore.findById(binding.attemptId()).orElse(null);
            if (attempt == null || attempt.status() != AgentStageStatus.PENDING) {
                continue;
            }
            AgentStageRun updated = stageRunStore.transition(
                    attempt.stageRunId(),
                    AgentStageStatus.CANCELLED,
                    "RETRY_CHECKPOINT_EXHAUSTED",
                    "unused pending attempt cancelled with exhausted checkpoint: " + checkpointId,
                    nowEpochMillis);
            cancelled.add(attempt);
            if (updated.status() != AgentStageStatus.CANCELLED) {
                throw new IllegalStateException("pending attempt was not cancelled: " + attempt.stageRunId());
            }
        }
        return cancelled;
    }

    private void restoreExhaustionSnapshots(
            RequirementStageCommand beforeCommand,
            RdRequirementTask beforeTask,
            TaskRetryCheckpoint beforeCheckpoint,
            TaskRetryFailureProvenance writtenProvenance,
            List<AgentStageRun> cancelledAttempts
    ) {
        for (AgentStageRun attempt : cancelledAttempts) {
            if (stageRunStore != null) {
                stageRunStore.save(attempt);
            }
        }
        if (writtenProvenance != null
                && failureProvenanceStore instanceof InMemoryTaskRetryFailureProvenanceStore memoryProvenance) {
            memoryProvenance.removeForRollback(writtenProvenance);
        }
        if (beforeCheckpoint != null
                && checkpointStore instanceof InMemoryTaskRetryCheckpointStore memoryCheckpoints) {
            memoryCheckpoints.restoreSnapshot(beforeCheckpoint);
        }
        if (stageCommandStore instanceof InMemoryRequirementStageCommandStore memoryCommands) {
            memoryCommands.restoreSnapshot(beforeCommand);
        }
        restoreTaskSnapshot(beforeTask);
    }

    private void restoreTaskSnapshot(RdRequirementTask beforeTask) {
        if (!(taskStore instanceof InMemoryRdTaskStore memoryTaskStore)) {
            throw new IllegalStateException(
                    "memory exhaustion rollback requires InMemoryRdTaskStore: " + beforeTask.taskId());
        }
        try {
            Method restore = InMemoryRdTaskStore.class.getDeclaredMethod(
                    "restoreTaskSnapshot", String.class, RdTask.class);
            restore.setAccessible(true);
            restore.invoke(memoryTaskStore, beforeTask.taskId(), beforeTask);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "unable to restore task snapshot after exhaustion rollback: " + beforeTask.taskId(),
                    exception);
        }
    }

    private FinalizationResult finalizeLocked(FinalizationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        RequirementStageFinalization marker = command.marker();
        if (!marker.isOutcomeRecorded()) {
            throw new IllegalStateException("stage finalization requires a recorded outcome: " + marker.commandId());
        }
        RequirementStageFinalization persisted = finalizations.get(key(marker.commandId(), marker.attemptNo()));
        RequirementStageExecutionPlan recordedPlan = outcomePlans.get(key(marker.commandId(), marker.attemptNo()));
        if (persisted == null || !persisted.sameOutcomeRecordedIdentity(marker)
                || recordedPlan == null || !recordedPlan.equals(command.plan())) {
            throw new IllegalStateException("stage finalization marker is stale: " + marker.commandId());
        }
        requireOwnedRunning(command.stageCommand(), command.leaseOwner(), command.nowEpochMillis());
        InMemoryRequirementStageCommandStore memoryCommandStore = null;
        if (command.nextCommand() != null) {
            if (!(stageCommandStore instanceof InMemoryRequirementStageCommandStore store)) {
                throw new IllegalStateException("memory finalizer continuation requires in-memory command store");
            }
            store.preflightCompletionAndEnqueue(
                    command.stageCommand(), command.leaseOwner(), command.nowEpochMillis(), command.nextCommand());
            memoryCommandStore = store;
        }
        preparePolicyEvaluateLedger(command.plan(), command.nextCommand(), command.nowEpochMillis());
        ExternalEffectReceipt effectReceipt = command.plan().externalEffectReceipt();
        RequirementPublication publicationReceipt = effectReceipt.isFinalizable()
                ? verifyPublicationReceipt(command.plan(), marker.taskId()) : null;
        boolean deferRetryableMutation = defersRetryableMutation(command);
        applyTaskMutations(command);
        commitPublicationReceipt(publicationReceipt, command.nowEpochMillis());
        applyJobDisposition(command.umbrellaJob(), command.jobDisposition(), command);
        RequirementStageCommand completed;
        RequirementStageCommand next = null;
        if (command.plan().commandDisposition()
                == com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE) {
            completed = stageCommandStore.fail(command.stageCommand(), command.leaseOwner(),
                    command.outcome().errorMessage(), command.nowEpochMillis());
        } else if (memoryCommandStore != null) {
            InMemoryRequirementStageCommandStore.CompletionAndContinuation completion =
                    memoryCommandStore.completeAndEnqueue(command.stageCommand(), command.leaseOwner(),
                            command.nowEpochMillis(), command.nextCommand());
            completed = completion.completed();
            next = completion.continuation();
        } else {
            completed = stageCommandStore.complete(command.stageCommand(), command.leaseOwner(), command.nowEpochMillis());
        }
        recordPublicationFailureProvenance(command, marker, deferRetryableMutation);
        recordHostVerifyFailureProvenance(command, marker, deferRetryableMutation);
        if (deferRetryableMutation) {
            return new FinalizationResult(marker, completed, next);
        }
        RequirementStageFinalization finalized = marker.finalized(
                command.outcome(), next == null ? "" : next.commandId(), command.nowEpochMillis());
        finalizations.put(key(marker.commandId(), marker.attemptNo()), finalized);
        return new FinalizationResult(finalized, completed, next);
    }

    private void recordHostVerifyFailureProvenance(
            FinalizationCommand command,
            RequirementStageFinalization finalized,
            boolean deferRetryableMutation
    ) {
        if (deferRetryableMutation) {
            return;
        }
        RequirementStageCommand stageCommand = command.stageCommand();
        if (!stageCommand.stage().startsWith("ROLE_EXECUTION:")) {
            return;
        }
        if (finalized.outcomeStatus() != RdTaskStatus.FAILED_RETRYABLE
                && finalized.outcomeStatus() != RdTaskStatus.FAILED_NEEDS_HUMAN) {
            return;
        }
        String resultJson = lastMutationResultJson(command.plan());
        if (!HostVerifyFailureJson.isStructuredHostVerify(resultJson)) {
            return;
        }
        if (failureProvenanceStore == null) {
            throw new IllegalStateException("terminal host-verify failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        if (policyRunStore == null) {
            throw new IllegalStateException("terminal host-verify failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        if (stageCommand.policyRunId().isBlank()) {
            throw new IllegalStateException("terminal host-verify failure has no policy generation: "
                    + stageCommand.commandId());
        }
        RequirementPolicyRun policy = policyRunStore.findById(stageCommand.policyRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "terminal host-verify failure has no matching policy generation: "
                                + stageCommand.commandId()));
        if (!stageCommand.taskId().equals(policy.taskId()) || policy.planDigest().isBlank()) {
            throw new IllegalStateException("terminal host-verify failure has no matching policy generation: "
                    + stageCommand.commandId());
        }
        String verificationRunId = HostVerifyFailureJson.verificationRunId(resultJson);
        failureProvenanceStore.save(new TaskRetryFailureProvenance(
                Long.toString(eventIdGenerator.nextId()),
                stageCommand.taskId(),
                stageCommand.commandId(),
                stageCommand.attemptNo(),
                HostVerifyFailureJson.STAGE,
                TaskFailurePhase.HOST_VERIFY,
                finalized.outcomeStatus(),
                command.plan().postVersion(),
                command.plan().postFencingToken(),
                "", "", "",
                policy.id(),
                policy.planDigest(),
                "",
                firstNonBlank(HostVerifyFailureJson.failurePhase(resultJson), HostVerifyFailureJson.STAGE),
                command.nowEpochMillis(),
                verificationRunId));
    }

    private static String lastMutationResultJson(RequirementStageExecutionPlan plan) {
        if (plan == null || plan.mutations() == null) {
            return "";
        }
        return HostVerifyFailureJson.lastNonBlank(
                plan.mutations().stream().map(RequirementTaskMutation::executionResultJson).toList());
    }

    private static String firstNonBlank(String first, String second) {
        String normalized = first == null ? "" : first.strip();
        return normalized.isBlank() ? (second == null ? "" : second.strip()) : normalized;
    }

    private void recordPublicationFailureProvenance(
            FinalizationCommand command,
            RequirementStageFinalization finalized,
            boolean deferRetryableMutation
    ) {
        RequirementStageCommand stageCommand = command.stageCommand();
        String stage = stageCommand.stage();
        if (!"PUBLICATION".equals(stage) && !stage.startsWith("PUBLICATION:")) {
            return;
        }
        if (failureProvenanceStore == null) {
            throw new IllegalStateException("terminal publication failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        if (finalized.outcomeStatus() != RdTaskStatus.FAILED_RETRYABLE
                && finalized.outcomeStatus() != RdTaskStatus.FAILED_NEEDS_HUMAN) {
            return;
        }
        if (publicationStore == null || policyRunStore == null) {
            throw new IllegalStateException("terminal publication failure provenance persistence is unavailable: "
                    + stageCommand.commandId());
        }
        ExternalEffectReceipt receipt = command.plan().externalEffectReceipt();
        if (receipt.kind() != ExternalEffectReceipt.Kind.PUBLICATION || receipt.operationId().isBlank()) {
            throw new IllegalStateException("terminal publication failure requires a publication receipt: "
                    + stageCommand.commandId());
        }
        if (stageCommand.policyRunId().isBlank()) {
            throw new IllegalStateException("terminal publication failure has no policy generation: "
                    + stageCommand.commandId());
        }
        RequirementPublication publication = publicationStore.findByOperationId(receipt.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "terminal publication failure has no matching ledger row: " + stageCommand.commandId()));
        if (!stageCommand.taskId().equals(publication.taskId())) {
            throw new IllegalStateException("terminal publication failure ledger task mismatch: "
                    + stageCommand.commandId());
        }
        RequirementPolicyRun policy = policyRunStore.findById(stageCommand.policyRunId())
                .orElseThrow(() -> new IllegalStateException(
                        "terminal publication failure has no matching policy generation: "
                                + stageCommand.commandId()));
        if (!stageCommand.taskId().equals(policy.taskId()) || policy.planDigest().isBlank()) {
            throw new IllegalStateException("terminal publication failure has no matching policy generation: "
                    + stageCommand.commandId());
        }
        String failureKind = publication.status().name();
        if (failureKind.isBlank()) {
            failureKind = receipt.durableState();
        }
        failureProvenanceStore.save(new TaskRetryFailureProvenance(
                Long.toString(eventIdGenerator.nextId()),
                stageCommand.taskId(),
                stageCommand.commandId(),
                stageCommand.attemptNo(),
                "PUBLICATION:" + receipt.operationId(),
                com.wish.rd.engine.retry.model.TaskFailurePhase.PR_PUBLICATION,
                finalized.outcomeStatus(),
                command.plan().postVersion(),
                command.plan().postFencingToken(),
                "", "", "",
                policy.id(),
                policy.planDigest(),
                receipt.operationId(),
                failureKind,
                command.nowEpochMillis()));
    }

    private void preparePolicyEvaluateLedger(
            RequirementStageExecutionPlan plan, RequirementStageCommand continuation, long nowEpochMillis
    ) {
        if (continuation == null || !"POLICY_EVALUATE".equals(continuation.stage())) {
            return;
        }
        if (policyRunStore == null) {
            throw new IllegalStateException("policy-evaluate continuation requires a policy-run store");
        }
        if (!continuation.commandId().equals(continuation.policyRunId())
                || !plan.taskId().equals(continuation.taskId())
                || continuation.taskVersion() != plan.postVersion()
                || continuation.fencingToken() != plan.postFencingToken()
                || continuation.status() != RequirementStageCommand.Status.PENDING
                || !continuation.leaseOwner().isBlank()
                || continuation.leaseUntilEpochMillis() != 0L
                || !"REQUIREMENT_DELIVERY".equals(continuation.role())
                || plan.postStatus() != RdTaskStatus.PLAN_GENERATED
                || plan.mutations().isEmpty()) {
            throw new IllegalStateException("policy-evaluate continuation identity is invalid: "
                    + continuation.commandId());
        }
        String frozenPlanJson = RequirementPolicyRun.canonicalizeJson(
                plan.mutations().getLast().executionResultJson());
        RequirementPolicyRun requested = new RequirementPolicyRun(
                continuation.policyRunId(), continuation.taskId(), continuation.taskVersion(),
                continuation.fencingToken(), frozenPlanJson, RequirementPolicyRun.canonicalJsonDigest(frozenPlanJson),
                "", "", "", RequirementPolicyRunState.PLAN_READY,
                continuation.taskVersion(), continuation.fencingToken(), null, null,
                "", "", "", 0L, "", "", 0L, 0L, nowEpochMillis, nowEpochMillis);
        RequirementPolicyRun existing = policyRunStore.findById(requested.id()).orElse(null);
        RequirementPolicyRun effective = existing == null ? policyRunStore.createOrGet(requested) : existing;
        if (!samePreparedLedgerGeneration(effective, requested)) {
            throw new IllegalStateException("policy-evaluate ledger conflicts: " + continuation.commandId());
        }
    }

    private static boolean samePreparedLedgerGeneration(
            RequirementPolicyRun actual, RequirementPolicyRun expected
    ) {
        return actual.state() == RequirementPolicyRunState.PLAN_READY
                && actual.id().equals(expected.id())
                && actual.taskId().equals(expected.taskId())
                && actual.sourceTaskVersion() == expected.sourceTaskVersion()
                && actual.sourceFencingToken() == expected.sourceFencingToken()
                && actual.boundTaskVersion() == expected.boundTaskVersion()
                && actual.boundFencingToken() == expected.boundFencingToken()
                && actual.planDigest().equals(expected.planDigest())
                && RequirementPolicyRun.canonicalizeJson(actual.planJson()).equals(expected.planJson())
                && actual.policyJson().isBlank()
                && actual.policyDigest().isBlank()
                && actual.policyAction().isBlank()
                && actual.approvalRequestId().isBlank()
                && actual.approvalResumeCommandId().isBlank()
                && actual.consumedByCommandId().isBlank();
    }

    private RequirementPublication verifyPublicationReceipt(RequirementStageExecutionPlan plan, String taskId) {
        ExternalEffectReceipt receipt = plan.externalEffectReceipt();
        if (receipt.kind() != ExternalEffectReceipt.Kind.PUBLICATION || !receipt.isFinalizable()) {
            return null;
        }
        if (publicationStore == null) {
            throw new IllegalStateException("publication receipt finalization is unavailable");
        }
        RequirementPublication publication = publicationStore.findByOperationId(receipt.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "publication receipt does not match the durable ledger: " + receipt.operationId()));
        boolean receiptConfirmsPr = "PR_CONFIRMED".equalsIgnoreCase(receipt.durableState());
        boolean matchingCurrentState = publication.status() == RequirementPublicationStatus.PR_CONFIRMED
                || (receiptConfirmsPr && publication.status() == RequirementPublicationStatus.COMMITTED)
                || ("COMMITTED".equalsIgnoreCase(receipt.durableState())
                && publication.status() == RequirementPublicationStatus.COMMITTED);
        if (!receipt.operationId().equals(publication.operationId())
                || !taskId.equals(publication.taskId())
                || !matchingCurrentState) {
            throw new IllegalStateException("publication receipt does not match the durable ledger: "
                    + receipt.operationId());
        }
        requirePublicationReceiptIdentity(receipt, taskId, plan, publication);
        return publication;
    }

    private void commitPublicationReceipt(RequirementPublication publication, long nowEpochMillis) {
        if (publication == null || publication.status() == RequirementPublicationStatus.COMMITTED) {
            return;
        }
        RequirementPublication committed = publicationStore.save(publication.withCommitted(nowEpochMillis));
        if (committed.status() != RequirementPublicationStatus.COMMITTED
                || !publication.operationId().equals(committed.operationId())
                || !publication.taskId().equals(committed.taskId())) {
            throw new IllegalStateException("publication receipt compare-and-set failed: "
                    + publication.operationId());
        }
    }

    private static void requirePublicationReceiptIdentity(
            ExternalEffectReceipt receipt,
            String taskId,
            RequirementStageExecutionPlan plan,
            RequirementPublication publication
    ) {
        try {
            var payload = OBJECT_MAPPER.readTree(receipt.receiptJson());
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("publication receipt payload must be an object: "
                        + receipt.operationId());
            }
            if (payload.hasNonNull("operationId")
                    && !receipt.operationId().equals(payload.path("operationId").asText())) {
                throw new IllegalStateException("publication receipt payload operation does not match: "
                        + receipt.operationId());
            }
            if (payload.hasNonNull("taskId") && !taskId.equals(payload.path("taskId").asText())) {
                throw new IllegalStateException("publication receipt payload task does not match: "
                        + receipt.operationId());
            }
            String receiptUrl = payload.path("pullRequestUrl").asText("");
            int receiptNumber = payload.path("pullRequestNumber").asInt(-1);
            String plannedUrl = plan.mutations().isEmpty() ? "" : plan.mutations().getLast().pullRequestUrl();
            if (receiptUrl.isBlank() || receiptNumber < 0
                    || !receiptUrl.equals(publication.pullRequestUrl())
                    || receiptNumber != publication.pullRequestNumber()
                    || !receiptUrl.equals(plannedUrl)) {
                throw new IllegalStateException("publication receipt pull request does not match: "
                        + receipt.operationId());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("publication receipt payload cannot be decoded: "
                    + receipt.operationId(), exception);
        }
    }

    private void applyTaskMutations(FinalizationCommand command) {
        if (command.taskMutationDisposition() == TaskMutationDisposition.ALREADY_APPLIED
                || command.plan().mutations().isEmpty()
                || defersRetryableMutation(command)) {
            return;
        }
        if (taskStore == null || taskStatePersistence == null) {
            throw new IllegalStateException("memory stage finalizer requires task state persistence for mutations");
        }
        RdTask current = taskStore.findTask(command.plan().taskId()).orElseThrow(
                () -> new IllegalStateException("stage task snapshot is missing: " + command.plan().taskId()));
        long expectedVersion = command.plan().expectedVersion();
        long expectedFencingToken = command.plan().expectedFencingToken();
        for (RequirementTaskMutation mutation : command.plan().mutations()) {
            if (!(current instanceof RdRequirementTask task)) {
                throw new IllegalStateException("stage task snapshot is not a requirement task: " + current.taskId());
            }
            RdRequirementTask next = task.withState(
                    mutation.toStatus(),
                    mutation.promptSnapshotReplacementOrNull(),
                    mutation.executionResultReplacementOrNull(),
                    mutation.pullRequestUrlReplacementOrNull(),
                    mutation.errorMessage(),
                    command.nowEpochMillis());
            RdTaskStatusEvent event = new RdTaskStatusEvent(
                    eventIdGenerator.nextIdString(),
                    task.taskId(),
                    mutation.toStatus().name(),
                    task.title(),
                    mutation.eventMessage(),
                    command.nowEpochMillis(),
                    0L,
                    RdTaskEventTrigger.SYSTEM.name());
            current = mutation.kind() == RequirementTaskMutation.Kind.STATUS_TRANSITION
                    ? taskStatePersistence.saveWithEventCas(
                            next, event, expectedVersion, expectedFencingToken, mutation.fromStatus())
                    : taskStatePersistence.saveActionWithEventCas(
                            next, event, expectedVersion, expectedFencingToken, mutation.fromStatus());
            expectedVersion++;
            expectedFencingToken++;
        }
    }

    private static boolean defersRetryableMutation(FinalizationCommand command) {
        return command.plan().commandDisposition()
                == com.wish.rd.engine.requirement.job.model.CommandDisposition.RETRYABLE_TECHNICAL_FAILURE
                && command.stageCommand().attemptNo() < command.stageCommand().maxAttempts();
    }

    private void applyJobDisposition(
            RequirementDeliveryJob job,
            JobDisposition disposition,
            FinalizationCommand command
    ) {
        if (job == null || disposition == JobDisposition.NONE) {
            return;
        }
        if (jobAlreadyHasDisposition(job, disposition)) {
            return;
        }
        switch (disposition) {
            case COMPLETE -> jobStore.complete(job.jobId(), command.leaseOwner(), command.nowEpochMillis());
            case FAIL_RETRYABLE -> jobStore.fail(
                    job.jobId(), command.leaseOwner(), command.outcome().errorMessage(), command.nowEpochMillis());
            case CANCEL -> jobStore.cancelByTask(
                    job.taskId(), command.outcome().errorMessage(), command.nowEpochMillis());
            case NONE -> {
                // The guarded return above keeps this branch exhaustive.
            }
        }
    }

    private boolean jobAlreadyHasDisposition(RequirementDeliveryJob job, JobDisposition disposition) {
        return jobStore.findByTask(job.taskId())
                .map(RequirementDeliveryJob::status)
                .map(status -> switch (disposition) {
                    case COMPLETE -> status == com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus.SUCCEEDED;
                    case FAIL_RETRYABLE -> status
                            == com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus.FAILED_RETRYABLE
                            || status == com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus.DEAD_LETTERED;
                    case CANCEL -> status == com.wish.rd.engine.requirement.job.model.RequirementDeliveryJobStatus.CANCELLED;
                    case NONE -> true;
                })
                .orElse(false);
    }

    private void requireOwnedRunning(RequirementStageCommand command, String leaseOwner, long nowEpochMillis) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        RequirementStageCommand current = stageCommandStore.findById(command.commandId()).orElseThrow(
                () -> new IllegalStateException("stage command not found: " + command.commandId()));
        if (current.status() != RequirementStageCommand.Status.RUNNING
                || !current.leaseOwner().equals(safe(leaseOwner))
                || current.attemptNo() != command.attemptNo()
                || current.leaseUntilEpochMillis() <= nowEpochMillis) {
            throw new IllegalStateException("stage command lease is not owned: " + command.commandId());
        }
    }

    private static void requirePositiveFence(RequirementStageCommand command) {
        if (command == null || command.fencingToken() <= 0L) {
            throw new IllegalArgumentException("stage finalization requires a positive command fencingToken");
        }
    }

    private static String key(String commandId, int attemptNo) {
        return safe(commandId) + ":" + Math.max(0, attemptNo);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
