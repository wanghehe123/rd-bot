package com.wish.rd.engine.evaluation;

import com.wish.rd.engine.evaluation.model.EvaluationArtifact;
import com.wish.rd.engine.evaluation.model.EvaluationExecutionResult;
import com.wish.rd.engine.evaluation.model.EvaluationRun;
import com.wish.rd.engine.evaluation.model.EvaluationRunOverview;
import com.wish.rd.engine.evaluation.model.EvaluationRunPage;
import com.wish.rd.engine.evaluation.model.EvaluationRunQuery;
import com.wish.rd.engine.evaluation.model.EvaluationRunEvent;
import com.wish.rd.engine.evaluation.model.EvaluationRunStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persistence boundary for evaluation runs, events, and artifact metadata. */
public interface EvaluationRunStore {
    EvaluationRun create(EvaluationRun run);

    Optional<EvaluationRun> find(String runId);

    List<EvaluationRun> list();

    /** Returns one server-side evaluation history page. PostgreSQL stores must override this with LIMIT/OFFSET. */
    default EvaluationRunPage query(EvaluationRunQuery query) {
        EvaluationRunQuery safeQuery = Objects.requireNonNull(query, "query must not be null");
        List<EvaluationRun> allRuns = list();
        List<EvaluationRun> matchedRuns = allRuns.stream().filter(safeQuery::matches).toList();
        int start = (int) Math.min(safeQuery.offset(), matchedRuns.size());
        int end = Math.min(start + safeQuery.pageSize(), matchedRuns.size());
        return EvaluationRunPage.of(
                matchedRuns.subList(start, end),
                matchedRuns.size(),
                safeQuery,
                EvaluationRunOverview.from(allRuns, safeQuery.nonSmokeDatasetIds())
        );
    }

    EvaluationRun transition(String runId, EvaluationRunStatus expected, EvaluationRunStatus target,
                             String message, String errorCategory, String errorMessage, long now);

    EvaluationRun complete(String runId, EvaluationRunStatus expected, EvaluationExecutionResult result, long now);

    void appendArtifacts(String runId, List<EvaluationArtifact> artifacts);

    List<EvaluationRunEvent> listEvents(String runId);

    List<EvaluationArtifact> listArtifacts(String runId);
}
