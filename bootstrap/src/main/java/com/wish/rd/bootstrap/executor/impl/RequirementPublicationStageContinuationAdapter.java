package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.bootstrap.threading.RequirementStageCommandFactory;
import com.wish.rd.engine.requirement.job.RequirementStageCommandStore;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.requirement.publication.RequirementPublicationContinuationPort;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationContinuation;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Host adapter that materializes reconciled publication work as a durable stage command.
 *
 * <p>The stage key includes the immutable operation id, so the command store's existing
 * {@code (task_id, role, stage)} uniqueness constraint makes concurrent scheduler enqueue calls
 * idempotent without a JVM-local lock.
 */
@Component
public final class RequirementPublicationStageContinuationAdapter implements RequirementPublicationContinuationPort {

    private static final String ROLE = "REQUIREMENT_DELIVERY";

    private final RequirementStageCommandStore stageCommandStore;
    private final RequirementStageCommandFactory commandFactory;

    /**
     * Creates the production continuation adapter with the shared command metadata factory.
     *
     * @param stageCommandStore durable stage command store
     * @param commandFactory shared stage-command metadata factory
     */
    @Autowired
    public RequirementPublicationStageContinuationAdapter(
            RequirementStageCommandStore stageCommandStore,
            RequirementStageCommandFactory commandFactory
    ) {
        this.stageCommandStore = Objects.requireNonNull(stageCommandStore, "stageCommandStore must not be null");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
    }

    /**
     * Creates a default-policy adapter for focused tests and compatibility callers.
     *
     * @param stageCommandStore durable stage command store
     * @param idGenerator provider of durable command identifiers
     * @param maxAttempts maximum command attempts
     */
    public RequirementPublicationStageContinuationAdapter(
            RequirementStageCommandStore stageCommandStore,
            SnowflakeIdGenerator idGenerator,
            int maxAttempts
    ) {
        this(
                stageCommandStore,
                new RequirementStageCommandFactory(
                        idGenerator,
                        maxAttempts,
                        com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy.defaults(),
                        null
                )
        );
    }

    @Override
    public void enqueue(RequirementPublicationContinuation continuation) {
        RequirementPublicationContinuation safeContinuation = Objects.requireNonNull(
                continuation, "continuation must not be null");
        if (safeContinuation.taskVersion() < 0L || safeContinuation.fencingToken() <= 0L) {
            throw new IllegalArgumentException("publication continuation requires non-negative version and positive fence");
        }
        long now = System.currentTimeMillis();
        RequirementStageCommand requested = commandFactory.createPendingCommand(
                safeContinuation.taskId(),
                safeContinuation.taskVersion(),
                safeContinuation.fencingToken(),
                ROLE,
                safeContinuation.stage(),
                safeContinuation.projectId(),
                safeContinuation.priority(),
                "",
                now
        );
        RequirementStageCommand effective = stageCommandStore.enqueue(requested);
        requireExactEnqueueIdentity(requested, effective);
    }

    private static void requireExactEnqueueIdentity(
            RequirementStageCommand requested,
            RequirementStageCommand effective
    ) {
        if (effective == null
                || effective.fencingToken() <= 0L
                || !requested.commandId().equals(effective.commandId())
                || !requested.taskId().equals(effective.taskId())
                || requested.taskVersion() != effective.taskVersion()
                || requested.fencingToken() != effective.fencingToken()
                || !requested.role().equals(effective.role())
                || !requested.stage().equals(effective.stage())
                || requested.maxAttempts() != effective.maxAttempts()
                || requested.deadlineEpochMillis() != effective.deadlineEpochMillis()
                || requested.resourceClass() != effective.resourceClass()
                || !requested.resourceRequirements().equals(effective.resourceRequirements())
                || !requested.projectId().equals(effective.projectId())
                || !requested.providerId().equals(effective.providerId())
                || requested.priorityRank() != effective.priorityRank()
                || !requested.policyRunId().equals(effective.policyRunId())) {
            throw new IllegalStateException("publication continuation enqueue conflict has a different durable identity");
        }
    }
}
