package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageTransitions;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Creates idempotent resume checkpoints, new role attempts, and durable retry dispatches. */
@Service
public final class TaskRetryEngine {

    private static final Comparator<AgentStageRun> STAGE_RECENCY = Comparator
            .comparingInt(AgentStageRun::attemptNo)
            .thenComparingLong(AgentStageRun::createTimeEpochMillis)
            .thenComparing(AgentStageRun::stageRunId);

    private final TaskRetryTaskPort taskPort;
    private final AgentStageRunStore stageRunStore;
    private final RetrievalRunStore retrievalRunStore;
    private final AiReviewRunStore aiReviewRunStore;
    private final TaskRetryCheckpointStore checkpointStore;
    private final TaskMaterialStore materialStore;
    private final TaskFailureRecoveryService failureRecoveryService;
    private final TaskRetryPointResolver resolver;
    private final TaskRetryDispatcherPort dispatcher;
    private final Supplier<String> idSupplier;
    private final LongSupplier clock;

    @Autowired
    public TaskRetryEngine(
            TaskRetryTaskPort taskPort,
            AgentStageRunStore stageRunStore,
            RetrievalRunStore retrievalRunStore,
            AiReviewRunStore aiReviewRunStore,
            TaskRetryCheckpointStore checkpointStore,
            TaskMaterialStore materialStore,
            TaskFailureRecoveryService failureRecoveryService,
            TaskRetryDispatcherPort dispatcher,
            SnowflakeIdGenerator idGenerator
    ) {
        this(
                taskPort,
                stageRunStore,
                retrievalRunStore,
                aiReviewRunStore,
                checkpointStore,
                materialStore,
                failureRecoveryService,
                new TaskRetryPointResolver(),
                dispatcher,
                (idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator)::nextIdString,
                System::currentTimeMillis
        );
    }

    /**
     * Creates the retry orchestrator.
     *
     * @param taskPort task state port
     * @param stageRunStore role attempt store
     * @param retrievalRunStore retrieval attempt store
     * @param aiReviewRunStore AI review attempt store
     * @param checkpointStore retry checkpoint store
     * @param resolver structured retry-point resolver
     * @param dispatcher durable delivery dispatcher
     * @param idSupplier ID supplier
     * @param clock epoch-millis clock
     */
    public TaskRetryEngine(
            TaskRetryTaskPort taskPort,
            AgentStageRunStore stageRunStore,
            RetrievalRunStore retrievalRunStore,
            AiReviewRunStore aiReviewRunStore,
            TaskRetryCheckpointStore checkpointStore,
            TaskRetryPointResolver resolver,
            TaskRetryDispatcherPort dispatcher,
            Supplier<String> idSupplier,
            LongSupplier clock
    ) {
        this(
                taskPort,
                stageRunStore,
                retrievalRunStore,
                aiReviewRunStore,
                checkpointStore,
                new InMemoryTaskMaterialStore(),
                new TaskFailureRecoveryService(
                        taskPort,
                        stageRunStore,
                        AgentStageArtifactStore.noop(),
                        retrievalRunStore,
                        aiReviewRunStore,
                        checkpointStore,
                        resolver,
                        new TaskFailureDiagnosticParser()
                ),
                resolver,
                dispatcher,
                idSupplier,
                clock
        );
    }

    /**
     * Creates the retry orchestrator with its recovery-evidence dependencies.
     *
     * @param taskPort task state port
     * @param stageRunStore role attempt store
     * @param retrievalRunStore retrieval attempt store
     * @param aiReviewRunStore AI review attempt store
     * @param checkpointStore retry checkpoint store
     * @param materialStore task material store
     * @param failureRecoveryService read-only recovery service
     * @param resolver structured retry-point resolver
     * @param dispatcher durable delivery dispatcher
     * @param idSupplier ID supplier
     * @param clock epoch-millis clock
     */
    public TaskRetryEngine(
            TaskRetryTaskPort taskPort,
            AgentStageRunStore stageRunStore,
            RetrievalRunStore retrievalRunStore,
            AiReviewRunStore aiReviewRunStore,
            TaskRetryCheckpointStore checkpointStore,
            TaskMaterialStore materialStore,
            TaskFailureRecoveryService failureRecoveryService,
            TaskRetryPointResolver resolver,
            TaskRetryDispatcherPort dispatcher,
            Supplier<String> idSupplier,
            LongSupplier clock
    ) {
        this.taskPort = Objects.requireNonNull(taskPort, "taskPort must not be null");
        this.stageRunStore = Objects.requireNonNull(stageRunStore, "stageRunStore must not be null");
        this.retrievalRunStore = Objects.requireNonNull(retrievalRunStore, "retrievalRunStore must not be null");
        this.aiReviewRunStore = Objects.requireNonNull(aiReviewRunStore, "aiReviewRunStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.materialStore = Objects.requireNonNull(materialStore, "materialStore must not be null");
        this.failureRecoveryService = Objects.requireNonNull(
                failureRecoveryService, "failureRecoveryService must not be null");
        this.resolver = resolver == null ? new TaskRetryPointResolver() : resolver;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Returns the current retry point without changing task or attempt state. */
    public TaskRetryPoint preview(String taskId) {
        RdRequirementTask task = taskPort.getRequirementTask(taskId);
        return resolver.resolve(task, stageRunStore.listByTask(taskId),
                retrievalRunStore.listByTask(taskId), aiReviewRunStore.listByTask(taskId));
    }

    /**
     * Creates or reuses one retry checkpoint and dispatches execution from that point.
     *
     * @param taskId requirement task ID
     * @param trigger USER or SYSTEM
     * @return dispatched or previously active retry checkpoint
     */
    public TaskRetryCheckpoint retry(String taskId, String trigger) {
        return retry(taskId, trigger, TaskRetryCommand.empty());
    }

    /**
     * Creates or reuses a retry checkpoint after validating the operator's recovery input.
     *
     * @param taskId requirement task ID
     * @param trigger USER or SYSTEM
     * @param command expected failed source version/run IDs and selected recovery evidence
     * @return dispatched or previously active retry checkpoint
     */
    public TaskRetryCheckpoint retry(String taskId, String trigger, TaskRetryCommand command) {
        TaskRetryCommand safeCommand = command == null ? TaskRetryCommand.empty() : command;
        TaskRetryCheckpoint active = checkpointStore.findActiveByTask(taskId).orElse(null);
        if (active != null) {
            validateExistingCheckpoint(active, safeCommand);
            return active;
        }
        TaskFailureRecoverySnapshot snapshot = failureRecoveryService.snapshot(taskId);
        validateCommand(snapshot, safeCommand);
        TaskRetryPoint point = snapshot.retryPoint();
        int attemptNo = checkpointStore.listByTask(taskId).stream()
                .mapToInt(TaskRetryCheckpoint::attemptNo).max().orElse(0) + 1;
        String idempotencyKey = taskId + ":" + point.sourceTaskVersion() + ":"
                + point.failurePhase() + ":" + (point.retryFromRole() == null ? "" : point.retryFromRole().name());
        TaskRetryCheckpoint proposed = TaskRetryCheckpoint.created(
                nextId(), point, attemptNo, idempotencyKey, snapshot.sourceTaskStatus(), safeCommand, now());
        TaskRetryCheckpointStore.CreateResult created = checkpointStore.createOrGet(proposed);
        if (!created.created()) {
            validateExistingCheckpoint(created.checkpoint(), safeCommand);
            return created.checkpoint();
        }

        TaskRetryCheckpoint checkpoint = created.checkpoint();
        List<AgentStageRun> preparedStageRuns = new ArrayList<>();
        try {
            taskPort.markRecovering(taskId, "从失败阶段重试：" + point.failurePhase());
            createAttempts(point, preparedStageRuns);
            checkpoint = checkpointStore.transition(checkpoint.checkpointId(), TaskRetryCheckpointStatus.CREATED,
                    TaskRetryCheckpointStatus.DISPATCHED, "", now());
            dispatcher.dispatch(taskId, point);
            return checkpoint;
        } catch (RuntimeException exception) {
            compensatePreparedStages(preparedStageRuns, exception);
            failCheckpoint(checkpoint, exception);
            compensateTask(taskId, exception);
            throw exception;
        }
    }

    private void validateCommand(TaskFailureRecoverySnapshot snapshot, TaskRetryCommand command) {
        TaskRetryPoint retryPoint = snapshot.retryPoint();
        String expectedStageRunId = command.expectedFailedStageRunId();
        String actualStageRunId = retryPoint.failedStageRunId();
        if (!expectedStageRunId.isBlank() && !expectedStageRunId.equals(actualStageRunId)) {
            throw new IllegalStateException("retry failure stage changed: expected " + expectedStageRunId
                    + " but was " + actualStageRunId);
        }
        String expectedRetrievalRunId = command.expectedFailedRetrievalRunId();
        String actualRetrievalRunId = retryPoint.failedRetrievalRunId();
        if (!expectedRetrievalRunId.isBlank() && !expectedRetrievalRunId.equals(actualRetrievalRunId)) {
            throw new IllegalStateException("retry failure retrieval changed: expected " + expectedRetrievalRunId
                    + " but was " + actualRetrievalRunId);
        }
        String expectedAiReviewRunId = command.expectedFailedAiReviewRunId();
        String actualAiReviewRunId = retryPoint.failedAiReviewRunId();
        if (!expectedAiReviewRunId.isBlank() && !expectedAiReviewRunId.equals(actualAiReviewRunId)) {
            throw new IllegalStateException("retry failure AI review changed: expected " + expectedAiReviewRunId
                    + " but was " + actualAiReviewRunId);
        }
        long expectedSourceTaskVersion = command.expectedSourceTaskVersion();
        if (expectedSourceTaskVersion > 0L && expectedSourceTaskVersion != retryPoint.sourceTaskVersion()) {
            throw new IllegalStateException("retry failure source version changed: expected "
                    + expectedSourceTaskVersion + " but was " + retryPoint.sourceTaskVersion());
        }
        List<String> evidenceMaterialIds = command.evidenceMaterialIds();
        Set<String> uniqueMaterialIds = new HashSet<>(evidenceMaterialIds);
        if (uniqueMaterialIds.size() != evidenceMaterialIds.size()) {
            throw new IllegalArgumentException("evidenceMaterialIds must not contain duplicates");
        }
        Set<String> taskMaterialIds = materialStore.listByTask(snapshot.taskId()).stream()
                .map(material -> material.materialId())
                .collect(java.util.stream.Collectors.toSet());
        for (String materialId : evidenceMaterialIds) {
            if (!taskMaterialIds.contains(materialId)) {
                throw new IllegalArgumentException("evidence material does not belong to task: " + materialId);
            }
        }
        if (snapshot.diagnostic().requiresSupplement()
                && command.operatorNote().isBlank()
                && evidenceMaterialIds.isEmpty()) {
            throw new IllegalArgumentException("recovery evidence or operator note is required");
        }
    }

    private void validateExistingCheckpoint(TaskRetryCheckpoint checkpoint, TaskRetryCommand command) {
        if (!command.expectedFailedStageRunId().isBlank()
                && !command.expectedFailedStageRunId().equals(checkpoint.failedStageRunId())) {
            throw new IllegalStateException("active retry checkpoint failure stage changed: expected "
                    + command.expectedFailedStageRunId() + " but was " + checkpoint.failedStageRunId());
        }
        if (!command.expectedFailedRetrievalRunId().isBlank()
                && !command.expectedFailedRetrievalRunId().equals(checkpoint.failedRetrievalRunId())) {
            throw new IllegalStateException("active retry checkpoint failure retrieval changed: expected "
                    + command.expectedFailedRetrievalRunId() + " but was " + checkpoint.failedRetrievalRunId());
        }
        if (!command.expectedFailedAiReviewRunId().isBlank()
                && !command.expectedFailedAiReviewRunId().equals(checkpoint.failedAiReviewRunId())) {
            throw new IllegalStateException("active retry checkpoint failure AI review changed: expected "
                    + command.expectedFailedAiReviewRunId() + " but was " + checkpoint.failedAiReviewRunId());
        }
        if (command.expectedSourceTaskVersion() > 0L
                && command.expectedSourceTaskVersion() != checkpoint.sourceTaskVersion()) {
            throw new IllegalStateException("active retry checkpoint source version changed: expected "
                    + command.expectedSourceTaskVersion() + " but was " + checkpoint.sourceTaskVersion());
        }

        Set<String> evidenceMaterialIds = new HashSet<>(command.evidenceMaterialIds());
        if (evidenceMaterialIds.size() != command.evidenceMaterialIds().size()) {
            throw new IllegalArgumentException("evidenceMaterialIds must not contain duplicates");
        }
        boolean explicitInput = !command.expectedFailedStageRunId().isBlank()
                || !command.expectedFailedRetrievalRunId().isBlank()
                || !command.expectedFailedAiReviewRunId().isBlank()
                || command.expectedSourceTaskVersion() > 0L
                || !command.operatorNote().isBlank()
                || !command.evidenceMaterialIds().isEmpty();
        if (!explicitInput) {
            return;
        }
        if (!command.operatorNote().equals(checkpoint.operatorNote())
                || !evidenceMaterialIds.equals(new HashSet<>(checkpoint.evidenceMaterialIds()))) {
            throw new IllegalStateException("active retry checkpoint was created with different recovery input");
        }
    }

    private void createAttempts(TaskRetryPoint point, List<AgentStageRun> preparedStageRuns) {
        if ((point.failurePhase() == TaskFailurePhase.AGENT_ROLE
                || point.failurePhase() == TaskFailurePhase.AI_REVIEW)
                && point.retryFromRole() != null) {
            createRoleAttemptsFrom(point.taskId(), point.retryFromRole(), preparedStageRuns);
            return;
        }
        if (point.failurePhase() == TaskFailurePhase.RAG && !point.failedRetrievalRunId().isBlank()) {
            retrievalRunStore.retry(point.failedRetrievalRunId(), nextId(), now());
        }
    }

    private void createRoleAttemptsFrom(
            String taskId,
            AgentRole retryFromRole,
            List<AgentStageRun> preparedStageRuns
    ) {
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        int start = roles.indexOf(retryFromRole);
        if (start < 0) {
            throw new IllegalArgumentException("retryFromRole is not a requirement role: " + retryFromRole);
        }
        List<AgentStageRun> existing = stageRunStore.listByTask(taskId);
        for (int index = start; index < roles.size(); index++) {
            AgentRole role = roles.get(index);
            AgentStageRun latest = existing.stream().filter(stage -> stage.role() == role)
                    .max(STAGE_RECENCY).orElse(null);
            if (latest != null && AgentStageTransitions.requiresFreshAttemptOnRecovery(latest.status())) {
                latest = stageRunStore.transition(
                        latest.stageRunId(),
                        AgentStageStatus.FAILED_RETRYABLE,
                        "ORCHESTRATION_INTERRUPTED",
                        "previous role attempt was interrupted before it reached a terminal state",
                        now()
                );
            }
            if (latest != null && !latest.status().isTerminal()) {
                continue;
            }
            int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
            AgentStageRun prepared = stageRunStore.save(AgentStageRun.pending(
                    nextId(), taskId, role, attemptNo,
                    taskId + ":" + role.name() + ":" + attemptNo, now()));
            preparedStageRuns.add(prepared);
        }
    }

    private void compensatePreparedStages(List<AgentStageRun> preparedStageRuns, RuntimeException original) {
        for (AgentStageRun prepared : preparedStageRuns) {
            try {
                AgentStageRun latest = stageRunStore.findById(prepared.stageRunId()).orElse(prepared);
                if (!latest.status().isTerminal()) {
                    stageRunStore.transition(
                            latest.stageRunId(),
                            AgentStageStatus.FAILED_RETRYABLE,
                            "RETRY_PREPARATION",
                            safe(original.getMessage()),
                            now()
                    );
                }
            } catch (RuntimeException compensationFailure) {
                addSuppressed(original, compensationFailure);
            }
        }
    }

    private void failCheckpoint(TaskRetryCheckpoint checkpoint, RuntimeException original) {
        try {
            TaskRetryCheckpoint latest = checkpointStore.find(checkpoint.checkpointId()).orElse(checkpoint);
            if (!latest.status().isTerminal()) {
                checkpointStore.transition(latest.checkpointId(), latest.status(),
                        TaskRetryCheckpointStatus.FAILED_RETRYABLE, safe(original.getMessage()), now());
            }
        } catch (RuntimeException compensationFailure) {
            addSuppressed(original, compensationFailure);
        }
    }

    private void compensateTask(String taskId, RuntimeException original) {
        try {
            taskPort.markRetryPreparationFailed(taskId, safe(original.getMessage()));
        } catch (RuntimeException compensationFailure) {
            addSuppressed(original, compensationFailure);
        }
    }

    private static void addSuppressed(RuntimeException original, RuntimeException compensationFailure) {
        if (compensationFailure != original) {
            original.addSuppressed(compensationFailure);
        }
    }

    private String nextId() {
        String value = safe(idSupplier.get());
        if (value.isBlank()) {
            throw new IllegalStateException("retry id supplier returned blank id");
        }
        return value;
    }

    private long now() {
        return Math.max(0L, clock.getAsLong());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
