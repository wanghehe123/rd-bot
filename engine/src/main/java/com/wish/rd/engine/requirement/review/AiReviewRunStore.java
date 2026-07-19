package com.wish.rd.engine.requirement.review;

import com.wish.rd.engine.requirement.review.model.AiReviewArtifact;
import com.wish.rd.engine.requirement.review.model.AiReviewEvent;
import com.wish.rd.engine.requirement.review.model.AiReviewRun;
import com.wish.rd.engine.requirement.review.model.AiReviewRunStatus;
import com.wish.rd.engine.requirement.review.model.AiReviewResult;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for immutable AI review attempts, events, and redacted artifacts. */
public interface AiReviewRunStore {

    AiReviewRun create(AiReviewRun run);

    Optional<AiReviewRun> find(String runId);

    List<AiReviewRun> listByTask(String taskId);

    AiReviewRun transition(
            String runId,
            AiReviewRunStatus expectedStatus,
            AiReviewRunStatus targetStatus,
            String trigger,
            String message,
            String errorCategory,
            String errorMessage,
            long nowEpochMillis
    );

    AiReviewRun save(AiReviewRun run);

    AiReviewRun complete(
            String runId,
            AiReviewRunStatus expectedStatus,
            AiReviewRunStatus terminalStatus,
            AiReviewResult result,
            String trigger,
            String message,
            long nowEpochMillis
    );

    AiReviewRun retry(String terminalRunId, String nextRunId, long nowEpochMillis);

    List<AiReviewEvent> listEvents(String runId);

    AiReviewArtifact appendArtifact(AiReviewArtifact artifact);

    List<AiReviewArtifact> listArtifacts(String runId);
}
