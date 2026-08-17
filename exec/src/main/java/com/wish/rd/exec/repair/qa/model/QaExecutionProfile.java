package com.wish.rd.exec.repair.qa.model;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolved QA instructions after task, project, repository and auto-detection precedence.
 *
 * <p>{@code buildCommandsDeclared} / {@code staticCommandsDeclared} distinguish an omitted
 * list from an explicit empty skip. Convenience constructors leave both undeclared.
 */
public record QaExecutionProfile(
        boolean browserRequired,
        boolean ambiguous,
        String decisionSource,
        String baseUrl,
        String startCommand,
        String healthPath,
        List<String> allowedHosts,
        List<String> regressionCommands,
        List<String> buildCommands,
        List<String> staticCommands,
        boolean buildCommandsDeclared,
        boolean staticCommandsDeclared,
        String reason
) {

    public QaExecutionProfile {
        decisionSource = text(decisionSource);
        baseUrl = text(baseUrl);
        startCommand = text(startCommand);
        healthPath = text(healthPath);
        allowedHosts = copyDistinct(allowedHosts);
        regressionCommands = copyDistinct(regressionCommands);
        buildCommands = buildCommandsDeclared ? copyDistinct(buildCommands) : List.of();
        staticCommands = staticCommandsDeclared ? copyDistinct(staticCommands) : List.of();
        reason = text(reason);
    }

    /**
     * Creates a profile with undeclared BUILD and STATIC command lists.
     *
     * @param browserRequired whether browser QA is required
     * @param ambiguous whether the decision is ambiguous
     * @param decisionSource profile source
     * @param baseUrl browser base URL
     * @param startCommand QA start command
     * @param healthPath HTTP health path
     * @param allowedHosts allowed browser hosts
     * @param regressionCommands QA regression commands
     * @param reason human-readable reason
     */
    public QaExecutionProfile(
            boolean browserRequired,
            boolean ambiguous,
            String decisionSource,
            String baseUrl,
            String startCommand,
            String healthPath,
            List<String> allowedHosts,
            List<String> regressionCommands,
            String reason
    ) {
        this(
                browserRequired,
                ambiguous,
                decisionSource,
                baseUrl,
                startCommand,
                healthPath,
                allowedHosts,
                regressionCommands,
                List.of(),
                List.of(),
                false,
                false,
                reason
        );
    }

    /**
     * Creates a profile without regression, BUILD, or STATIC command lists.
     *
     * @param browserRequired whether browser QA is required
     * @param ambiguous whether the decision is ambiguous
     * @param decisionSource profile source
     * @param baseUrl browser base URL
     * @param startCommand QA start command
     * @param healthPath HTTP health path
     * @param allowedHosts allowed browser hosts
     * @param reason human-readable reason
     */
    public QaExecutionProfile(
            boolean browserRequired,
            boolean ambiguous,
            String decisionSource,
            String baseUrl,
            String startCommand,
            String healthPath,
            List<String> allowedHosts,
            String reason
    ) {
        this(
                browserRequired,
                ambiguous,
                decisionSource,
                baseUrl,
                startCommand,
                healthPath,
                allowedHosts,
                List.of(),
                reason
        );
    }

    private static List<String> copyDistinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream()
                .map(QaExecutionProfile::text)
                .filter(value -> !value.isBlank())
                .toList()));
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
