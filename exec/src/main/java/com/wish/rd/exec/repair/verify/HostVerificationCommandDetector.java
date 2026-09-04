package com.wish.rd.exec.repair.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.qa.QaDocsOnlyChangeClassifier;
import com.wish.rd.exec.repair.qa.QaNpmInstallPlan;
import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;
import com.wish.rd.exec.repair.verify.model.HostVerificationCommandSet;
import com.wish.rd.rag.qa.model.QaValidationProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Resolves host BUILD and STATIC commands from docs-only classification, explicit QA
 * profiles, then conservative repository auto-detection.
 *
 * <p>Reuses {@link QaDocsOnlyChangeClassifier} for the docs-only allowlist and never
 * reads Coding {@code testCommands}. Node auto-detection walks the same package
 * directories as {@link QaNpmInstallPlan} ({@code .}, {@code server}, {@code client},
 * {@code frontend}, {@code web}, {@code ui}) and emits {@code npm --prefix <dir>}
 * for nested trees. Auto-detection never emits {@code npm run dev} or {@code next dev}.
 */
public final class HostVerificationCommandDetector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final QaDocsOnlyChangeClassifier DOCS_ONLY_CLASSIFIER = new QaDocsOnlyChangeClassifier();

    /**
     * Resolves commands from the repository and candidate change-set with no explicit profile.
     *
     * @param repository   prepared repository root
     * @param changedFiles real candidate paths; {@code null} or empty is undeterminable, not docs-only
     * @return resolved command set
     */
    public HostVerificationCommandSet detect(Path repository, Collection<String> changedFiles) {
        return detect(repository, changedFiles, false, List.of(), false, List.of());
    }

    /**
     * Resolves commands, letting a resolved {@link QaExecutionProfile} override auto-detection.
     *
     * @param repository   prepared repository root
     * @param changedFiles real candidate paths
     * @param profile      optional explicit profile; {@code null} means auto-detect both steps
     * @return resolved command set
     */
    public HostVerificationCommandSet detect(
            Path repository,
            Collection<String> changedFiles,
            QaExecutionProfile profile
    ) {
        if (profile == null) {
            return detect(repository, changedFiles);
        }
        return detect(
                repository,
                changedFiles,
                profile.buildCommandsDeclared(),
                profile.buildCommands(),
                profile.staticCommandsDeclared(),
                profile.staticCommands()
        );
    }

    /**
     * Resolves commands, letting a persisted {@link QaValidationProfile} override auto-detection.
     *
     * @param repository   prepared repository root
     * @param changedFiles real candidate paths
     * @param profile      optional explicit profile; {@code null} means auto-detect both steps
     * @return resolved command set
     */
    public HostVerificationCommandSet detect(
            Path repository,
            Collection<String> changedFiles,
            QaValidationProfile profile
    ) {
        if (profile == null) {
            return detect(repository, changedFiles);
        }
        return detect(
                repository,
                changedFiles,
                profile.buildCommandsDeclared(),
                profile.buildCommands(),
                profile.staticCommandsDeclared(),
                profile.staticCommands()
        );
    }

    private HostVerificationCommandSet detect(
            Path repository,
            Collection<String> changedFiles,
            boolean buildCommandsDeclared,
            List<String> declaredBuildCommands,
            boolean staticCommandsDeclared,
            List<String> declaredStaticCommands
    ) {
        if (DOCS_ONLY_CLASSIFIER.classify(changedFiles) == QaDocsOnlyChangeClassifier.Decision.DOCS_ONLY) {
            return new HostVerificationCommandSet(
                    List.of(),
                    List.of(),
                    false,
                    false,
                    true,
                    false,
                    "candidate change is docs-only; skip BUILD and STATIC"
            );
        }
        List<String> buildCommands = buildCommandsDeclared
                ? copy(declaredBuildCommands)
                : autoDetectBuild(repository);
        List<String> staticCommands = staticCommandsDeclared
                ? copy(declaredStaticCommands)
                : autoDetectStatic(repository);
        boolean ambiguous = !buildCommandsDeclared && buildCommands.isEmpty();
        return new HostVerificationCommandSet(
                buildCommands,
                staticCommands,
                buildCommandsDeclared,
                staticCommandsDeclared,
                false,
                ambiguous,
                reason(buildCommandsDeclared, staticCommandsDeclared, ambiguous)
        );
    }

    private static List<String> autoDetectBuild(Path repository) {
        return merge(detectNodeBuild(repository), detectMavenBuild(repository));
    }

    private static List<String> autoDetectStatic(Path repository) {
        return merge(detectNodeStatic(repository), detectMavenStatic(repository));
    }

    private static List<String> detectNodeBuild(Path repository) {
        List<String> commands = new ArrayList<>();
        for (String relative : QaNpmInstallPlan.packageDirectories(repository)) {
            commands.addAll(detectNodeBuildIn(packageDirectory(repository, relative), npmPrefix(relative)));
        }
        return List.copyOf(commands);
    }

    private static List<String> detectNodeBuildIn(Path directory, String prefix) {
        JsonNode root = readPackageJson(directory);
        if (root == null) {
            return List.of();
        }
        JsonNode scripts = root.path("scripts");
        boolean hasTest = hasScript(scripts, "test");
        boolean hasBuild = hasScript(scripts, "build");
        // 没有 test/build 时不把 npm install 单独当作 BUILD，否则陌生仓库会空跑安装后被当成通过
        if (!hasTest && !hasBuild) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        commands.add(npmCommand(prefix, installCommand(directory)));
        if (hasTest) {
            commands.add(npmCommand(prefix, "npm test"));
        }
        if (hasBuild) {
            commands.add(npmCommand(prefix, "npm run build"));
        }
        return List.copyOf(commands);
    }

    private static List<String> detectNodeStatic(Path repository) {
        List<String> commands = new ArrayList<>();
        for (String relative : QaNpmInstallPlan.packageDirectories(repository)) {
            commands.addAll(detectNodeStaticIn(packageDirectory(repository, relative), npmPrefix(relative)));
        }
        return List.copyOf(commands);
    }

    private static List<String> detectNodeStaticIn(Path directory, String prefix) {
        JsonNode root = readPackageJson(directory);
        if (root == null) {
            return List.of();
        }
        JsonNode scripts = root.path("scripts");
        List<String> commands = new ArrayList<>();
        if (hasScript(scripts, "typecheck")) {
            commands.add(npmCommand(prefix, "npm run typecheck"));
        } else if (prefix.isBlank() && Files.isRegularFile(directory.resolve("tsconfig.json"))) {
            commands.add("npx tsc --noEmit");
        }
        if (hasScript(scripts, "lint")) {
            commands.add(npmCommand(prefix, "npm run lint"));
        }
        return List.copyOf(commands);
    }

    private static List<String> detectMavenBuild(Path repository) {
        if (!hasMavenWrapper(repository)) {
            return List.of();
        }
        return List.of("./mvnw -q test");
    }

    private static List<String> detectMavenStatic(Path repository) {
        if (!hasMavenWrapper(repository)) {
            return List.of();
        }
        String pom;
        try {
            pom = Files.readString(repository.resolve("pom.xml"));
        } catch (IOException exception) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        // STATIC 只追加仓库已经声明的 checkstyle/spotbugs，避免给未接入的仓库硬加失败目标
        if (declaresPlugin(pom, "maven-checkstyle-plugin")) {
            commands.add("./mvnw -q checkstyle:check");
        }
        if (declaresPlugin(pom, "spotbugs-maven-plugin")) {
            commands.add("./mvnw -q spotbugs:check");
        }
        return List.copyOf(commands);
    }

    private static boolean hasMavenWrapper(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return false;
        }
        Path pom = repository.resolve("pom.xml");
        Path mvnw = repository.resolve("mvnw");
        return Files.isRegularFile(pom) && looksLikeExecutableWrapper(mvnw);
    }

    private static boolean looksLikeExecutableWrapper(Path mvnw) {
        if (!Files.isRegularFile(mvnw)) {
            return false;
        }
        if (Files.isExecutable(mvnw)) {
            return true;
        }
        try {
            String content = Files.readString(mvnw).stripLeading();
            return content.startsWith("#!");
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean declaresPlugin(String pom, String artifactId) {
        return pom.contains("<artifactId>" + artifactId + "</artifactId>");
    }

    private static JsonNode readPackageJson(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return null;
        }
        Path packageJson = repository.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(Files.readString(packageJson));
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean hasScript(JsonNode scripts, String name) {
        return scripts != null && scripts.isObject() && scripts.has(name);
    }

    private static String installCommand(Path repository) {
        return Files.isRegularFile(repository.resolve("package-lock.json")) ? "npm ci" : "npm install";
    }

    private static Path packageDirectory(Path repository, String relative) {
        return ".".equals(relative) ? repository : repository.resolve(relative);
    }

    private static String npmPrefix(String relative) {
        return ".".equals(relative) ? "" : relative;
    }

    private static String npmCommand(String prefix, String command) {
        if (prefix == null || prefix.isBlank()) {
            return command;
        }
        if (command.startsWith("npm ")) {
            return "npm --prefix " + prefix + command.substring("npm".length());
        }
        if (command.startsWith("npx ")) {
            return "npm --prefix " + prefix + " exec -- " + command.substring("npx ".length());
        }
        return command;
    }

    private static String reason(
            boolean buildCommandsDeclared,
            boolean staticCommandsDeclared,
            boolean ambiguous
    ) {
        if (ambiguous) {
            return "cannot detect safe BUILD commands from the repository";
        }
        if (buildCommandsDeclared && staticCommandsDeclared) {
            return "using declared BUILD and STATIC commands from the QA profile";
        }
        if (buildCommandsDeclared) {
            return "using declared BUILD commands; auto-detected STATIC from the repository";
        }
        if (staticCommandsDeclared) {
            return "using declared STATIC commands; auto-detected BUILD from the repository";
        }
        return "auto-detected host verification commands from the repository";
    }

    private static List<String> merge(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private static List<String> copy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(values);
    }
}
