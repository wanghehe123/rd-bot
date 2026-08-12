package com.wish.rd.engine.retry;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import com.wish.rd.engine.retry.model.TaskRetryPoint;
import com.wish.rd.engine.retry.model.TaskRetryRoute;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/** Maps exact failure provenance to the first checkpoint-bound recovery command. */
public final class TaskRetryRoutePlanner {

    private static final String DELIVERY = "REQUIREMENT_DELIVERY";

    /**
     * Derives the canonical failure phase for one durable command stage.
     *
     * <p>This is the inverse of the frozen stage identities used by {@link #plan(TaskRetryPoint)}.
     * An unmappable stage fails closed instead of degrading to {@code CONTEXT}.
     * For {@code ROLE_EXECUTION:<role>}, a checkpoint source lineage of {@link TaskFailurePhase#RAG}
     * preserves {@code RAG}; every other lineage maps to {@link TaskFailurePhase#AGENT_ROLE}.
     *
     * @param stage durable command stage
     * @param sourceLineagePhase optional checkpoint source phase for ROLE_EXECUTION disambiguation
     * @return canonical failure phase
     * @throws IllegalStateException when the stage cannot be classified
     */
    public static TaskFailurePhase phaseForStage(String stage, TaskFailurePhase sourceLineagePhase) {
        String normalized = stage == null ? "" : stage.strip();
        if (normalized.equals("MATERIAL_COLLECTING") || normalized.equals("MATERIAL_READY")) {
            return TaskFailurePhase.MATERIAL;
        }
        if (normalized.equals("CONTEXT_BUILDING") || normalized.equals("CONTEXT_READY")) {
            return TaskFailurePhase.CONTEXT;
        }
        if (normalized.equals("PLAN_GENERATING") || normalized.equals("PLAN_GENERATED")) {
            return TaskFailurePhase.PLAN;
        }
        if (normalized.equals("POLICY_EVALUATE")
                || normalized.equals("POLICY_APPLY")
                || normalized.equals("APPROVAL_RESUME")) {
            return TaskFailurePhase.POLICY;
        }
        if (normalized.equals("DETERMINISTIC_REVIEW")) {
            return TaskFailurePhase.DETERMINISTIC_REVIEW;
        }
        if (normalized.equals("AI_REVIEW")) {
            return TaskFailurePhase.AI_REVIEW;
        }
        if (normalized.equals("PUBLICATION")) {
            return TaskFailurePhase.PR_PUBLICATION;
        }
        if (normalized.startsWith("PUBLICATION:") && normalized.length() > "PUBLICATION:".length()) {
            return TaskFailurePhase.PR_PUBLICATION;
        }
        if (normalized.startsWith("ROLE_EXECUTION:") && normalized.length() > "ROLE_EXECUTION:".length()) {
            return sourceLineagePhase == TaskFailurePhase.RAG
                    ? TaskFailurePhase.RAG
                    : TaskFailurePhase.AGENT_ROLE;
        }
        throw new IllegalStateException("unmappable exhausted command stage: " + normalized);
    }

    public TaskRetryRoute plan(TaskRetryPoint point) {
        if (point == null || point.sourceFencingToken() <= 0L || point.failedStageCommandId().isBlank()
                || point.failedStage().isBlank()) {
            throw ambiguous(point);
        }
        return switch (point.failurePhase()) {
            case MATERIAL -> earlyInfrastructure(point, "MATERIAL_COLLECTING", "MATERIAL_READY");
            case CONTEXT -> earlyInfrastructure(point, "CONTEXT_BUILDING", "CONTEXT_READY");
            case PLAN -> earlyInfrastructure(point, "PLAN_GENERATING", "PLAN_GENERATED");
            case POLICY -> {
                requirePolicy(point);
                requireStage(point, "POLICY_EVALUATE", "POLICY_APPLY", "APPROVAL_RESUME");
                yield infrastructure("POLICY_EVALUATE", allRoles());
            }
            case RAG -> {
                if (point.failedRetrievalRunId().isBlank()) {
                    throw ambiguous(point);
                }
                yield roleRoute(point);
            }
            case AGENT_ROLE -> {
                if (point.failedStageRunId().isBlank()) {
                    throw ambiguous(point);
                }
                yield roleRoute(point);
            }
            case DETERMINISTIC_REVIEW -> {
                requirePolicy(point);
                yield infrastructure(requireStage(point, "DETERMINISTIC_REVIEW"), List.of());
            }
            case AI_REVIEW -> {
                requirePolicy(point);
                if (point.failedAiReviewRunId().isBlank()) {
                    throw ambiguous(point);
                }
                requireStage(point, "AI_REVIEW");
                yield point.retryFromRole() == null ? directAiRoute(point) : aiRoleRoute(point);
            }
            case PR_PUBLICATION -> publicationRoute(point);
        };
    }

    private static TaskRetryRoute roleRoute(TaskRetryPoint point) {
        AgentRole role = point.retryFromRole();
        if (role == null || !point.failedStage().equals("ROLE_EXECUTION:" + role.name())) {
            throw ambiguous(point);
        }
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        int index = roles.indexOf(role);
        if (index < 0 || point.sourcePolicyRunId().isBlank() || point.sourcePlanDigest().isBlank()) {
            throw ambiguous(point);
        }
        return new TaskRetryRoute(RdTaskStatus.RECOVERING, role.name(), point.failedStage(),
                TaskRetryAttemptKind.AGENT_STAGE,
                point.failurePhase() == TaskFailurePhase.RAG ? TaskRetryAttemptKind.RETRIEVAL : null,
                roles.subList(index, roles.size()));
    }

    private static TaskRetryRoute directAiRoute(TaskRetryPoint point) {
        if (point.failedAiReviewRunId().isBlank() || point.sourcePolicyRunId().isBlank()
                || point.sourcePlanDigest().isBlank()) {
            throw ambiguous(point);
        }
        return new TaskRetryRoute(RdTaskStatus.RECOVERING, DELIVERY, "AI_REVIEW",
                TaskRetryAttemptKind.AI_REVIEW, null, List.of());
    }

    private static TaskRetryRoute aiRoleRoute(TaskRetryPoint point) {
        AgentRole role = point.retryFromRole();
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        int index = roles.indexOf(role);
        if (role == null || index < 0) {
            throw ambiguous(point);
        }
        return new TaskRetryRoute(RdTaskStatus.RECOVERING, role.name(), "ROLE_EXECUTION:" + role.name(),
                TaskRetryAttemptKind.AGENT_STAGE, null, roles.subList(index, roles.size()));
    }

    private static TaskRetryRoute publicationRoute(TaskRetryPoint point) {
        if (!point.failedStage().equals("PUBLICATION:" + point.publicationOperationId())
                || point.publicationOperationId().isBlank()) {
            throw ambiguous(point);
        }
        requirePolicy(point);
        return infrastructure(point.failedStage(), List.of());
    }

    private static TaskRetryRoute infrastructure(String stage, List<AgentRole> roles) {
        return new TaskRetryRoute(RdTaskStatus.RECOVERING, DELIVERY, stage, null, null, roles);
    }

    private static TaskRetryRoute earlyInfrastructure(TaskRetryPoint point, String... stages) {
        if (!point.sourcePolicyRunId().isBlank() || !point.sourcePlanDigest().isBlank()) {
            throw ambiguous(point);
        }
        return infrastructure(requireStage(point, stages), allRoles());
    }

    private static String requireStage(TaskRetryPoint point, String... stages) {
        for (String stage : stages) {
            if (stage.equals(point.failedStage())) {
                return stage;
            }
        }
        throw ambiguous(point);
    }

    private static void requirePolicy(TaskRetryPoint point) {
        if (point.sourcePolicyRunId().isBlank() || point.sourcePlanDigest().isBlank()) {
            throw ambiguous(point);
        }
    }

    private static IllegalStateException ambiguous(TaskRetryPoint point) {
        return new IllegalStateException("RETRY_POINT_AMBIGUOUS: " + (point == null ? "" : point.taskId()));
    }

    private static List<AgentRole> allRoles() {
        return AgentRole.requirementDeliveryOrder();
    }
}
