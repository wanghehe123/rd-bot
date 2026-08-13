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

class ImplementationPackageIsolationPolicyTest {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern IMPLEMENTATION_PATTERN = Pattern.compile(
            "(?m)^public\\s+(?:final\\s+)?class\\s+([A-Za-z0-9_]+)\\s+(?:extends\\s+[^\\{]+\\s+)?implements\\s+"
    );

    /**
     * 聚合根按 RULE.md 3.6 就该待在领域包根上，和它守护的实体同层；藏进 {@code .impl}
     * 会让"入口在哪"这件事只能靠读代码猜。豁免必须逐个列名，且下面那条测试会要求
     * RULE.md 3.6 真的把它写成聚合根，防止这里退化成绕过策略的垃圾桶。
     */
    private static final List<String> AGGREGATE_ROOT_EXEMPTIONS = List.of(
            "com.wish.rd.rag.knowledge.KnowledgeDocumentMutationEngine"
    );

    @Test
    void anExemptionMustBeBackedByADocumentedAggregateRoot() throws IOException {
        String rule = Files.readString(Path.of("..", "RULE.md").normalize());
        int section = rule.indexOf("### 3.6 聚合根");
        assertTrue(section > 0, "RULE.md 3.6 must exist for exemptions to point at");
        int nextSection = rule.indexOf("### 3.7", section);
        String aggregateRootSection = rule.substring(section, nextSection < 0 ? rule.length() : nextSection);
        for (String exemption : AGGREGATE_ROOT_EXEMPTIONS) {
            String simpleName = exemption.substring(exemption.lastIndexOf('.') + 1);
            assertTrue(aggregateRootSection.contains(simpleName),
                    "RULE.md 3.6 must name " + simpleName + " as an aggregate root, "
                            + "otherwise the exemption is just a way to skip the policy");
        }
    }

    @Test
    void publicInterfaceImplementationsShouldLiveUnderImplPackages() throws IOException {
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

        assertTrue(violations.isEmpty(), () -> "interface implementations must live in impl packages:\n"
                + String.join("\n", violations));
    }

    private void collectViolation(Path path, List<String> violations) {
        try {
            String content = Files.readString(path);
            Matcher implementationMatcher = IMPLEMENTATION_PATTERN.matcher(content);
            if (!implementationMatcher.find()) {
                return;
            }
            String packageName = packageName(content);
            String qualifiedName = packageName + "." + implementationMatcher.group(1);
            if (AGGREGATE_ROOT_EXEMPTIONS.contains(qualifiedName)) {
                return;
            }
            if (!packageName.endsWith(".impl") && !packageName.contains(".impl.")) {
                violations.add(path.normalize() + " -> " + qualifiedName);
            }
        } catch (IOException exception) {
            violations.add(path.normalize() + " -> unreadable: " + exception.getMessage());
        }
    }

    private String packageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }
}
