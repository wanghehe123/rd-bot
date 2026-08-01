package com.wish.rd.engine.evaluation;

import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, per-case runtime inputs needed to construct a {@link
 * com.wish.rd.engine.evaluation.model.CodingBenchmarkExecutionRequest}.
 *
 * <p>Instances are created by {@link #of} which enforces that every host path resolves inside
 * {@code prepRoot} and rejects symlinks. The record is never serialised into web-facing responses.</p>
 */
public record CodingBenchmarkCaseRuntime(
        String caseId,
        String agentImage,
        String oracleImage,
        Path agentRepository,
        Path agentCache,
        Path verifierRepository,
        Path verifierCache,
        Path protectedTestBundle,
        Path protectedTestPatch,
        String protectedTestTarget,
        List<String> agentCommand,
        List<String> oracleCommand,
        long agentTimeoutMillis,
        long oracleTimeoutMillis
) {
    private static final Pattern IMAGE_DIGEST = Pattern.compile(".+@sha256:[a-f0-9]{64}");
    private static final int AGENT_TIMEOUT_MAX = 45 * 60 * 1000;
    private static final int ORACLE_TIMEOUT_MAX = 8 * 60 * 1000;

    /**
     * Factory that enforces the prep-root boundary and validates every field.
     *
     * @param prepRoot the configured {@code rd.evaluation.coding-benchmark-prep-root}; must be an
     *     absolute, real path
     */
    public static CodingBenchmarkCaseRuntime of(
            Path prepRoot,
            String caseId,
            String agentImage,
            String oracleImage,
            Path agentRepository,
            Path agentCache,
            Path verifierRepository,
            Path verifierCache,
            Path protectedTestBundle,
            Path protectedTestPatch,
            String protectedTestTarget,
            List<String> agentCommand,
            List<String> oracleCommand,
            long agentTimeoutMillis,
            long oracleTimeoutMillis
    ) {
        Objects.requireNonNull(prepRoot, "prepRoot must not be null");
        Path realPrep;
        try {
            realPrep = prepRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("prepRoot is not accessible: " + prepRoot, e);
        }
        return new CodingBenchmarkCaseRuntime(
                Objects.requireNonNull(caseId, "caseId must not be null"),
                validateDigest(agentImage, "agentImage"),
                validateDigest(oracleImage, "oracleImage"),
                resolveInside(realPrep, agentRepository, "agentRepository"),
                resolveInside(realPrep, agentCache, "agentCache"),
                resolveInside(realPrep, verifierRepository, "verifierRepository"),
                resolveInside(realPrep, verifierCache, "verifierCache"),
                resolveInsideOrNull(realPrep, protectedTestBundle, "protectedTestBundle"),
                resolveInsideOrNull(realPrep, protectedTestPatch, "protectedTestPatch"),
                protectedTestTarget,
                agentCommand,
                oracleCommand,
                agentTimeoutMillis,
                oracleTimeoutMillis);
    }

    /** Validates all fields after construction. */
    public CodingBenchmarkCaseRuntime {
        agentCommand = agentCommand == null ? List.of() : List.copyOf(agentCommand);
        oracleCommand = oracleCommand == null ? List.of() : List.copyOf(oracleCommand);
        protectedTestTarget = protectedTestTarget == null ? "" : protectedTestTarget.strip().replace('\\', '/');
        if (!IMAGE_DIGEST.matcher(agentImage.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("agentImage must use an immutable sha256 digest");
        }
        if (!IMAGE_DIGEST.matcher(oracleImage.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("oracleImage must use an immutable sha256 digest");
        }
        if (agentCommand.isEmpty()) {
            throw new IllegalArgumentException("agentCommand must not be empty");
        }
        if (oracleCommand.isEmpty()) {
            throw new IllegalArgumentException("oracleCommand must not be empty");
        }
        if (agentCommand.getFirst().matches("sh|bash|zsh")) {
            throw new IllegalArgumentException("agentCommand must not invoke a shell");
        }
        if (oracleCommand.getFirst().matches("sh|bash|zsh")) {
            throw new IllegalArgumentException("oracleCommand must not invoke a shell");
        }
        if (agentTimeoutMillis < 1L || agentTimeoutMillis > AGENT_TIMEOUT_MAX) {
            throw new IllegalArgumentException("agentTimeoutMillis must be between 1ms and " + AGENT_TIMEOUT_MAX + "ms");
        }
        if (oracleTimeoutMillis < 1L || oracleTimeoutMillis > ORACLE_TIMEOUT_MAX) {
            throw new IllegalArgumentException("oracleTimeoutMillis must be between 1ms and " + ORACLE_TIMEOUT_MAX + "ms");
        }
    }

    private static String validateDigest(String value, String field) {
        if (value == null || !IMAGE_DIGEST.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException(field + " must use an immutable sha256 digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static Path resolveInside(Path prepRoot, Path candidate, String field) {
        Objects.requireNonNull(candidate, field + " must not be null");
        try {
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(prepRoot)) {
                throw new IllegalArgumentException(field + " resolves outside the configured prep root: " + real);
            }
            return real;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(field + " path is not accessible: " + candidate, e);
        }
    }

    private static Path resolveInsideOrNull(Path prepRoot, Path candidate, String field) {
        if (candidate == null) {
            return null;
        }
        return resolveInside(prepRoot, candidate, field);
    }
}
