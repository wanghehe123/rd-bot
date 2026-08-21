package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.AgentStageArtifactStore;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.AgentStageTransitions;
import com.wish.rd.engine.agent.recovery.InterruptedStageRecoveryService;
import com.wish.rd.engine.agent.recovery.InterruptedStageWorkspaceRecoveryPort;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.requirement.review.AiReviewRunStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.engine.retry.model.TaskRetryCommand;
import com.wish.rd.engine.retry.model.InitializeRequirementRetryCommand;
import com.wish.rd.engine.retry.model.RequirementRetryDispatchResult;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.rag.retrieval.run.RetrievalRunStore;
import com.wish.rd.rag.runtime.TaskMaterialStore;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import com.wish.rd.engine.retry.model.TaskFailureRecoverySnapshot;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private InterruptedStageRecoveryService interruptedStageRecoveryService;
    private InterruptedStageWorkspaceRecoveryPort workspaceRecoveryPort =
            InterruptedStageWorkspaceRecoveryPort.unavailable();
    private AgentStageArtifactStore artifactStore = AgentStageArtifactStore.noop();
    private final TaskRetryPointResolver resolver;
    private final TaskRetryDispatcherPort dispatcher;
    private RequirementRetryDispatchTransactionPort retryDispatchTransactionPort;
    private TaskRetryAttemptBindingStore retryAttemptBindingStore;
    private final Supplier<String> idSupplier;
    private final LongSupplier clock;
    private long commandDeadlineMillis = RequirementDeliverySchedulingPolicy.defaults().commandDeadlineMillis();

    /**
     * Creates the Spring-managed retry orchestrator.
     *
     * @param taskPort task state port
     * @param stageRunStore role attempt store
     * @param retrievalRunStore retrieval attempt store
     * @param aiReviewRunStore AI review attempt store
     * @param checkpointStore retry checkpoint store
     * @param materialStore task material store
     * @param failureRecoveryService read-only recovery service
     * @param dispatcher durable delivery dispatcher
     * @param retryDispatchTransactionPort checkpoint-bound initialization transaction
     * @param retryAttemptBindingStore exact retry attempt bindings
     * @param idGenerator ID generator
     */
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
            RequirementRetryDispatchTransactionPort retryDispatchTransactionPort,
            TaskRetryAttemptBindingStore retryAttemptBindingStore,
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
        this.retryDispatchTransactionPort = Objects.requireNonNull(
                retryDispatchTransactionPort, "retryDispatchTransactionPort must not be null");
        this.retryAttemptBindingStore = Objects.requireNonNull(
                retryAttemptBindingStore, "retryAttemptBindingStore must not be null");
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
        this.interruptedStageRecoveryService = rebuildInterruptedStageRecoveryService();
        this.resolver = resolver == null ? new TaskRetryPointResolver() : resolver;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Autowired(required = false)
    void setInterruptedStageWorkspaceRecovery(InterruptedStageWorkspaceRecoveryPort workspaceRecoveryPort) {
        this.workspaceRecoveryPort = workspaceRecoveryPort == null
                ? InterruptedStageWorkspaceRecoveryPort.unavailable()
                : workspaceRecoveryPort;
        this.interruptedStageRecoveryService = rebuildInterruptedStageRecoveryService();
    }

    @Autowired(required = false)
    void setAgentStageArtifactStore(AgentStageArtifactStore artifactStore) {
        this.artifactStore = artifactStore == null ? AgentStageArtifactStore.noop() : artifactStore;
        this.interruptedStageRecoveryService = rebuildInterruptedStageRecoveryService();
    }

    /** Direct injection for tests and non-Spring assembly. */
    public void setInterruptedStageRecoveryService(InterruptedStageRecoveryService interruptedStageRecoveryService) {
        this.interruptedStageRecoveryService = interruptedStageRecoveryService == null
                ? rebuildInterruptedStageRecoveryService()
                : interruptedStageRecoveryService;
    }

    /** Direct injection for tests and non-Spring assembly. */
    public void setRequirementRetryDispatchTransactionPort(
            RequirementRetryDispatchTransactionPort retryDispatchTransactionPort
    ) {
        this.retryDispatchTransactionPort = retryDispatchTransactionPort;
    }

    /** Direct injection for tests and non-Spring assembly. */
    public void setTaskRetryAttemptBindingStore(TaskRetryAttemptBindingStore retryAttemptBindingStore) {
        this.retryAttemptBindingStore = retryAttemptBindingStore;
    }

    /**
     * Binds the same stage-command lifetime used by {@code RequirementStageCommandFactory}.
     * Lease renewal ({@code rd.requirement-delivery.lease-millis}) is not this deadline.
     *
     * @param commandDeadlineMillis maximum lifetime of the checkpoint's first durable command
     */
    @Value("${rd.requirement-delivery.scheduling.command-deadline-millis:3600000}")
    public void setCommandDeadlineMillis(long commandDeadlineMillis) {
        this.commandDeadlineMillis = Math.max(1L, commandDeadlineMillis);
    }

    private InterruptedStageRecoveryService rebuildInterruptedStageRecoveryService() {
        return new InterruptedStageRecoveryService(
                workspaceRecoveryPort,
                stageRunStore,
                artifactStore,
                idSupplier
        );
    }

    /**
     * Returns the current retry point without changing task or attempt state.
     *
     * <p>Preview uses the same authoritative snapshot as {@link #retry(String, String)} so a
     * publication failure that is visible on {@code /failure-recovery} cannot 409 on
     * {@code /retry-preview} merely because it has no agent-stage row.
     */
    public TaskRetryPoint preview(String taskId) {
        return failureRecoveryService.snapshot(taskId).retryPoint();
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
        RdRequirementTask currentTask = taskPort.getRequirementTask(taskId);
        if (currentTask.status() == RdTaskStatus.CANCELLED
                && !"USER".equalsIgnoreCase(trigger == null ? "" : trigger.strip())) {
            throw new IllegalStateException("cancelled task recovery requires an operator request");
        }
        TaskRetryCheckpoint active = checkpointStore.findActiveByTask(taskId).orElse(null);
        if (active != null) {
            validateExistingCheckpoint(active, safeCommand);
            return active;
        }
        TaskFailureRecoverySnapshot snapshot = failureRecoveryService.snapshot(taskId);
        validateCommand(snapshot, safeCommand);
        TaskRetryPoint point = applyRetryFromRoleOverride(snapshot.retryPoint(), safeCommand);
        int attemptNo = checkpointStore.listByTask(taskId).stream()
                .mapToInt(TaskRetryCheckpoint::attemptNo).max().orElse(0) + 1;
        String idempotencyKey = taskId + ":" + point.sourceTaskVersion() + ":"
                + point.failurePhase() + ":"
                + (point.retryFromRole() == null ? "" : point.retryFromRole().name())
                + ":" + attemptNo;
        // Legacy resolver points may still carry the task fence without a durable command/stage
        // pair. Checkpoint creation requires that audit-only shape to use fence 0 explicitly.
        TaskRetryPoint checkpointPoint = point.failedStageCommandId().isBlank()
                ? new TaskRetryPoint(
                        point.taskId(), point.failurePhase(), point.retryFromRole(),
                        point.failedStageRunId(), point.failedRetrievalRunId(), point.failedAiReviewRunId(),
                        point.reason(), point.sourceTaskVersion(), 0L, "", "", "", "", "")
                : point;
        if (retryDispatchTransactionPort == null) {
            throw new IllegalStateException("checkpoint-bound retry is not configured");
        }
        TaskRetryCheckpoint proposed = TaskRetryCheckpoint.created(
                nextId(), checkpointPoint, attemptNo, idempotencyKey, snapshot.sourceTaskStatus(),
                safeCommand, now());
        return initializeCheckpointBoundRetry(currentTask, point, proposed);
    }

    private TaskRetryCheckpoint initializeCheckpointBoundRetry(
            RdRequirementTask sourceTask,
            TaskRetryPoint point,
            TaskRetryCheckpoint proposed
    ) {
        TaskRetryRoute route = new TaskRetryRoutePlanner().plan(point);
        List<AgentStageRun> preparedStageRuns = new ArrayList<>();
        try {
            long now = now();
            long duration = commandDeadlineMillis;
            long deadline = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
            RequirementRetryDispatchResult initialized = retryDispatchTransactionPort.initialize(proposed, checkpoint -> {
                List<TaskRetryAttemptBinding> bindings = preparePrimaryBindings(
                        checkpoint, point, route, preparedStageRuns);
                String targetBindingId = targetBindingId(route, bindings);
                return new InitializeRequirementRetryCommand(
                        checkpoint, route, nextId(), 3, deadline,
                        sourceTask.projectId(), "", sourceTask.priority(), targetBindingId, bindings, now);
            });
            try {
                dispatcher.dispatchCheckpoint(initialized.checkpoint().checkpointId());
            } catch (RuntimeException ignored) {
                // Initialization is durable before scheduling. The worker recovery loop may claim the same command.
            }
            return initialized.checkpoint();
        } catch (RuntimeException exception) {
            compensatePreparedStages(preparedStageRuns, exception);
            TaskRetryCheckpoint latest = checkpointStore.find(proposed.checkpointId()).orElse(null);
            if (latest != null && !latest.status().isTerminal()) {
                failCheckpoint(latest, exception);
            }
            compensateTask(point.taskId(), exception);
            throw exception;
        }
    }

    private List<TaskRetryAttemptBinding> preparePrimaryBindings(
            TaskRetryCheckpoint checkpoint,
            TaskRetryPoint point,
            TaskRetryRoute route,
            List<AgentStageRun> preparedStageRuns
    ) {
        if (route.primaryAttemptKind() == null) {
            return List.of();
        }
        if (retryAttemptBindingStore == null) {
            throw new IllegalStateException("checkpoint-bound retry attempt bindings are not configured");
        }
        if (route.primaryAttemptKind() == com.wish.rd.engine.retry.model.TaskRetryAttemptKind.AGENT_STAGE) {
            AgentRole role = AgentRole.valueOf(route.role());
            List<AgentStageRun> boundStages = createRoleAttemptsFrom(point.taskId(), role, preparedStageRuns);
            List<TaskRetryAttemptBinding> bindings = new ArrayList<>();
            for (AgentStageRun stage : boundStages) {
                bindings.add(new TaskRetryAttemptBinding(nextId(), checkpoint.checkpointId(),
                        com.wish.rd.engine.retry.model.TaskRetryAttemptKind.AGENT_STAGE, stage.role(),
                        stage.stageRunId(), "", stage.attemptNo(), 0));
            }
            if (bindings.stream().noneMatch(binding -> binding.role() == role)) {
                throw new IllegalStateException("retry route did not create its primary role attempt: " + role);
            }
            return List.copyOf(bindings);
        }
        if (point.failedAiReviewRunId().isBlank()) {
            throw new IllegalStateException("direct AI retry has no failed review identity");
        }
        var review = aiReviewRunStore.retry(point.failedAiReviewRunId(), nextId(), now());
        return List.of(new TaskRetryAttemptBinding(nextId(), checkpoint.checkpointId(),
                com.wish.rd.engine.retry.model.TaskRetryAttemptKind.AI_REVIEW, null,
                review.runId(), "", review.attemptNo(), 0));
    }

    private static String targetBindingId(TaskRetryRoute route, List<TaskRetryAttemptBinding> bindings) {
        if (route.primaryAttemptKind() == null) {
            return "";
        }
        return bindings.stream()
                .filter(binding -> binding.kind() == route.primaryAttemptKind())
                .filter(binding -> route.role().equals(binding.role() == null ? "REQUIREMENT_DELIVERY"
                        : binding.role().name()))
                .map(TaskRetryAttemptBinding::bindingId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("retry route primary binding is missing"));
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
        String expectedCommandId = command.expectedFailedStageCommandId();
        if (!expectedCommandId.isBlank() && !expectedCommandId.equals(retryPoint.failedStageCommandId())) {
            throw new IllegalStateException("retry failure command changed: expected " + expectedCommandId
                    + " but was " + retryPoint.failedStageCommandId());
        }
        String expectedStage = command.expectedFailedStage();
        if (!expectedStage.isBlank() && !expectedStage.equals(retryPoint.failedStage())) {
            throw new IllegalStateException("retry failure command stage changed: expected " + expectedStage
                    + " but was " + retryPoint.failedStage());
        }
        long expectedFence = command.expectedSourceFencingToken();
        if (expectedFence > 0L && expectedFence != retryPoint.sourceFencingToken()) {
            throw new IllegalStateException("retry failure source fence changed: expected "
                    + expectedFence + " but was " + retryPoint.sourceFencingToken());
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
        validateRetryFromRoleOverride(retryPoint, command);
    }

    /**
     * Validates the operator's bounce-back role: only an upstream (or same) requirement-delivery
     * role of an AGENT_ROLE failure may be chosen, so completed downstream work is never skipped.
     */
    private void validateRetryFromRoleOverride(TaskRetryPoint retryPoint, TaskRetryCommand command) {
        String override = command.retryFromRoleOverride();
        if (override.isBlank()) {
            return;
        }
        if (retryPoint.failurePhase() != TaskFailurePhase.AGENT_ROLE || retryPoint.retryFromRole() == null) {
            throw new IllegalArgumentException(
                    "retryFromRoleOverride is only supported for AGENT_ROLE failures");
        }
        AgentRole overrideRole;
        try {
            overrideRole = AgentRole.valueOf(override);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("retryFromRoleOverride is not a valid role: " + override);
        }
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        int overrideIndex = roles.indexOf(overrideRole);
        int failedIndex = roles.indexOf(retryPoint.retryFromRole());
        if (overrideIndex < 0) {
            throw new IllegalArgumentException(
                    "retryFromRoleOverride is not a requirement-delivery role: " + override);
        }
        if (overrideIndex > failedIndex) {
            throw new IllegalArgumentException("retryFromRoleOverride must not skip the failed role: "
                    + override + " is downstream of " + retryPoint.retryFromRole());
        }
    }

    /** Rewrites the resolved retry point when the operator bounces the task back to an upstream role. */
    private TaskRetryPoint applyRetryFromRoleOverride(TaskRetryPoint point, TaskRetryCommand command) {
        String override = command.retryFromRoleOverride();
        if (override.isBlank() || point.failurePhase() != TaskFailurePhase.AGENT_ROLE) {
            return point;
        }
        AgentRole overrideRole = AgentRole.valueOf(override);
        if (overrideRole == point.retryFromRole()) {
            return point;
        }
        return new TaskRetryPoint(point.taskId(), point.failurePhase(), overrideRole,
                point.failedStageRunId(), point.failedRetrievalRunId(), point.failedAiReviewRunId(),
                point.reason(), point.sourceTaskVersion(), point.sourceFencingToken(),
                point.failedStageCommandId(), "ROLE_EXECUTION:" + overrideRole.name(),
                point.sourcePolicyRunId(), point.sourcePlanDigest(), point.publicationOperationId());
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
        if (!command.retryFromRoleOverride().isBlank()
                && (checkpoint.retryFromRole() == null
                        || !command.retryFromRoleOverride().equals(checkpoint.retryFromRole().name()))) {
            throw new IllegalStateException("active retry checkpoint was created for role "
                    + (checkpoint.retryFromRole() == null ? "NONE" : checkpoint.retryFromRole().name())
                    + " and cannot be redirected to " + command.retryFromRoleOverride());
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

    private List<AgentStageRun> createRoleAttemptsFrom(
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
        List<AgentStageRun> boundStages = new ArrayList<>();
        for (int index = start; index < roles.size(); index++) {
            AgentRole role = roles.get(index);
            AgentStageRun latest = existing.stream().filter(stage -> stage.role() == role)
                    .max(STAGE_RECENCY).orElse(null);
            if (latest != null && AgentStageTransitions.requiresFreshAttemptOnRecovery(latest.status())) {
                if (interruptedStageRecoveryService.tryRecoverInterruptedStage(latest, now())) {
                    boundStages.add(stageRunStore.findById(latest.stageRunId()).orElse(latest));
                    continue;
                }
                latest = stageRunStore.transition(
                        latest.stageRunId(),
                        AgentStageStatus.FAILED_RETRYABLE,
                        "ORCHESTRATION_INTERRUPTED",
                        "previous role attempt was interrupted before it reached a terminal state",
                        now()
                );
            }
            if (latest != null && !latest.status().isTerminal()) {
                if (!alreadyBoundToACheckpoint(latest.stageRunId())) {
                    boundStages.add(latest);
                    continue;
                }
                latest = stageRunStore.transition(
                        latest.stageRunId(),
                        AgentStageStatus.CANCELLED,
                        "RETRY_CHECKPOINT_SUPERSEDED",
                        "pending role attempt is already bound to an earlier retry checkpoint",
                        now()
                );
            }
            int attemptNo = latest == null ? 1 : latest.attemptNo() + 1;
            AgentStageRun prepared = stageRunStore.save(AgentStageRun.pending(
                    nextId(), taskId, role, attemptNo,
                    taskId + ":" + role.name() + ":" + attemptNo, now()));
            preparedStageRuns.add(prepared);
            boundStages.add(prepared);
        }
        return boundStages;
    }

    private boolean alreadyBoundToACheckpoint(String stageRunId) {
        return retryAttemptBindingStore != null
                && retryAttemptBindingStore.findByStageRunId(stageRunId).isPresent();
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
