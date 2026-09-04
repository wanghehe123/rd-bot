package com.wish.rd.exec.repair.qa;

import com.wish.rd.exec.repair.qa.model.QaExecutionProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host-side npm install plan for isolated Pi workspaces.
 *
 * <p>Pi QA and coding agents run on an {@code --internal} credential-relay
 * network and cannot reach package registries. The host therefore installs
 * into the role's Linux image (with egress) before the isolated agent starts,
 * so native addons such as {@code better-sqlite3} match the container ABI.
 */
public final class QaNpmInstallPlan {

    private static final List<String> PACKAGE_DIRECTORIES = List.of(
            ".",
            "server",
            "client",
            "frontend",
            "web",
            "ui"
    );

    private QaNpmInstallPlan() {
    }

    /**
     * Whether an isolated coding workspace needs a networked npm install.
     *
     * @param repository prepared coding repository root
     * @return {@code true} when at least one known package.json exists
     */
    public static boolean requiredForCoding(Path repository) {
        return !packageDirectories(repository).isEmpty();
    }

    /**
     * Whether browser QA needs a networked npm install before the isolated agent.
     *
     * @param repository prepared QA repository root
     * @param profile    resolved QA profile; {@code null} skips
     * @return {@code true} when browser QA is required and at least one package.json exists
     */
    public static boolean required(Path repository, QaExecutionProfile profile) {
        if (profile == null || !profile.browserRequired()) {
            return false;
        }
        if ("DOCS_ONLY".equalsIgnoreCase(profile.decisionSource())) {
            return false;
        }
        return !packageDirectories(repository).isEmpty();
    }

    /**
     * Relative package directories that contain {@code package.json}.
     *
     * @param repository repository root
     * @return relative directory names, {@code "."} for the root
     */
    public static List<String> packageDirectories(Path repository) {
        if (repository == null || !Files.isDirectory(repository)) {
            return List.of();
        }
        List<String> directories = new ArrayList<>();
        for (String relative : PACKAGE_DIRECTORIES) {
            Path directory = ".".equals(relative) ? repository : repository.resolve(relative);
            if (Files.isRegularFile(directory.resolve("package.json"))) {
                directories.add(relative);
            }
        }
        return List.copyOf(directories);
    }

    /**
     * Copy a host npm registry into the provision container environment.
     *
     * <p>Only the bridge provision container may use this. Isolated agents stay
     * {@code npm_config_offline=true} and must not inherit registry egress.
     *
     * @param environment destination container env
     * @param hostEnv     process environment, typically {@code System.getenv()}
     */
    public static void copyHostNpmRegistry(Map<String, String> environment, Map<String, String> hostEnv) {
        if (environment == null || hostEnv == null) {
            return;
        }
        String registry = firstNonBlank(hostEnv.get("npm_config_registry"), hostEnv.get("NPM_CONFIG_REGISTRY"));
        if (!registry.isBlank()) {
            environment.put("npm_config_registry", registry);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return "";
    }

    /**
     * POSIX script that installs every detected package tree.
     *
     * <p>Uses {@code npm ci} when a lockfile is present, otherwise {@code npm install}.
     * Always includes devDependencies because the QA image sets {@code NODE_ENV=production}.
     *
     * @param repository repository root used to choose ci vs install
     * @return shell script body for {@code sh -c}
     */
    public static String shellScript(Path repository) {
        List<String> directories = packageDirectories(repository);
        if (directories.isEmpty()) {
            return "true";
        }
        StringBuilder script = new StringBuilder();
        script.append("set -eu\n");
        for (String relative : directories) {
            Path directory = ".".equals(relative) ? repository : repository.resolve(relative);
            boolean locked = Files.isRegularFile(directory.resolve("package-lock.json"))
                    || Files.isRegularFile(directory.resolve("npm-shrinkwrap.json"));
            String command = locked
                    ? "npm ci --include=dev --no-audit --no-fund --prefer-offline"
                    : "npm install --include=dev --no-audit --no-fund --prefer-offline";
            script.append("( cd ").append(shellSingleQuote(relative)).append(" && ").append(command).append(" )\n");
        }
        return script.toString();
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
