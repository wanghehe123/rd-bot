package com.wish.rd.exec.repair.qa.model;

import java.util.LinkedHashSet;
import java.util.List;

/** Resolved QA instructions after task, project, repository and auto-detection precedence. */
public record QaExecutionProfile(
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

    public QaExecutionProfile {
        decisionSource = text(decisionSource);
        baseUrl = text(baseUrl);
        startCommand = text(startCommand);
        healthPath = text(healthPath);
        allowedHosts = allowedHosts == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(allowedHosts.stream()
                        .map(QaExecutionProfile::text)
                        .filter(value -> !value.isBlank())
                        .toList()));
        regressionCommands = regressionCommands == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(regressionCommands.stream()
                        .map(QaExecutionProfile::text)
                        .filter(value -> !value.isBlank())
                        .toList()));
        reason = text(reason);
    }

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

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
