package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.engine.provider.ProviderFallbackPolicyEnforcer;
import com.wish.rd.engine.provider.ProviderSideEffectStatusPort;
import com.wish.rd.engine.provider.ProviderToolOperationStore;
import com.wish.rd.engine.provider.model.ProviderFallbackSideEffectSafety;
import com.wish.rd.engine.provider.model.ProviderFallbackEvaluation;
import com.wish.rd.engine.provider.model.ProviderToolOperation;
import com.wish.rd.engine.provider.model.ProviderWorkRisk;
import com.wish.rd.engine.requirement.publication.RequirementPublicationStore;
import com.wish.rd.engine.requirement.publication.model.RequirementPublication;
import com.wish.rd.engine.requirement.publication.model.RequirementPublicationStatus;
import com.wish.rd.exec.repair.provider.ProviderFallbackPreflightPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Uses the Host publication ledger to gate model-provider switches both before
 * invocation and when the stage result is accepted by the engine.
 */
@Component
public final class PublicationProviderSideEffectStatusAdapter
        implements ProviderSideEffectStatusPort, ProviderFallbackPreflightPort {

    private final RequirementPublicationStore publicationStore;
    private final ProviderToolOperationStore toolOperationStore;
    private final ProviderFallbackPolicyEnforcer fallbackPolicy = new ProviderFallbackPolicyEnforcer();

    /**
     * Compatibility constructor for focused tests that intentionally exercise the fail-closed
     * no-ledger path.
     *
     * @param publicationStore durable publication ledger
     */
    public PublicationProviderSideEffectStatusAdapter(RequirementPublicationStore publicationStore) {
        this(publicationStore, ProviderToolOperationStore.unavailable());
    }

    /**
     * Creates the Host provider-fallback authority adapter.
     *
     * @param publicationStore durable publication ledger
     * @param toolOperationStore durable tool-operation ledger
     */
    @Autowired
    public PublicationProviderSideEffectStatusAdapter(
            RequirementPublicationStore publicationStore,
            ProviderToolOperationStore toolOperationStore
    ) {
        this.publicationStore = Objects.requireNonNull(publicationStore, "publicationStore must not be null");
        this.toolOperationStore = Objects.requireNonNull(
                toolOperationStore, "toolOperationStore must not be null");
    }

    @Override
    public ProviderFallbackPreflightPort.Decision evaluate(ProviderFallbackPreflightPort.Request request) {
        if (request == null) {
            return ProviderFallbackPreflightPort.Decision.block("provider fallback request is missing");
        }
        if (request.highRiskWork()) {
            return ProviderFallbackPreflightPort.Decision.block(
                    "high-risk work requires explicit human approval before a provider switch"
            );
        }
        ProviderFallbackEvaluation capabilityDecision = fallbackPolicy.evaluateWithReason(
                parseRole(request.role()),
                request.failedProvider(),
                request.failedStatus(),
                request.fallbackProvider(),
                request.sideEffectRole()
                        ? ProviderFallbackSideEffectSafety.explicitCleanAttempt(
                                request.stageRunId(), request.attemptId())
                        : ProviderFallbackSideEffectSafety.unknown(
                                "generation-only fallback has no repository side effects"
                        ),
                request.sideEffectRole()
                        ? ProviderWorkRisk.TOOL_SIDE_EFFECT
                        : ProviderWorkRisk.GENERATION_ONLY
        );
        if (!capabilityDecision.allowed()) {
            return ProviderFallbackPreflightPort.Decision.block(
                    "fallback provider capability policy blocked invocation: "
                            + capabilityDecision.reason()
            );
        }
        if (!request.sideEffectRole()) {
            return ProviderFallbackPreflightPort.Decision.allow(
                    "generation-only fallback has no repository or publication side effects"
            );
        }
        Optional<ProviderFallbackPreflightPort.Decision> publicationBlock = publicationBlock(
                request.workflowTaskId()
        );
        if (publicationBlock.isPresent()) {
            return publicationBlock.get();
        }
        Optional<ProviderFallbackPreflightPort.Decision> toolOperationBlock = toolOperationBlock(request);
        if (toolOperationBlock.isPresent()) {
            return toolOperationBlock.get();
        }
        if (request.stageRunId().isBlank()
                || request.attemptId().isBlank()
                || !request.freshWorkspace()) {
            return ProviderFallbackPreflightPort.Decision.block(
                    "host-owned fresh provider-attempt workspace evidence is missing"
            );
        }
        return ProviderFallbackPreflightPort.Decision.allow(
                "durable clean tool-operation evidence and a distinct Host workspace were verified"
        );
    }

    private static com.wish.rd.engine.agent.model.AgentRole parseRole(String role) {
        try {
            return com.wish.rd.engine.agent.model.AgentRole.valueOf(
                    role == null ? "" : role.strip().toUpperCase(java.util.Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public ProviderFallbackSideEffectSafety resolve(ProviderSideEffectStatusPort.Request request) {
        if (request == null) {
            return ProviderFallbackSideEffectSafety.unknown("provider side-effect request is missing");
        }
        Optional<RequirementPublication> latest = publicationStore.findLatestByTaskId(request.taskId());
        if (latest.isPresent()) {
            RequirementPublication publication = latest.get();
            return switch (publication.status()) {
                case UNKNOWN_REMOTE_RESULT -> ProviderFallbackSideEffectSafety.unknown(
                        "publication state is UNKNOWN_REMOTE_RESULT for operation "
                                + publication.operationId()
                );
                case NEEDS_HUMAN -> ProviderFallbackSideEffectSafety.ambiguous(
                        "publication state is NEEDS_HUMAN for operation " + publication.operationId()
                );
                case PREPARED, BRANCH_CONFIRMED, PR_CONFIRMED ->
                        ProviderFallbackSideEffectSafety.unknown(
                                "publication operation is not committed: " + publication.status()
                        );
                case COMMITTED -> ProviderFallbackSideEffectSafety.ambiguous(
                        "publication is already COMMITTED; provider replay could duplicate effects"
                );
            };
        }
        if (isSideEffectRole(request.role())) {
            Optional<ProviderToolOperation> operation = toolOperationStore.findLatestByTaskAndStageRun(
                    request.taskId(), request.stageRunId());
            if (operation.isEmpty()) {
                return ProviderFallbackSideEffectSafety.unknown(
                        "durable tool-operation evidence is missing for the provider fallback"
                );
            }
            if (!operation.get().permitsFallback()) {
                return toolOperationSafety(operation.get());
            }
        }
        ProviderFallbackSideEffectSafety evidence = request.executorEvidence();
        if (!evidence.isExplicitlySafe()) {
            return ProviderFallbackSideEffectSafety.unknown(evidence.auditReason());
        }
        if (request.stageRunId().isBlank()
                || !request.stageRunId().equals(evidence.operationId())) {
            return ProviderFallbackSideEffectSafety.ambiguous(
                    "provider-attempt operation does not match the persisted stage run"
            );
        }
        return evidence;
    }

    private Optional<ProviderFallbackPreflightPort.Decision> publicationBlock(String taskId) {
        return publicationStore.findLatestByTaskId(taskId).map(publication -> {
            String reason = switch (publication.status()) {
                case UNKNOWN_REMOTE_RESULT -> "publication state is UNKNOWN_REMOTE_RESULT";
                case NEEDS_HUMAN -> "publication state is NEEDS_HUMAN";
                case PREPARED, BRANCH_CONFIRMED, PR_CONFIRMED ->
                        "publication operation is not committed: " + publication.status();
                case COMMITTED -> "publication is already COMMITTED; fallback replay is forbidden";
            };
            return ProviderFallbackPreflightPort.Decision.block(
                    reason + " for operation " + publication.operationId()
            );
        });
    }

    private Optional<ProviderFallbackPreflightPort.Decision> toolOperationBlock(
            ProviderFallbackPreflightPort.Request request
    ) {
        Optional<ProviderToolOperation> operation = toolOperationStore.findLatestByTaskAndStageRun(
                request.workflowTaskId(), request.stageRunId());
        if (operation.isEmpty()) {
            return Optional.of(ProviderFallbackPreflightPort.Decision.block(
                    "durable tool-operation evidence is missing for side-effect provider fallback"
            ));
        }
        if (!operation.get().permitsFallback()) {
            return Optional.of(ProviderFallbackPreflightPort.Decision.block(
                    "durable tool-operation evidence is " + operation.get().status()
                            + " for operation " + operation.get().operationId()
            ));
        }
        return Optional.empty();
    }

    private static ProviderFallbackSideEffectSafety toolOperationSafety(ProviderToolOperation operation) {
        String reason = "durable tool-operation evidence is " + operation.status()
                + " for operation " + operation.operationId();
        return operation.status() == ProviderToolOperation.Status.NEEDS_HUMAN
                ? ProviderFallbackSideEffectSafety.ambiguous(reason)
                : ProviderFallbackSideEffectSafety.unknown(reason);
    }

    private static boolean isSideEffectRole(com.wish.rd.engine.agent.model.AgentRole role) {
        return role == com.wish.rd.engine.agent.model.AgentRole.CODING_AGENT
                || role == com.wish.rd.engine.agent.model.AgentRole.QA_AGENT;
    }
}
