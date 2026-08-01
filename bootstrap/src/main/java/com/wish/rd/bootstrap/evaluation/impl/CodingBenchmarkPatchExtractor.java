package com.wish.rd.bootstrap.evaluation.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Host-side translation from Pi coding artifacts ({@code patch.diff} / dirty repo) into the
 * coding-benchmark contract path {@code candidate.patch}.
 */
public final class CodingBenchmarkPatchExtractor {

    private static final String PATCH_DIFF = "patch.diff";
    private static final String PROVENANCE = "candidate-patch-source.json";
    private static final List<String> DIFF_EXCLUDES = List.of(
            ":(exclude)node_modules",
            ":(exclude)target",
            ":(exclude)build",
            ":(exclude).gradle",
            ":(exclude)dist",
            ":(exclude).git"
    );

    private CodingBenchmarkPatchExtractor() {
    }

    public enum Source {
        AGENT_WRITTEN,
        PROMOTED_PATCH_DIFF,
        HOST_EXTRACTED,
        REJECTED,
        MISSING
    }

    public record Result(boolean ready, Source source, String message) {
        public Result {
            Objects.requireNonNull(source, "source must not be null");
            message = message == null ? "" : message;
        }
    }

    /**
     * Ensures {@code candidatePatch} exists and is non-empty, promoting or extracting when needed.
     *
     * @param withheldTarget relative path prefix that must not appear in the patch (may be blank)
     */
    public static Result ensure(Path agentRepository, Path agentOutput, Path candidatePatch, String withheldTarget)
            throws IOException {
        Objects.requireNonNull(agentRepository, "agentRepository must not be null");
        Objects.requireNonNull(agentOutput, "agentOutput must not be null");
        Objects.requireNonNull(candidatePatch, "candidatePatch must not be null");
        String withheld = withheldTarget == null ? "" : withheldTarget.trim();

        if (isNonEmptyFile(candidatePatch)) {
            Result validated = validateOrReject(candidatePatch, Files.readString(candidatePatch), withheld, Source.AGENT_WRITTEN);
            writeProvenance(agentOutput, validated);
            return validated;
        }

        Path patchDiff = agentOutput.resolve(PATCH_DIFF);
        if (isNonEmptyFile(patchDiff)) {
            String content = Files.readString(patchDiff);
            Result validated = validateOrReject(candidatePatch, content, withheld, Source.PROMOTED_PATCH_DIFF);
            if (validated.ready()) {
                Files.writeString(candidatePatch, content, StandardCharsets.UTF_8);
            }
            writeProvenance(agentOutput, validated);
            return validated;
        }

        String extracted;
        try {
            extracted = extractFromWorkingTree(agentRepository);
        } catch (IOException exception) {
            Result missing = new Result(false, Source.MISSING, "host git extraction failed: " + exception.getMessage());
            writeProvenance(agentOutput, missing);
            return missing;
        }
        if (extracted == null || extracted.isBlank()) {
            Result missing = new Result(false, Source.MISSING, "no candidate patch and working tree has no publishable diff");
            writeProvenance(agentOutput, missing);
            return missing;
        }
        Result validated = validateOrReject(candidatePatch, extracted, withheld, Source.HOST_EXTRACTED);
        if (validated.ready()) {
            Files.writeString(candidatePatch, extracted, StandardCharsets.UTF_8);
        }
        writeProvenance(agentOutput, validated);
        return validated;
    }

    private static Result validateOrReject(Path candidatePatch, String content, String withheld, Source source)
            throws IOException {
        if (content == null || content.isBlank()) {
            return new Result(false, Source.MISSING, "empty patch content");
        }
        if (!withheld.isEmpty() && touchesPath(content, withheld)) {
            Files.deleteIfExists(candidatePatch);
            return new Result(false, Source.REJECTED, "candidate patch touches withheld path: " + withheld);
        }
        return new Result(true, source, "");
    }

    private static boolean touchesPath(String patch, String withheldTarget) {
        String normalized = withheldTarget.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        if (normalized.isEmpty()) {
            return false;
        }
        String needle = normalized.toLowerCase(Locale.ROOT);
        for (String line : patch.split("\n", -1)) {
            if (!line.startsWith("+++ ") && !line.startsWith("--- ") && !line.startsWith("diff --git ")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (lower.contains("/" + needle + "/") || lower.contains("/" + needle + " ")
                    || lower.endsWith("/" + needle) || lower.contains(" " + needle + "/")
                    || lower.contains("b/" + needle) || lower.contains("a/" + needle)) {
                return true;
            }
        }
        return false;
    }

    private static String extractFromWorkingTree(Path agentRepository) throws IOException {
        Path indexFile = Files.createTempFile("rd-eval-index-", ".git");
        try {
            run(agentRepository, List.of("git", "read-tree", "HEAD"), indexFile);
            List<String> add = new ArrayList<>();
            add.add("git");
            add.add("add");
            add.add("-A");
            add.add("--");
            add.add(".");
            add.addAll(DIFF_EXCLUDES);
            run(agentRepository, add, indexFile);
            return run(agentRepository, List.of("git", "diff", "--cached", "--binary", "HEAD"), indexFile);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("host git extraction failed: " + exception.getMessage(), exception);
        } finally {
            Files.deleteIfExists(indexFile);
        }
    }

    private static String run(Path repo, List<String> command, Path indexFile) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repo.toFile());
        builder.environment().put("GIT_INDEX_FILE", indexFile.toAbsolutePath().normalize().toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("exit " + process.exitValue() + " for " + String.join(" ", command) + ": " + output);
        }
        return output;
    }

    private static boolean isNonEmptyFile(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) > 0L;
    }

    private static void writeProvenance(Path agentOutput, Result result) {
        try {
            String json = "{\"source\":\"" + result.source().name()
                    + "\",\"ready\":" + result.ready()
                    + ",\"message\":" + jsonString(result.message())
                    + "}\n";
            Files.writeString(agentOutput.resolve(PROVENANCE), json, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Provenance is diagnostic only; patch readiness is the contract.
        }
    }

    private static String jsonString(String value) {
        String escaped = value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }
}
