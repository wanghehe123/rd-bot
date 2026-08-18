package com.wish.rd.exec.repair.security.model;

import com.wish.rd.exec.repair.execution.model.RepairJobCommand;
import com.wish.rd.exec.repair.security.RegisteredRepositoryCatalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Execution target allowlist used before starting a repair container.
 *
 * <p>Each configured entry may itself contain multiple comma-separated
 * patterns, so a single environment variable can allowlist several
 * repositories or branches at once.</p>
 *
 * @param enabled                 whether enforcement is enabled
 * @param repositoryUrls          allowed repository URL patterns
 * @param repositories            allowed {@code owner/name} patterns
 * @param baseBranches            allowed base branch patterns
 * @param workBranches            allowed work branch patterns
 * @param registeredRepositories  repositories already saved as RD-Bot projects
 */
public record ExecutionAllowlistPolicy(
        boolean enabled,
        List<String> repositoryUrls,
        List<String> repositories,
        List<String> baseBranches,
        List<String> workBranches,
        RegisteredRepositoryCatalog registeredRepositories
) {

    public ExecutionAllowlistPolicy(
            boolean enabled,
            List<String> repositoryUrls,
            List<String> repositories,
            List<String> baseBranches,
            List<String> workBranches
    ) {
        this(enabled, repositoryUrls, repositories, baseBranches, workBranches, RegisteredRepositoryCatalog.none());
    }

    public ExecutionAllowlistPolicy {
        repositoryUrls = normalizePatterns(repositoryUrls);
        repositories = normalizePatterns(repositories);
        baseBranches = normalizePatterns(baseBranches);
        workBranches = normalizePatterns(workBranches);
        registeredRepositories = registeredRepositories == null
                ? RegisteredRepositoryCatalog.none()
                : registeredRepositories;
    }

    public ExecutionAllowlistPolicy withRegisteredRepositories(RegisteredRepositoryCatalog catalog) {
        return new ExecutionAllowlistPolicy(
                enabled,
                repositoryUrls,
                repositories,
                baseBranches,
                workBranches,
                catalog
        );
    }

    /**
     * Returns a permissive policy for local and test execution.
     *
     * @return disabled policy
     */
    public static ExecutionAllowlistPolicy disabled() {
        return new ExecutionAllowlistPolicy(false, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Evaluates a repair command against configured allowlists.
     *
     * @param command repair command
     * @return allow or reject decision
     */
    public Decision evaluate(RepairJobCommand command) {
        if (!enabled) {
            return Decision.allow();
        }
        if (command == null) {
            return Decision.reject("repair command is missing");
        }
        boolean hasAnyRule = !repositoryUrls.isEmpty()
                || !repositories.isEmpty()
                || !baseBranches.isEmpty()
                || !workBranches.isEmpty();
        if (!hasAnyRule) {
            return Decision.reject("execution allowlist has no rules");
        }
        boolean registeredProject = isRegisteredProject(command);
        if (!repositoryUrls.isEmpty()
                && !registeredProject
                && !matchesAnyRepositoryUrl(repositoryUrls, command.repositoryUrl())) {
            return Decision.reject("repositoryUrl is not allowlisted: " + normalize(command.repositoryUrl()));
        }
        if (!repositories.isEmpty()
                && !registeredProject
                && !matchesAny(repositories, repositoryName(command), true)) {
            return Decision.reject("repository is not allowlisted: " + repositoryName(command));
        }
        if (!baseBranches.isEmpty() && !matchesAny(baseBranches, command.baseBranch(), false)) {
            return Decision.reject("baseBranch is not allowlisted");
        }
        if (!workBranches.isEmpty() && !matchesAny(workBranches, command.workBranch(), false)) {
            return Decision.reject("workBranch is not allowlisted");
        }
        return Decision.allow();
    }

    private boolean isRegisteredProject(RepairJobCommand command) {
        return registeredRepositories.contains(command.repositoryUrl(), repositoryName(command));
    }

    private static String repositoryName(RepairJobCommand command) {
        String owner = normalize(command.repoOwner());
        String name = normalize(command.repoName());
        if (owner.isBlank()) {
            return name;
        }
        if (name.isBlank()) {
            return owner;
        }
        return owner + "/" + name;
    }

    private static boolean matchesAny(List<String> patterns, String value, boolean caseInsensitive) {
        String normalizedValue = normalize(value);
        if (caseInsensitive) {
            normalizedValue = normalizedValue.toLowerCase(Locale.ROOT);
        }
        for (String pattern : patterns) {
            String normalizedPattern = caseInsensitive ? pattern.toLowerCase(Locale.ROOT) : pattern;
            if (Pattern.matches(globRegex(normalizedPattern), normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyRepositoryUrl(List<String> patterns, String value) {
        String normalizedValue = normalizeRepositoryUrl(value).toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String normalizedPattern = normalizeRepositoryUrl(pattern).toLowerCase(Locale.ROOT);
            if (Pattern.matches(globRegex(normalizedPattern), normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRepositoryUrl(String value) {
        String normalized = trimTrailingSlashes(normalize(value));
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - ".git".length());
        }
        return trimTrailingSlashes(normalized);
    }

    private static String trimTrailingSlashes(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String globRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.append('$').toString();
    }

    private static List<String> normalizePatterns(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .flatMap(value -> Arrays.stream(normalize(value).split(",")))
                .map(ExecutionAllowlistPolicy::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Evaluation result.
     *
     * @param allowed whether execution can continue
     * @param reason  rejection reason when denied
     */
    public record Decision(boolean allowed, String reason) {

        public Decision {
            reason = reason == null ? "" : reason;
        }

        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }
}
