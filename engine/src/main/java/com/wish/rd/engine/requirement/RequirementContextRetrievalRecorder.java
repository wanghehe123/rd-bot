package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.DeepRetrievalOrchestrator;
import com.wish.rd.engine.retrieval.RequirementKnowledgeSearchPort;
import com.wish.rd.engine.retrieval.iterative.IterativeRetrievalPolicy;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.rag.retrieval.run.RetrievalRunLifecycle;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records requirement and role-context evidence collection as the same retrieval lifecycle used by
 * Bug repair. Existing role-context ranking stays compatible while later adapters can replace this
 * material source with knowledge-map and repository channels.
 */
public final class RequirementContextRetrievalRecorder {

    private static final int DEFAULT_TOP_K = 8;

    private final DeepRetrievalOrchestrator orchestrator;

    public RequirementContextRetrievalRecorder(RetrievalRunLifecycle lifecycle) {
        this(new DeepRetrievalOrchestrator(
                Objects.requireNonNull(lifecycle, "lifecycle must not be null"),
                RequirementKnowledgeSearchPort.noop()
        ));
    }

    public RequirementContextRetrievalRecorder(DeepRetrievalOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
    }

    public List<RetrievalOutcome> record(RdRequirementTask task, List<TaskMaterial> materials) {
        return recordWithPolicy(task, materials, IterativeRetrievalPolicy.disabled());
    }

    /**
     * Records requirement and role evidence with an explicit iterative opt-in policy.
     * The existing {@link #record} method remains single-pass by default.
     *
     * @param task requirement task
     * @param materials task materials
     * @param policy explicit retrieval policy
     * @return one outcome for the requirement base and each delivery role
     */
    public List<RetrievalOutcome> recordWithPolicy(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            IterativeRetrievalPolicy policy
    ) {
        if (task == null) {
            return List.of();
        }
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : List.copyOf(materials);
        List<RetrievalOutcome> outcomes = new ArrayList<>();
        outcomes.add(recordOnly(task, safeMaterials, RetrievalConsumerType.REQUIREMENT_BASE, null, policy));
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            outcomes.add(recordOnly(task, safeMaterials, RetrievalConsumerType.AGENT_ROLE, role, policy));
        }
        return List.copyOf(outcomes);
    }

    /** Records only the failed role and its downstream consumers during a role-stage retry. */
    public List<RetrievalOutcome> recordFromRole(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            AgentRole retryFromRole
    ) {
        if (task == null || retryFromRole == null) {
            return List.of();
        }
        List<AgentRole> roles = AgentRole.requirementDeliveryOrder();
        int start = roles.indexOf(retryFromRole);
        if (start < 0) {
            throw new IllegalArgumentException("retryFromRole is not a requirement-delivery role: " + retryFromRole);
        }
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : List.copyOf(materials);
        List<RetrievalOutcome> outcomes = new ArrayList<>();
        for (int index = start; index < roles.size(); index++) {
            outcomes.add(recordOnly(task, safeMaterials, RetrievalConsumerType.AGENT_ROLE, roles.get(index)));
        }
        return List.copyOf(outcomes);
    }

    /** Executes one explicitly selected retrieval consumer, reusing a pre-created non-terminal child attempt. */
    public RetrievalOutcome recordOnly(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role
    ) {
        return recordOnly(task, materials, consumerType, role, "", "");
    }

    /** Runs one stage-bound retrieval; upstream output remains query-only and is never evidence. */
    public RetrievalOutcome recordOnly(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            String stageRunId,
            String upstreamClues
    ) {
        return recordOnly(task, materials, consumerType, role, stageRunId, upstreamClues,
                IterativeRetrievalPolicy.disabled());
    }

    /** Runs one stage-bound retrieval with an explicit iterative opt-in policy. */
    public RetrievalOutcome recordOnly(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            String stageRunId,
            String upstreamClues,
            IterativeRetrievalPolicy policy
    ) {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        RetrievalConsumerType consumer = consumerType == null
                ? RetrievalConsumerType.REQUIREMENT_BASE : consumerType;
        if (consumer != RetrievalConsumerType.REQUIREMENT_BASE && role == null) {
            throw new IllegalArgumentException("role is required for " + consumer);
        }
        List<TaskMaterial> safeMaterials = materials == null ? List.of() : List.copyOf(materials);
        return orchestrator.retrieveWithPolicy(
                task, safeMaterials, consumer, role, stageRunId, upstreamClues, DEFAULT_TOP_K, policy
        );
    }

    private RetrievalOutcome recordOnly(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            RetrievalConsumerType consumerType,
            AgentRole role,
            IterativeRetrievalPolicy policy
    ) {
        return recordOnly(task, materials, consumerType, role, "", "", policy);
    }
}
