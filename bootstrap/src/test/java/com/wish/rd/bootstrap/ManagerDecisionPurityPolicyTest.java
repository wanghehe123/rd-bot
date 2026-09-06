package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host Manager types are a pure decision function plus a store port. They must not reach
 * the repair executor, workspace, Docker, or Pi agent runtime.
 */
class ManagerDecisionPurityPolicyTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final List<String> FORBIDDEN = List.of(
            "RepairExecutorPort",
            "RepairWorkspaceFactory",
            "DockerPiAgentExecutor",
            "rd-pi-bridge",
            "workspaces/",
            "provider-attempts/"
    );

    @Test
    void managerTypesMustNotReferenceRepairRuntimeOrWorkspace() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(
                PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine/requirement/manager"),
                PROJECT_ROOT.resolve("engine/src/main/java/com/wish/rd/engine/requirement/ManagerGapFixPackageBuilder.java")
        )) {
            if (Files.isRegularFile(root)) {
                collect(root, violations);
                continue;
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collect(path, violations));
            }
        }
        assertTrue(violations.isEmpty(),
                "Manager types must not reference repair/workspace/Docker/Pi executor:\n"
                        + String.join("\n", violations));
    }

    private static void collect(Path path, List<String> violations) {
        String relative = PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException exception) {
            violations.add(relative + " -> unreadable: " + exception.getMessage());
            return;
        }
        for (String forbidden : FORBIDDEN) {
            if (content.contains(forbidden)) {
                violations.add(relative + " contains " + forbidden);
            }
        }
    }
}
