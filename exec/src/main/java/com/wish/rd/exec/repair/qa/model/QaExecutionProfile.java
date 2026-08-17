package com.wish.rd.exec.repair.qa.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolved QA instructions after task, project, repository and auto-detection precedence.
 *
 * <p>{@code buildCommandsDeclared} / {@code staticCommandsDeclared} distinguish an omitted
 * list from an explicit empty skip. Convenience constructors leave both undeclared.
 *
 * <p>JSON emits {@code buildCommands}/{@code staticCommands} as {@code null} when undeclared
 * and as {@code []} when the step is explicitly skipped. In-memory accessors still return
 * an empty list when undeclared so Java callers do not need null checks.
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
        @JsonIgnore List<String> buildCommands,
        @JsonIgnore List<String> staticCommands,
        @JsonIgnore boolean buildCommandsDeclared,
        @JsonIgnore boolean staticCommandsDeclared,
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

    /**
     * JSON view of BUILD commands.
     *
     * @return {@code null} when undeclared, otherwise the normalized list (possibly empty)
     */
    @JsonProperty("buildCommands")
    public List<String> jsonBuildCommands() {
        return buildCommandsDeclared ? buildCommands : null;
    }

    /**
     * JSON view of STATIC commands.
     *
     * @return {@code null} when undeclared, otherwise the normalized list (possibly empty)
     */
    @JsonProperty("staticCommands")
    public List<String> jsonStaticCommands() {
        return staticCommandsDeclared ? staticCommands : null;
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
