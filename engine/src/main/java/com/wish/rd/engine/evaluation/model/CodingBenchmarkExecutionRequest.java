package com.wish.rd.engine.evaluation.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Server-created, immutable inputs for one isolated Agent-plus-Oracle coding benchmark attempt.
 *
 * <p>No Web request creates this object. In particular, image digests, workspace paths, commands
 * and relay credentials originate from the frozen readiness snapshot and scheduler.</p>
 */
public record CodingBenchmarkExecutionRequest(
        CodingBenchmarkTrial trial,
        String agentImage,
        String oracleImage,
        Path agentRepository,
        Path agentCache,
        Path agentOutputDirectory,
        Path verifierRepository,
        Path verifierCache,
        Path candidatePatch,
        Path protectedTestBundle,
        Path protectedTestPatch,
        String protectedTestTarget,
        Path outputDirectory,
        List<String> agentCommand,
        List<String> oracleCommand,
        String relayToken,
        long agentTimeoutMillis,
        long oracleTimeoutMillis
) {
    private static final Pattern IMAGE_DIGEST = Pattern.compile(".+@sha256:[a-f0-9]{64}");

    /** Validates immutable digests, trial-private paths, non-shell argv values and bounded timeouts. */
    public CodingBenchmarkExecutionRequest {
        trial = Objects.requireNonNull(trial, "trial must not be null");
        agentImage = requireImage(agentImage, "agent image");
        oracleImage = requireImage(oracleImage, "oracle image");
        agentRepository = requirePath(agentRepository, "agent repository");
        agentCache = requirePath(agentCache, "agent cache");
        agentOutputDirectory = requirePath(agentOutputDirectory, "agent output directory");
        verifierRepository = requirePath(verifierRepository, "verifier repository");
        verifierCache = requirePath(verifierCache, "verifier cache");
        candidatePatch = requirePath(candidatePatch, "candidate patch");
        protectedTestBundle = optionalPath(protectedTestBundle);
        protectedTestPatch = optionalPath(protectedTestPatch);
        if ((protectedTestBundle == null) == (protectedTestPatch == null)) {
            throw new IllegalArgumentException("exactly one protected test bundle or protected test patch is required");
        }
        protectedTestTarget = protectedTestBundle == null
                ? optionalRelativePath(protectedTestTarget, "protected test target")
                : requireRelativePath(protectedTestTarget, "protected test target");
        outputDirectory = requirePath(outputDirectory, "output directory");
        if (agentCache.equals(verifierCache)) {
            throw new IllegalArgumentException("Agent and Oracle caches must be separate");
        }
        if (agentOutputDirectory.equals(outputDirectory)) {
            throw new IllegalArgumentException("Agent and Oracle output directories must be separate");
        }
        if (!candidatePatch.startsWith(agentOutputDirectory) || candidatePatch.equals(agentOutputDirectory)) {
            throw new IllegalArgumentException("candidate patch must stay inside the Agent output directory");
        }
        agentCommand = requireCommand(agentCommand, "agent command");
        oracleCommand = requireCommand(oracleCommand, "oracle command");
        relayToken = requireText(relayToken, "relay token");
        if (relayToken.contains("\n") || relayToken.contains("\r")) {
            throw new IllegalArgumentException("relay token must not contain line breaks");
        }
        if (agentTimeoutMillis < 1L || agentTimeoutMillis > 45L * 60_000L) {
            throw new IllegalArgumentException("agent timeout must be between one millisecond and 45 minutes");
        }
        if (oracleTimeoutMillis < 1L || oracleTimeoutMillis > 8L * 60_000L) {
            throw new IllegalArgumentException("oracle timeout must be between one millisecond and 8 minutes");
        }
    }

    /** @return the immutable {@code sha256:...} digest portion of the Agent image reference. */
    public String agentImageDigest() {
        return digestOf(agentImage);
    }

    /** @return the immutable {@code sha256:...} digest portion of the Oracle image reference. */
    public String oracleImageDigest() {
        return digestOf(oracleImage);
    }

    private static String requireImage(String value, String field) {
        String normalized = requireText(value, field).toLowerCase(Locale.ROOT);
        if (!IMAGE_DIGEST.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must use an immutable sha256 digest");
        }
        return normalized;
    }

    private static Path requirePath(Path value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value.toAbsolutePath().normalize();
    }

    private static Path optionalPath(Path value) {
        return value == null ? null : value.toAbsolutePath().normalize();
    }

    private static String requireRelativePath(String value, String field) {
        String normalized = requireText(value, field).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../")) {
            throw new IllegalArgumentException(field + " must remain repository-relative");
        }
        return normalized;
    }

    private static String optionalRelativePath(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isEmpty() ? "" : requireRelativePath(normalized, field);
    }

    private static List<String> requireCommand(List<String> command, String field) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        List<String> normalized = command.stream().map(value -> requireText(value, field + " entry")).toList();
        if (normalized.getFirst().equals("sh") || normalized.getFirst().equals("bash") || normalized.getFirst().equals("zsh")) {
            throw new IllegalArgumentException(field + " must not invoke a shell");
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String digestOf(String image) {
        return "sha256:" + image.substring(image.lastIndexOf("@sha256:") + "@sha256:".length());
    }
}
