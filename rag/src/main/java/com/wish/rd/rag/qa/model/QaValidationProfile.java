package com.wish.rd.rag.qa.model;

import java.util.List;

/** Persisted QA validation profile scoped to one project or one task. */
public record QaValidationProfile(
        String scopeType,
        String scopeId,
        String mode,
        String baseUrl,
        String startCommand,
        String healthPath,
        List<String> allowedHosts,
        List<String> regressionCommands,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public QaValidationProfile {
        scopeType = normalizeScope(scopeType);
        scopeId = requireText(scopeId, "scopeId");
        QaValidationProfileCommand command = new QaValidationProfileCommand(
                mode, baseUrl, startCommand, healthPath, allowedHosts, regressionCommands
        );
        mode = command.mode();
        baseUrl = command.baseUrl();
        startCommand = command.startCommand();
        healthPath = command.healthPath();
        allowedHosts = command.allowedHosts();
        regressionCommands = command.regressionCommands();
    }

    private static String normalizeScope(String value) {
        String normalized = requireText(value, "scopeType").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("PROJECT", "TASK").contains(normalized)) {
            throw new IllegalArgumentException("scopeType must be PROJECT or TASK");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
