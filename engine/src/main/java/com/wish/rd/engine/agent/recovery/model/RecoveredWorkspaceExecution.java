package com.wish.rd.engine.agent.recovery.model;

/**
 * A workspace output that completed the Pi result lifecycle and can be reconciled
 * onto the interrupted stage attempt without opening a fresh attempt.
 */
public record RecoveredWorkspaceExecution(
        boolean success,
        String resultJson,
        String errorCategory,
        String errorMessage,
        String providerName,
        String providerAttemptsJson
) {

    public RecoveredWorkspaceExecution {
        resultJson = resultJson == null ? "{}" : resultJson;
        errorCategory = errorCategory == null ? "" : errorCategory.strip();
        errorMessage = errorMessage == null ? "" : errorMessage.strip();
        providerName = providerName == null ? "" : providerName.strip();
        providerAttemptsJson = providerAttemptsJson == null || providerAttemptsJson.isBlank()
                ? "[]"
                : providerAttemptsJson.strip();
    }
}
