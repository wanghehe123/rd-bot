package com.wish.rd.engine.evaluation;

/** Reads bounded local evaluation logs and allowlisted text artifacts. */
public interface EvaluationOutputReaderPort {
    String readLog(String runId, int maxChars);

    String readArtifact(String runId, String artifactType, int maxChars);
}
