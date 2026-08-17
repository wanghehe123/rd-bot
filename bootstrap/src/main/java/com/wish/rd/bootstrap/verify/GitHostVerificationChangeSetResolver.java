package com.wish.rd.bootstrap.verify;

import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.verify.HostVerificationChangeSetResolver;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lists files changed by the replayed candidate using git, falling back to patch-path parsing.
 *
 * <p>An empty or unreadable change-set is undeterminable and must fail closed to a full BUILD,
 * never docs-only.
 */
public final class GitHostVerificationChangeSetResolver implements HostVerificationChangeSetResolver {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Resolves repository-relative changed paths after patch replay.
     *
     * @param task        requirement task
     * @param codingStage coding stage being verified
     * @param workspace   replayed repository root
     * @return unique relative paths; empty when undeterminable
     */
    @Override
    public List<String> resolve(RdRequirementTask task, AgentStageRun codingStage, Path workspace) {
        List<String> fromGit = gitChangedFiles(workspace);
        if (!fromGit.isEmpty()) {
            return fromGit;
        }
        return parsePatchFiles(workspace);
    }

    private static List<String> gitChangedFiles(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return List.of();
        }
        // apply --index 后变更在暂存区；相对 HEAD 才能看到
        List<String> versusHead = runGitNameOnly(workspace, List.of(
                "git", "-C", workspace.toString(), "diff", "--name-only", "HEAD"
        ));
        if (!versusHead.isEmpty()) {
            return versusHead;
        }
        return runGitNameOnly(workspace, List.of(
                "git", "-C", workspace.toString(), "diff", "--name-only", "--cached"
        ));
    }

    private static List<String> runGitNameOnly(Path workspace, List<String> argv) {
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return normalizePaths(List.of(output.split("\n")));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static List<String> parsePatchFiles(Path workspace) {
        if (workspace == null) {
            return List.of();
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(workspace.resolve("candidate-patch.diff"));
        Path parent = workspace.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("input").resolve("attachments").resolve("candidate-patch.diff"));
        }
        for (Path patch : candidates) {
            List<String> parsed = parsePatchPaths(readQuietly(patch));
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return List.of();
    }

    static List<String> parsePatchPaths(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String rawLine : patch.split("\n")) {
            String line = rawLine.strip();
            if (!line.startsWith("diff --git a/")) {
                continue;
            }
            String rest = line.substring("diff --git a/".length());
            int split = rest.indexOf(" b/");
            if (split < 0) {
                continue;
            }
            addPath(paths, rest.substring(0, split));
            addPath(paths, rest.substring(split + 3));
        }
        return List.copyOf(paths);
    }

    private static void addPath(Set<String> paths, String raw) {
        String normalized = normalizePath(raw);
        if (!normalized.isBlank()) {
            paths.add(normalized);
        }
    }

    private static List<String> normalizePaths(List<String> raw) {
        Set<String> paths = new LinkedHashSet<>();
        for (String value : raw) {
            addPath(paths, value);
        }
        return List.copyOf(paths);
    }

    private static String normalizePath(String raw) {
        String value = raw == null ? "" : raw.strip().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.isBlank()
                || "/dev/null".equals(value)
                || value.contains("\0")
                || value.startsWith("/")
                || value.contains("..")) {
            return "";
        }
        return value;
    }

    private static String readQuietly(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }
}
