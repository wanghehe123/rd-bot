package com.wish.rd.engine.retrieval.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalConsumerType;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;

import java.util.List;

/** Immutable result used to build and validate a role context package. */
public record RetrievalOutcome(
        String runId,
        RetrievalRunStatus status,
        RetrievalConsumerType consumerType,
        AgentRole role,
        String stageRunId,
        List<RoleContextEvidence> selectedEvidence,
        EvidenceQualityDecision qualityDecision,
        String qualityReportArtifactId,
        List<String> missingEvidenceTypes,
        List<String> omittedEvidenceIds,
        String stopReason
) {
    public RetrievalOutcome {
        runId = safe(runId);
        status = status == null ? RetrievalRunStatus.FAILED_NEEDS_HUMAN : status;
        consumerType = consumerType == null ? RetrievalConsumerType.REQUIREMENT_BASE : consumerType;
        stageRunId = safe(stageRunId);
        selectedEvidence = selectedEvidence == null ? List.of() : List.copyOf(selectedEvidence);
        qualityReportArtifactId = safe(qualityReportArtifactId);
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : List.copyOf(missingEvidenceTypes);
        omittedEvidenceIds = omittedEvidenceIds == null ? List.of() : List.copyOf(omittedEvidenceIds);
        stopReason = safe(stopReason);
    }

    public boolean succeeded() {
        return status == RetrievalRunStatus.SUCCEEDED || status == RetrievalRunStatus.SUCCEEDED_DEGRADED;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
