package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPackageIsolationPolicyTest {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern MODEL_TYPE_PATTERN = Pattern.compile("(?m)^public\\s+(record|enum)\\s+([A-Za-z0-9_]+)");

    @Test
    void publicDomainRecordsAndEnumsShouldLiveUnderModelPackages() throws IOException {
        List<Path> roots = List.of(
                Path.of("src", "main", "java"),
                Path.of("..", "rag", "src", "main", "java"),
                Path.of("..", "engine", "src", "main", "java"),
                Path.of("..", "exec", "src", "main", "java"),
                Path.of("..", "skill", "src", "main", "java")
        );
        List<String> violations = new ArrayList<>();
        for (Path root : roots) {
            try (var files = Files.walk(root.normalize())) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectViolation(path, violations));
            }
        }

        assertTrue(violations.isEmpty(), () -> "records/enums must live in model packages:\n"
                + String.join("\n", violations));
    }

    private void collectViolation(Path path, List<String> violations) {
        try {
            String content = Files.readString(path);
            Matcher typeMatcher = MODEL_TYPE_PATTERN.matcher(content);
            if (!typeMatcher.find()) {
                return;
            }
            String packageName = packageName(content);
            if (!isAllowedModelPackage(packageName)) {
                violations.add(path.normalize() + " -> " + packageName + "." + typeMatcher.group(2));
            }
        } catch (IOException exception) {
            violations.add(path.normalize() + " -> unreadable: " + exception.getMessage());
        }
    }

    private String packageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean isAllowedModelPackage(String packageName) {
        return packageName.endsWith(".model")
                || packageName.contains(".model.")
                || packageName.contains(".controller.request")
                || packageName.contains(".controller.vo");
    }
}
