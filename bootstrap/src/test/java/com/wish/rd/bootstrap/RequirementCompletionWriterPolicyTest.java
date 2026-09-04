package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RdTaskStatus.COMPLETED} as a requirement-task write target is only allowed
 * in {@code planCompletionStage}, {@code executeCompletionStage}, and the transition graph.
 *
 * <p>{@code RepairTaskMergeSyncEngine} reading {@code COMPLETED → MERGED} is a source-status
 * check, not a writer. Registry {@code markRequirementCompleted} is the CAS primitive and
 * may only be defined there, not called from other production writers.
 */
class RequirementCompletionWriterPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Set<String> ALLOWED_ENGINE_METHODS = Set.of(
            "planCompletionStage",
            "executeCompletionStage"
    );
    private static final Pattern METHOD_HEADER = Pattern.compile(
            "^\\s+(?:public|protected|private).+\\s+(\\w+)\\s*\\(");
    private static final Pattern MARK_COMPLETED_CALL = Pattern.compile("markRequirementCompleted\\s*\\(");
    private static final Pattern MUTATION_COMPLETED = Pattern.compile(
            "mutation\\s*\\([^;]{0,240}RdTaskStatus\\.COMPLETED");
    private static final Pattern FENCED_COMPLETED = Pattern.compile(
            "transitionRequirementFenced\\s*\\([^;]{0,240}RdTaskStatus\\.COMPLETED");

    @Test
    void completedAsRequirementTargetOnlyAppearsInCompletionStageAndTransitionPolicy() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : List.of("engine", "bootstrap", "rag")) {
            Path root = PROJECT_ROOT.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collect(path, violations));
            }
        }
        assertTrue(violations.isEmpty(),
                "RdTaskStatus.COMPLETED as a requirement task target may only be written from "
                        + "planCompletionStage / executeCompletionStage / RdTaskTransitionPolicy:\n"
                        + String.join("\n", violations));
    }

    private static void collect(Path path, List<String> violations) {
        String relative = PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
        if (relative.endsWith("RdTaskTransitionPolicy.java")) {
            return;
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException exception) {
            violations.add(relative + " -> unreadable: " + exception.getMessage());
            return;
        }
        String[] lines = content.split("\n", -1);
        String method = "";
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            Matcher header = METHOD_HEADER.matcher(line);
            if (header.find() && !line.contains("=") && !line.trim().startsWith("//")) {
                method = header.group(1);
            }
            String window = window(lines, index, 6);
            if (MARK_COMPLETED_CALL.matcher(line).find()) {
                if (line.contains("RdRequirementTask markRequirementCompleted")) {
                    continue;
                }
                if (relative.endsWith("RequirementDeliveryEngine.java")
                        && ALLOWED_ENGINE_METHODS.contains(method)) {
                    continue;
                }
                violations.add(relative + ":" + (index + 1) + " markRequirementCompleted in " + method);
            }
            if (MUTATION_COMPLETED.matcher(window).find()) {
                if (relative.endsWith("RequirementDeliveryEngine.java")
                        && "planCompletionStage".equals(method)) {
                    continue;
                }
                violations.add(relative + ":" + (index + 1) + " mutation(... COMPLETED) in " + method);
            }
            if (FENCED_COMPLETED.matcher(window).find()) {
                if (relative.endsWith("RequirementDeliveryEngine.java")
                        && "executeCompletionStage".equals(method)) {
                    continue;
                }
                violations.add(relative + ":" + (index + 1) + " transitionRequirementFenced(... COMPLETED) in "
                        + method);
            }
        }
    }

    private static String window(String[] lines, int start, int extra) {
        StringBuilder builder = new StringBuilder(lines[start]);
        for (int index = 1; index <= extra && start + index < lines.length; index++) {
            builder.append('\n').append(lines[start + index]);
        }
        return builder.toString();
    }
}
