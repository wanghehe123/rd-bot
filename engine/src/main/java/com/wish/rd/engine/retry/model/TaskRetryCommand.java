package com.wish.rd.engine.retry.model;

import java.util.List;

/** Operator-supplied immutable input for one resume-from-failure checkpoint. */
public record TaskRetryCommand(
        String expectedFailedStageRunId,
        String expectedFailedRetrievalRunId,
        String expectedFailedAiReviewRunId,
        long expectedSourceTaskVersion,
        String operatorNote,
        List<String> evidenceMaterialIds
) {

    private static final int MAX_NOTE_LENGTH = 8_000;
    private static final int MAX_EVIDENCE_COUNT = 10;

    public TaskRetryCommand {
        expectedFailedStageRunId = safe(expectedFailedStageRunId);
        expectedFailedRetrievalRunId = safe(expectedFailedRetrievalRunId);
        expectedFailedAiReviewRunId = safe(expectedFailedAiReviewRunId);
        if (expectedSourceTaskVersion < 0L) {
            throw new IllegalArgumentException("expectedSourceTaskVersion must not be negative");
        }
        operatorNote = safe(operatorNote);
        if (operatorNote.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("operatorNote exceeds 8000 characters");
        }
        evidenceMaterialIds = List.copyOf(evidenceMaterialIds == null ? List.of() : evidenceMaterialIds).stream()
                .map(TaskRetryCommand::safe)
                .toList();
        if (evidenceMaterialIds.size() > MAX_EVIDENCE_COUNT) {
            throw new IllegalArgumentException("evidenceMaterialIds exceeds 10 items");
        }
        if (evidenceMaterialIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("evidenceMaterialIds must not contain blank values");
        }
    }

    /** Keeps callers that only guard a role-stage failure source-compatible. */
    public TaskRetryCommand(
            String expectedFailedStageRunId,
            String operatorNote,
            List<String> evidenceMaterialIds
    ) {
        this(expectedFailedStageRunId, "", "", 0L, operatorNote, evidenceMaterialIds);
    }

    /** Keeps callers that guard stage and retrieval sources source-compatible. */
    public TaskRetryCommand(
            String expectedFailedStageRunId,
            String expectedFailedRetrievalRunId,
            long expectedSourceTaskVersion,
            String operatorNote,
            List<String> evidenceMaterialIds
    ) {
        this(expectedFailedStageRunId, expectedFailedRetrievalRunId, "",
                expectedSourceTaskVersion, operatorNote, evidenceMaterialIds);
    }

    /** Returns the legacy no-evidence retry command. */
    public static TaskRetryCommand empty() {
        return new TaskRetryCommand("", "", "", 0L, "", List.of());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
