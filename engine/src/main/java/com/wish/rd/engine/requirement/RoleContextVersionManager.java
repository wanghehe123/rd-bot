package com.wish.rd.engine.requirement;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retrieval.model.RetrievalOutcome;
import com.wish.rd.rag.context.RoleContextBuilder;
import com.wish.rd.rag.context.RoleContextPackageStore;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Creates a new immutable role-context version only when its semantic evidence changes. */
public final class RoleContextVersionManager {

    private static final Comparator<RoleContextPackage> VERSION_ORDER = Comparator
            .comparingInt(RoleContextPackage::packageVersion)
            .thenComparingLong(RoleContextPackage::createdAtEpochMillis)
            .thenComparing(RoleContextPackage::packageId);

    private final RoleContextBuilder builder;
    private final RoleContextPackageStore store;
    private final Supplier<String> idSupplier;
    private final int maxChars;

    public RoleContextVersionManager(
            RoleContextBuilder builder,
            RoleContextPackageStore store,
            Supplier<String> idSupplier,
            int maxChars
    ) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
        this.maxChars = Math.max(0, maxChars);
    }

    public Map<AgentRole, RoleContextPackage> ensureLatestContexts(
            RdRequirementTask task,
            List<TaskMaterial> materials,
            long createdAtEpochMillis
    ) {
        Objects.requireNonNull(task, "task must not be null");
        EnumMap<AgentRole, RoleContextPackage> result = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.requirementDeliveryOrder()) {
            RoleContextPackage latest = store.listByTaskAndRole(task.taskId(), role.name()).stream()
                    .max(VERSION_ORDER)
                    .orElse(null);
            int candidateVersion = latest == null ? 1 : latest.packageVersion() + 1;
            RoleContextPackage candidate = builder.build(
                    idSupplier.get(), task, materials, role.name(), maxChars, candidateVersion, createdAtEpochMillis);
            RoleContextPackage selected = latest != null && semanticSignature(latest).equals(semanticSignature(candidate))
                    ? latest
                    : store.save(candidate);
            result.put(role, selected);
        }
        return Map.copyOf(result);
    }

    /** Creates or reuses the context version derived from one successful, role-bound RetrievalRun. */
    public RoleContextPackage ensureLatestContext(
            RdRequirementTask task,
            AgentRole role,
            RetrievalOutcome retrieval,
            long createdAtEpochMillis
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(retrieval, "retrieval must not be null");
        if (!retrieval.succeeded()) {
            throw new IllegalStateException(
                    "cannot build role context from unsuccessful retrieval: " + retrieval.status()
            );
        }
        if (retrieval.role() != role) {
            throw new IllegalArgumentException("retrieval role does not match context role");
        }
        RoleContextPackage latest = store.listByTaskAndRole(task.taskId(), role.name()).stream()
                .max(VERSION_ORDER)
                .orElse(null);
        int candidateVersion = latest == null ? 1 : latest.packageVersion() + 1;
        RoleContextPackage candidate = builder.buildFromEvidence(
                idSupplier.get(), task, retrieval.selectedEvidence(), role.name(), maxChars,
                candidateVersion, retrieval.runId(), createdAtEpochMillis
        );
        if (candidate.evidence().isEmpty()) {
            throw new IllegalStateException("successful retrieval produced an empty role context");
        }
        return latest != null && semanticSignature(latest).equals(semanticSignature(candidate))
                ? latest
                : store.save(candidate);
    }

    /** Exposes the semantic signature used for context-package reuse decisions. */
    public String semanticSignatureOf(RoleContextPackage contextPackage) {
        Objects.requireNonNull(contextPackage, "contextPackage must not be null");
        return semanticSignature(contextPackage);
    }

    private String semanticSignature(RoleContextPackage contextPackage) {
        StringBuilder value = new StringBuilder(contextPackage.role()).append('|');
        for (RoleContextEvidence evidence : contextPackage.evidence()) {
            value.append(evidence.evidenceId()).append('\u001f')
                    .append(evidence.sourceType()).append('\u001f')
                    .append(evidence.sourceUri()).append('\u001f')
                    .append(evidence.title()).append('\u001f')
                    .append(evidence.contentHash()).append('\u001f')
                    .append(evidence.summary()).append('\u001f')
                    .append(evidence.selectionReason()).append('\u001f')
                    .append(evidence.relevanceScore()).append('\u001f')
                    .append(evidence.requiredEvidenceType()).append('\u001f')
                    .append(evidence.sharedRoot()).append('\u001e');
        }
        return value.append('|').append(contextPackage.acceptanceCriteria())
                .append('|').append(contextPackage.riskHints())
                .append('|').append(contextPackage.maxChars())
                .append('|').append(contextPackage.omittedEvidenceIds())
                .append('|').append(contextPackage.retrievalRunId())
                .toString();
    }
}
