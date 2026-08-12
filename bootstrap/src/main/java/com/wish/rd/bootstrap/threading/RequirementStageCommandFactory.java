package com.wish.rd.bootstrap.threading;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.job.model.RequirementStageCommand;
import com.wish.rd.engine.scheduling.FairRequirementDeliveryClaimPlanner;
import com.wish.rd.engine.scheduling.model.RequirementDeliverySchedulingPolicy;
import com.wish.rd.engine.scheduling.model.ScheduleResourceClass;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * Builds durable requirement stage commands for dispatcher and publication-recovery enqueue paths.
 *
 * <p>The factory is the single owner of deadline, resource, and provider metadata so every
 * durable command receives the same scheduler contract before it reaches a store.
 */
@Component
public final class RequirementStageCommandFactory {

    private static final Logger log = LoggerFactory.getLogger(RequirementStageCommandFactory.class);

    private final SnowflakeIdGenerator idGenerator;
    private final int maxAttempts;
    private final RequirementDeliverySchedulingPolicy schedulingPolicy;
    private final AgentExecutionProfileService executionProfileService;

    /**
     * Creates the production command factory from configured scheduling and profile services.
     *
     * @param idGenerator provider of durable command identifiers
     * @param maxAttempts configured maximum delivery attempts
     * @param schedulingPropertiesProvider provider of the bound scheduling policy properties
     * @param executionProfileServiceProvider provider of authoritative role execution profiles
     */
    @Autowired
    public RequirementStageCommandFactory(
            SnowflakeIdGenerator idGenerator,
            @Value("${rd.requirement-delivery.max-attempts:3}") int maxAttempts,
            ObjectProvider<RequirementDeliverySchedulingProperties> schedulingPropertiesProvider,
            ObjectProvider<AgentExecutionProfileService> executionProfileServiceProvider
    ) {
        this(
                idGenerator,
                maxAttempts,
                resolveSchedulingPolicy(schedulingPropertiesProvider),
                executionProfileServiceProvider == null ? null : executionProfileServiceProvider.getIfAvailable()
        );
    }

    /**
     * Creates a deterministic command factory for explicit wiring and focused tests.
     *
     * @param idGenerator provider of durable command identifiers
     * @param maxAttempts maximum delivery attempts for created commands
     * @param schedulingPolicy immutable deadline, provider, and quota policy
     * @param executionProfileService authoritative role-profile resolver, if configured
     */
    public RequirementStageCommandFactory(
            SnowflakeIdGenerator idGenerator,
            int maxAttempts,
            RequirementDeliverySchedulingPolicy schedulingPolicy,
            AgentExecutionProfileService executionProfileService
    ) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.schedulingPolicy = schedulingPolicy == null
                ? RequirementDeliverySchedulingPolicy.defaults() : schedulingPolicy;
        this.executionProfileService = executionProfileService;
    }

    /**
     * Creates a pending stage command with scheduler-complete metadata.
     *
     * @param taskId requirement task identity
     * @param taskVersion fenced task version observed by the enqueue caller
     * @param fencingToken fenced task token observed by the enqueue caller
     * @param role command role
     * @param stage durable stage identity
     * @param projectId scheduler project key
     * @param priority task priority name
     * @param previousProviderId provider carried from a preceding command when profile resolution does not apply
     * @param nowEpochMillis creation clock
     * @return a pending command with nonzero deadline and nonblank provider identity
     */
    public RequirementStageCommand createPendingCommand(
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            String projectId,
            String priority,
            String previousProviderId,
            long nowEpochMillis
    ) {
        return createPendingCommand(taskId, taskVersion, fencingToken, role, stage, projectId,
                priority, previousProviderId, "", nowEpochMillis);
    }

    /** Creates a scheduler-complete command bound to one policy generation when supplied. */
    public RequirementStageCommand createPendingCommand(
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            String projectId,
            String priority,
            String previousProviderId,
            String policyRunId,
            long nowEpochMillis
    ) {
        String safeProjectId = normalizeProject(projectId);
        Set<ScheduleResourceClass> requirements =
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage);
        String commandId = idGenerator.nextIdString();
        String effectivePolicyRunId = policyRunId == null ? "" : policyRunId.strip();
        if (effectivePolicyRunId.isBlank() && "POLICY_EVALUATE".equals(stage)) {
            effectivePolicyRunId = commandId;
        }
        return RequirementStageCommand.pending(
                commandId,
                taskId,
                taskVersion,
                fencingToken,
                role,
                stage,
                0,
                maxAttempts,
                deadlineEpochMillis(nowEpochMillis),
                primaryResource(requirements),
                requirements,
                safeProjectId,
                providerIdFor(taskId, safeProjectId, role, stage, previousProviderId),
                priority,
                effectivePolicyRunId,
                nowEpochMillis
        );
    }

    /** Creates a scheduler-complete command bound to one checkpoint retry generation. */
    public RequirementStageCommand createRetryPendingCommand(
            String taskId,
            long taskVersion,
            long fencingToken,
            String role,
            String stage,
            String projectId,
            String priority,
            String previousProviderId,
            String policyRunId,
            String retryCheckpointId,
            long businessGeneration,
            String targetRetryBindingId,
            long nowEpochMillis
    ) {
        String safeProjectId = normalizeProject(projectId);
        Set<ScheduleResourceClass> requirements =
                FairRequirementDeliveryClaimPlanner.classifyStageRequirements(role, stage);
        return RequirementStageCommand.pending(
                idGenerator.nextIdString(), taskId, taskVersion, fencingToken, role, stage, 0,
                maxAttempts, deadlineEpochMillis(nowEpochMillis), primaryResource(requirements), requirements,
                safeProjectId, providerIdFor(taskId, safeProjectId, role, stage, previousProviderId), priority,
                policyRunId, retryCheckpointId, businessGeneration, targetRetryBindingId, nowEpochMillis);
    }

    private String providerIdFor(
            String taskId,
            String projectId,
            String role,
            String stage,
            String previousProviderId
    ) {
        String fallback = schedulingPolicy.defaultProviderId();
        String profileRole = profileRoleFor(role, stage);
        if (profileRole == null) {
            String previous = previousProviderId == null ? "" : previousProviderId.strip();
            return previous.isBlank() ? fallback : previous;
        }
        if (executionProfileService == null) {
            return fallback;
        }
        try {
            return executionProfileService.resolve(projectId, taskId, profileRole)
                    .map(AgentExecutionProfile::providerProfileId)
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException exception) {
            log.warn("requirement execution profile resolution failed; using configured provider, taskId={}, "
                            + "role={}, reason={}", taskId, profileRole, safeError(exception));
            return fallback;
        }
    }

    private String profileRoleFor(String role, String stage) {
        String normalizedStage = stage == null ? "" : stage.strip().toUpperCase(java.util.Locale.ROOT);
        if ("PUBLICATION".equals(normalizedStage) || normalizedStage.startsWith("PUBLICATION:")) {
            return AgentRole.CODING_AGENT.name();
        }
        String normalizedRole = role == null ? "" : role.strip();
        return AgentRole.requirementDeliveryOrder().stream()
                .map(AgentRole::name)
                .filter(candidate -> candidate.equalsIgnoreCase(normalizedRole))
                .findFirst()
                .orElse(null);
    }

    private ScheduleResourceClass primaryResource(Set<ScheduleResourceClass> requirements) {
        if (requirements.contains(ScheduleResourceClass.BROWSER_QA)) {
            return ScheduleResourceClass.BROWSER_QA;
        }
        if (requirements.contains(ScheduleResourceClass.DOCKER)) {
            return ScheduleResourceClass.DOCKER;
        }
        if (requirements.contains(ScheduleResourceClass.PROVIDER)) {
            return ScheduleResourceClass.PROVIDER;
        }
        return ScheduleResourceClass.GENERIC;
    }

    private long deadlineEpochMillis(long nowEpochMillis) {
        long now = Math.max(0L, nowEpochMillis);
        long duration = schedulingPolicy.commandDeadlineMillis();
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static RequirementDeliverySchedulingPolicy resolveSchedulingPolicy(
            ObjectProvider<RequirementDeliverySchedulingProperties> provider
    ) {
        RequirementDeliverySchedulingProperties properties = provider == null ? null : provider.getIfAvailable();
        return properties == null ? RequirementDeliverySchedulingPolicy.defaults() : properties.toPolicy();
    }

    private static String normalizeProject(String projectId) {
        return projectId == null || projectId.isBlank() ? "_default" : projectId.strip();
    }

    private static String safeError(RuntimeException exception) {
        String message = exception == null ? "" : exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
