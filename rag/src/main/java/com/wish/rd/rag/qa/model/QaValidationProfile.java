package com.wish.rd.rag.qa.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Persisted QA validation profile scoped to one project or one task.
 *
 * <p>HTTP JSON emits {@code buildCommands}/{@code staticCommands} as {@code null} when
 * undeclared and as {@code []} when the step is explicitly skipped.
 */
public record QaValidationProfile(
        String scopeType,
        String scopeId,
        String mode,
        String baseUrl,
        String startCommand,
        String healthPath,
        List<String> allowedHosts,
        List<String> regressionCommands,
        @JsonIgnore List<String> buildCommands,
        @JsonIgnore List<String> staticCommands,
        @JsonIgnore boolean buildCommandsDeclared,
        @JsonIgnore boolean staticCommandsDeclared,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {

    public QaValidationProfile {
        scopeType = normalizeScope(scopeType);
        scopeId = requireText(scopeId, "scopeId");
        QaValidationProfileCommand command = new QaValidationProfileCommand(
                mode,
                baseUrl,
                startCommand,
                healthPath,
                allowedHosts,
                regressionCommands,
                buildCommandsDeclared ? buildCommands : null,
                staticCommandsDeclared ? staticCommands : null
        );
        mode = command.mode();
        baseUrl = command.baseUrl();
        startCommand = command.startCommand();
        healthPath = command.healthPath();
        allowedHosts = command.allowedHosts();
        regressionCommands = command.regressionCommands();
        buildCommands = command.buildCommands();
        staticCommands = command.staticCommands();
        buildCommandsDeclared = command.buildCommandsDeclared();
        staticCommandsDeclared = command.staticCommandsDeclared();
    }

    /**
     * HTTP view of BUILD commands.
     *
     * @return {@code null} when undeclared, otherwise the normalized list (possibly empty)
     */
    @JsonProperty("buildCommands")
    public List<String> jsonBuildCommands() {
        return buildCommandsDeclared ? buildCommands : null;
    }

    /**
     * HTTP view of STATIC commands.
     *
     * @return {@code null} when undeclared, otherwise the normalized list (possibly empty)
     */
    @JsonProperty("staticCommands")
    public List<String> jsonStaticCommands() {
        return staticCommandsDeclared ? staticCommands : null;
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
