package com.wish.rd.engine.requirement.verify.model;

import java.util.List;

/**
 * Immutable snapshot of one BUILD or STATIC step inside a host verification run.
 *
 * <p>Persisted by {@code HostVerificationStore} and later shown on the admin
 * verification detail API. Commands are the exact allowlisted strings executed.
 *
 * @param runId          owning verification run id
 * @param step           BUILD or STATIC
 * @param status         current step status
 * @param commands       executed command lines, possibly empty
 * @param exitCode       process exit code, or {@code null} if not executed
 * @param durationMillis wall-clock duration, {@code 0} if not started
 * @param logArtifactId  log object id, or blank when none
 * @param errorMessage   operator-facing error, or blank
 */
public record HostVerificationStep(
        String runId,
        HostVerificationStepName step,
        HostVerificationStepStatus status,
        List<String> commands,
        Integer exitCode,
        long durationMillis,
        String logArtifactId,
        String errorMessage
) {

    public HostVerificationStep {
        runId = requireText(runId, "runId");
        if (step == null) {
            throw new IllegalArgumentException("step must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis must be >= 0");
        }
        logArtifactId = normalize(logArtifactId);
        errorMessage = normalize(errorMessage);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
