package com.wish.rd.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T09/W7：公开接口实现必须落在 .impl 包；本测试同时是「禁止新增未审查违规」的守卫。
 * 历史违规按计划 W7 逐类列出豁免（每类一条理由，禁止 wildcard/整域排除）。
 * 豁免防漂移：违规行以 `路径 -> FQCN` 形式匹配豁免键，豁免键删除或源文件移动都会让守卫失败。
 */
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

private static final Map<String, String> LEGACY_IMPL_PACKAGE_EXEMPTIONS = Map.ofEntries(
            Map.entry("com.wish.rd.bootstrap.executor.RdProjectRegisteredRepositoryCatalog",
                    "RULE.md §1.1 allowlist / §十一 凭据解析锚点类；保留"),
            Map.entry("com.wish.rd.bootstrap.executor.StoredThenSystemAuthEnvironmentResolver",
                    "RULE.md §1.1 allowlist / §十一 凭据解析锚点类；保留"),
            Map.entry("com.wish.rd.bootstrap.observability.PostgresDeliveryObservabilitySnapshotAdapter",
                    "bootstrap 适配层 observability 实现（delivery/observability spec 冻结面）；保留"),
            Map.entry("com.wish.rd.bootstrap.observability.UnavailableDeliveryObservabilitySnapshotAdapter",
                    "bootstrap 适配层 observability 实现（delivery/observability spec 冻结面）；保留"),
            Map.entry("com.wish.rd.bootstrap.verify.ArtifactHostVerificationPatchSource",
                    "RULE.md §3.5.3 逐字锚点类（CleanHostVerificationWorkspaceFactory/HostVerificationExecutorAdapter 等）；包名即合同，保留"),
            Map.entry("com.wish.rd.bootstrap.verify.CleanHostVerificationWorkspaceFactory",
                    "RULE.md §3.5.3 逐字锚点类（CleanHostVerificationWorkspaceFactory/HostVerificationExecutorAdapter 等）；包名即合同，保留"),
            Map.entry("com.wish.rd.bootstrap.verify.GitHostVerificationChangeSetResolver",
                    "RULE.md §3.5.3 逐字锚点类（CleanHostVerificationWorkspaceFactory/HostVerificationExecutorAdapter 等）；包名即合同，保留"),
            Map.entry("com.wish.rd.bootstrap.verify.HostVerificationExecutorAdapter",
                    "RULE.md §3.5.3 逐字锚点类（CleanHostVerificationWorkspaceFactory/HostVerificationExecutorAdapter 等）；包名即合同，保留")
    );

    @Test
    void everyExemptionMustStillPointAtAnExistingSourceFile() {
        for (String fq : LEGACY_IMPL_PACKAGE_EXEMPTIONS.keySet()) {
            String relative = fq.replace(".", "/") + ".java";
            boolean exists = false;
            for (Path root : MODULE_ROOTS) {
                if (Files.exists(root.resolve(relative))) {
                    exists = true;
                    break;
                }
            }
            assertTrue(exists, "stale exemption (source moved or deleted): " + fq);
        }
    }

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
        List<String> violations = new ArrayList<>();
        for (Path root : MODULE_ROOTS) {
            try (var files = Files.walk(root.normalize())) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> collectViolation(path, violations));
            }
        }

        List<String> unreviewed = violations.stream()
                .filter(line -> LEGACY_IMPL_PACKAGE_EXEMPTIONS.keySet().stream()
                        .noneMatch(line::endsWith))
                .toList();
        assertTrue(unreviewed.isEmpty(), () ->
                "new interface implementations must live in impl packages (or be reviewed into "
                        + "LEGACY_IMPL_PACKAGE_EXEMPTIONS with a per-class reason):\n"
                        + String.join("\n", unreviewed));
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
            if (LEGACY_IMPL_PACKAGE_EXEMPTIONS.containsKey(qualifiedName)) {
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

    private static final List<Path> MODULE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("..", "rag", "src", "main", "java"),
            Path.of("..", "engine", "src", "main", "java"),
            Path.of("..", "exec", "src", "main", "java"),
            Path.of("..", "skill", "src", "main", "java")
    );
}
