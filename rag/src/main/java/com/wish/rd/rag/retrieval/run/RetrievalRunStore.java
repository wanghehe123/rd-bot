package com.wish.rd.rag.retrieval.run;

import com.wish.rd.rag.retrieval.run.model.EvidenceQualityDecision;
import com.wish.rd.rag.retrieval.run.model.RetrievalRun;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunArtifact;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunEvent;
import com.wish.rd.rag.retrieval.run.model.RetrievalRunStatus;

import java.util.List;
import java.util.Optional;

/** Store boundary for retrieval attempts, their compare-and-set transitions, and audit events. */
public interface RetrievalRunStore {

    RetrievalRun create(RetrievalRun run);

    Optional<RetrievalRun> find(String runId);

    List<RetrievalRun> listByTask(String taskId);

    List<RetrievalRunEvent> listEvents(String runId);

    /**
     * Returns redacted process and evidence previews associated with one retrieval attempt.
     *
     * @param runId retrieval attempt identifier
     * @return append-only artifact projection ordered by creation time
     */
    List<RetrievalRunArtifact> listArtifacts(String runId);

    /**
     * Appends a bounded, redacted retrieval observation for operator inspection.
     *
     * @param artifact redacted process or evidence projection
     */
    void appendArtifact(RetrievalRunArtifact artifact);

    RetrievalRun updateEvidenceCounts(
            String runId,
            long expectedVersion,
            int candidateCount,
            int selectedEvidenceCount,
            long nowEpochMillis
    );

    RetrievalRun transition(
            String runId,
            long expectedVersion,
            RetrievalRunStatus nextStatus,
            int nextIteration,
            EvidenceQualityDecision qualityDecision,
            String stopReason,
            String errorCategory,
            String errorMessage,
            String trigger,
            long nowEpochMillis
    );

    RetrievalRun retry(String terminalRunId, String nextRunId, long nowEpochMillis);
}
