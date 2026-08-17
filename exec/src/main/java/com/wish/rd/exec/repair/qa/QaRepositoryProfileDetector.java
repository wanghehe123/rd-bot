package com.wish.rd.exec.repair.qa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;
import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Resolves whether browser QA is required using deterministic precedence and conservative auto-detection.
 *
 * <p>When the candidate change set is provably docs-only, browser build/start checks are skipped.
 * Ambiguous or undeterminable change sets fail closed to the full browser profile.
 */
public final class QaRepositoryProfileDetector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final QaDocsOnlyChangeClassifier DOCS_ONLY_CLASSIFIER = new QaDocsOnlyChangeClassifier();
    private static final List<String> REPOSITORY_PROFILE_PATHS = List.of(
            ".rd-bot/qa-profile.json",
            "rd-bot.qa.json"
    );

    /**
     * Resolves the QA profile and applies the docs-only exception from the repository's real git change set.
     *
     * @param command repair job command with optional QA profile overrides
     * @param repository prepared repository root (candidate patch already applied when present)
     * @return resolved execution profile
     */
    public QaExecutionProfile detect(RepairJobCommand command, Path repository) {
        return detect(command, repository, readCandidateChangedFiles(repository));
    }

    /**
     * Resolves the QA profile using an explicit candidate changed-file set (tests and host callers).
     *
     * @param command repair job command with optional QA profile overrides
     * @param repository prepared repository root
     * @param candidateChangedFiles real changed paths from the candidate patch; {@code null} means undeterminable
     * @return resolved execution profile
     */
    public QaExecutionProfile detect(
            RepairJobCommand command,
            Path repository,
            List<String> candidateChangedFiles
    ) {
        Map<String, String> context = command == null || command.contextJson() == null
                ? Map.of()
                : command.contextJson();
        String taskProfileJson = firstNonBlank(
                context.get("qaTaskOverrideJson"), browserModeJson(context.get("qaBrowserMode")));
        QaExecutionProfile resolved;
        if (isAutoProfile(taskProfileJson)) {
            resolved = withRegressionCommands(
                    repositoryProfileOrAuto(repository),
                    profileRegressionCommands(taskProfileJson)
            );
        } else {
            QaExecutionProfile taskOverride = explicitProfile(
                    taskProfileJson,
                    "TASK_OVERRIDE"
            );
            if (taskOverride != null) {
                resolved = taskOverride;
            } else {
                String projectProfileJson = context.get("qaProjectProfileJson");
                if (isAutoProfile(projectProfileJson)) {
                    resolved = withRegressionCommands(
                            repositoryProfileOrAuto(repository),
                            profileRegressionCommands(projectProfileJson)
                    );
                } else {
                    QaExecutionProfile projectProfile = explicitProfile(projectProfileJson, "PROJECT_PROFILE");
                    resolved = projectProfile != null ? projectProfile : repositoryProfileOrAuto(repository);
                }
            }
        }
        return applyDocsOnlyException(resolved, candidateChangedFiles);
    }

    public String toJson(QaExecutionProfile profile) {
        return toJson(profile, null);
    }

    /**
     * Serializes the resolved profile and embeds the host-computed candidate changed-file set.
     *
     * @param profile resolved QA profile
     * @param candidateChangedFiles real changed paths; omitted when {@code null}
     * @return JSON written to {@code /work/input/qa-profile.json}
     */
    public String toJson(QaExecutionProfile profile, List<String> candidateChangedFiles) {
        QaExecutionProfile safe = profile == null ? notApplicable("QA profile missing") : profile;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.valueToTree(safe);
            if (candidateChangedFiles != null) {
                com.fasterxml.jackson.databind.node.ArrayNode files = node.putArray("candidateChangedFiles");
                for (String path : candidateChangedFiles) {
                    if (path != null && !path.isBlank()) {
                        files.add(path.strip().replace('\\', '/'));
                    }
                }
            }
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize QA execution profile", exception);
        }
    }

    private QaExecutionProfile repositoryProfile(Path repository) {
        if (repository == null) {
            return null;
        }
        for (String relativePath : REPOSITORY_PROFILE_PATHS) {
            Path profilePath = repository.resolve(relativePath).normalize();
            if (!profilePath.startsWith(repository.toAbsolutePath().normalize()) || !Files.isRegularFile(profilePath)) {
                continue;
            }
            try {
                String json = Files.readString(profilePath);
                if (isAutoProfile(json)) {
                    return withRegressionCommands(autoDetect(repository), profileRegressionCommands(json));
                }
                return explicitProfile(json, "REPOSITORY_CONFIG");
            } catch (IOException exception) {
                return ambiguous("REPOSITORY_CONFIG", "cannot read " + relativePath);
            }
        }
        return null;
    }

    private QaExecutionProfile explicitProfile(String json, String source) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                return ambiguous(source, "QA profile must be a JSON object");
            }
            String mode = root.path("mode").asText("AUTO").strip().toUpperCase(Locale.ROOT);
            if ("AUTO".equals(mode)) {
                return null;
            }
            if ("DISABLED".equals(mode)) {
                return new QaExecutionProfile(
                        false, false, source, "", "", "", List.of(), "browser QA explicitly disabled"
                );
            }
            if (!"REQUIRED".equals(mode)) {
                return ambiguous(source, "QA profile mode must be REQUIRED or DISABLED");
            }
            String baseUrl = root.path("baseUrl").asText("").strip();
            String startCommand = root.path("startCommand").asText("").strip();
            String healthPath = root.path("healthPath").asText("/").strip();
            List<String> allowedHosts = strings(root.path("allowedHosts"));
            List<String> regressionCommands = strings(root.path("regressionCommands"));
            QaValidationProfileCommand validated = new QaValidationProfileCommand(
                    mode,
                    baseUrl,
                    startCommand,
                    healthPath,
                    allowedHosts,
                    regressionCommands,
                    null,
                    null
            );
            return new QaExecutionProfile(
                    true,
                    false,
                    source,
                    validated.baseUrl(),
                    validated.startCommand(),
                    validated.healthPath().isBlank() ? "/" : validated.healthPath(),
                    validated.allowedHosts(),
                    validated.regressionCommands(),
                    "explicit browser QA profile"
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ambiguous(source, "QA profile is invalid or unsafe");
        }
    }

    private QaExecutionProfile autoDetect(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return ambiguous("AUTO_DETECTION", "repository is unavailable for QA detection");
        }
        QaExecutionProfile nestedWebProfile = nestedViteProfile(repository);
        if (nestedWebProfile != null) {
            return nestedWebProfile;
        }
        Path packageJson = repository.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.readString(packageJson));
                String dependencies = root.path("dependencies").toString().toLowerCase(Locale.ROOT)
                        + root.path("devDependencies").toString().toLowerCase(Locale.ROOT);
                JsonNode scripts = root.path("scripts");
                String scriptsText = scripts.toString().toLowerCase(Locale.ROOT);
                if (dependencies.contains("vite") || scriptsText.contains("vite")) {
                    return autoWeb("http://127.0.0.1:5173", "npm run dev -- --host 0.0.0.0", "Vite project");
                }
                if (dependencies.contains("next") || scriptsText.contains("next dev")) {
                    // Dev-mode Next.js hydrates too slowly inside the QA container and
                    // silently swallows clicks, so browser QA must run the production build.
                    return autoWeb(
                            "http://127.0.0.1:3000",
                            "npm run build && npm run start -- --hostname 0.0.0.0",
                            "Next.js project (production mode)"
                    );
                }
                if (dependencies.contains("@angular/") || scriptsText.contains("ng serve")) {
                    return autoWeb(
                            "http://127.0.0.1:4200",
                            "npm run start -- --host 0.0.0.0",
                            "Angular project"
                    );
                }
                if (dependencies.contains("react-scripts") || scriptsText.contains("react-scripts start")) {
                    return autoWeb(
                            "http://127.0.0.1:3000",
                            "HOST=0.0.0.0 npm start",
                            "Create React App project"
                    );
                }
                if (hasWebDependency(dependencies) || hasStartScript(scripts)) {
                    return ambiguous("AUTO_DETECTION", "web markers found but base URL or start command is uncertain");
                }
            } catch (IOException exception) {
                return ambiguous("AUTO_DETECTION", "package.json cannot be parsed");
            }
        }
        if (hasStaticHtml(repository)) {
            return autoWeb(
                    "http://127.0.0.1:4173",
                    "python3 -m http.server 4173 --bind 0.0.0.0",
                    "static HTML project"
            );
        }
        return notApplicable("no web project markers detected");
    }

    private static QaExecutionProfile nestedViteProfile(Path repository) {
        if (!Files.isRegularFile(repository.resolve("start.sh"))) {
            return null;
        }
        for (String directory : List.of("client", "frontend", "web", "ui")) {
            Path packageJson = repository.resolve(directory).resolve("package.json");
            if (!Files.isRegularFile(packageJson)) {
                continue;
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(Files.readString(packageJson));
                String dependencies = root.path("dependencies").toString().toLowerCase(Locale.ROOT)
                        + root.path("devDependencies").toString().toLowerCase(Locale.ROOT);
                String scripts = root.path("scripts").toString().toLowerCase(Locale.ROOT);
                if (dependencies.contains("vite") || scripts.contains("vite")) {
                    return autoWeb(
                            "http://127.0.0.1:5173",
                            "./start.sh",
                            "nested Vite project with repository start script"
                    );
                }
            } catch (IOException ignored) {
                return ambiguous("AUTO_DETECTION", directory + "/package.json cannot be parsed");
            }
        }
        return null;
    }

    private static boolean hasWebDependency(String dependencies) {
        return List.of("react", "vue", "svelte", "express", "koa", "fastify")
                .stream()
                .anyMatch(dependencies::contains);
    }

    private static boolean hasStartScript(JsonNode scripts) {
        return scripts.isObject() && (scripts.has("dev") || scripts.has("start") || scripts.has("serve"));
    }

    private static boolean hasStaticHtml(Path repository) {
        try (var files = Files.list(repository)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html"));
        } catch (IOException exception) {
            return false;
        }
    }

    private static QaExecutionProfile autoWeb(String baseUrl, String startCommand, String reason) {
        return new QaExecutionProfile(
                true,
                false,
                "AUTO_DETECTION",
                baseUrl,
                startCommand,
                "/",
                hostsFor(baseUrl),
                reason
        );
    }

    private static QaExecutionProfile ambiguous(String source, String reason) {
        return new QaExecutionProfile(true, true, source, "", "", "", List.of(), reason);
    }

    private static QaExecutionProfile notApplicable(String reason) {
        return new QaExecutionProfile(
                false, false, "NOT_APPLICABLE", "", "", "", List.of(), reason
        );
    }

    private QaExecutionProfile repositoryProfileOrAuto(Path repository) {
        QaExecutionProfile repositoryProfile = repositoryProfile(repository);
        return repositoryProfile == null ? autoDetect(repository) : repositoryProfile;
    }

    private static QaExecutionProfile applyDocsOnlyException(
            QaExecutionProfile profile,
            List<String> candidateChangedFiles
    ) {
        if (profile == null || !profile.browserRequired() || profile.ambiguous()) {
            return profile;
        }
        if (DOCS_ONLY_CLASSIFIER.classify(candidateChangedFiles)
                != QaDocsOnlyChangeClassifier.Decision.DOCS_ONLY) {
            // UNDETERMINABLE and NOT_DOCS_ONLY both keep the full browser profile (fail closed).
            return profile;
        }
        return new QaExecutionProfile(
                false,
                false,
                "DOCS_ONLY",
                "",
                "",
                "",
                List.of(),
                List.of(),
                "candidate change is docs-only; skip build/start/browser regression"
        );
    }

    /**
     * Reads the real candidate changed-file set from the prepared repository.
     *
     * <p>Prefers staged paths ({@code git diff --cached}) because local QA applies the candidate
     * patch with {@code git apply --index}. Falls back to unstaged and untracked paths. Returns
     * {@code null} when git is unavailable so callers fail closed to the full profile.
     *
     * @param repository prepared repository root
     * @return changed paths, or {@code null} when the set cannot be determined
     */
    public static List<String> readCandidateChangedFiles(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return null;
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (!collectGitPaths(repository, List.of("diff", "--cached", "--name-only", "--diff-filter=ACDMRTUXB"), paths)
                || !collectGitPaths(repository, List.of("diff", "--name-only", "--diff-filter=ACDMRTUXB"), paths)
                || !collectGitPaths(repository, List.of("ls-files", "--others", "--exclude-standard"), paths)) {
            return null;
        }
        return List.copyOf(paths);
    }

    private static boolean collectGitPaths(Path repository, List<String> gitArgs, LinkedHashSet<String> paths) {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.add("-C");
        argv.add(repository.toAbsolutePath().normalize().toString());
        argv.addAll(gitArgs);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            for (String line : output.split("\\R")) {
                String path = line.strip();
                if (!path.isBlank()) {
                    paths.add(path.replace('\\', '/'));
                }
            }
            return true;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static boolean isAutoProfile(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            return root != null
                    && root.isObject()
                    && "AUTO".equals(root.path("mode").asText("AUTO").strip().toUpperCase(Locale.ROOT));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static List<String> profileRegressionCommands(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json == null ? "" : json);
            return root == null ? List.of() : strings(root.path("regressionCommands"));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static QaExecutionProfile withRegressionCommands(
            QaExecutionProfile profile,
            List<String> additionalCommands
    ) {
        LinkedHashSet<String> commands = new LinkedHashSet<>(profile.regressionCommands());
        if (additionalCommands != null) {
            commands.addAll(additionalCommands);
        }
        return new QaExecutionProfile(
                profile.browserRequired(),
                profile.ambiguous(),
                profile.decisionSource(),
                profile.baseUrl(),
                profile.startCommand(),
                profile.healthPath(),
                profile.allowedHosts(),
                List.copyOf(commands),
                profile.reason()
        );
    }

    private static List<String> hostsFor(String baseUrl) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        try {
            URI uri = new URI(baseUrl);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                hosts.add(uri.getHost());
            }
        } catch (URISyntaxException ignored) {
            return List.of();
        }
        if (hosts.contains("127.0.0.1") || hosts.contains("localhost")) {
            hosts.add("127.0.0.1");
            hosts.add("localhost");
        }
        return List.copyOf(hosts);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("").strip();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static String browserModeJson(String mode) {
        if (mode == null || mode.isBlank()) {
            return "";
        }
        return "{\"mode\":\"" + mode.replace("\"", "") + "\"}";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
