package com.wish.rd.exec.repair.security;

import com.wish.rd.exec.repair.execution.RepairJobCommand;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Execution target allowlist used before starting a repair container.
 *
 * @param enabled        whether enforcement is enabled
 * @param repositoryUrls allowed repository URL patterns
 * @param repositories   allowed {@code owner/name} patterns
 * @param baseBranches   allowed base branch patterns
 * @param workBranches   allowed work branch patterns
 */
public record ExecutionAllowlistPolicy(
        boolean enabled,
        List<String> repositoryUrls,
        List<String> repositories,
        List<String> baseBranches,
        List<String> workBranches
) {

    public ExecutionAllowlistPolicy {
        repositoryUrls = normalizePatterns(repositoryUrls);
        repositories = normalizePatterns(repositories);
        baseBranches = normalizePatterns(baseBranches);
        workBranches = normalizePatterns(workBranches);
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
        if (!repositoryUrls.isEmpty() && !matchesAny(repositoryUrls, command.repositoryUrl(), true)) {
            return Decision.reject("repositoryUrl is not allowlisted");
        }
        if (!repositories.isEmpty() && !matchesAny(repositories, repositoryName(command), true)) {
            return Decision.reject("repository is not allowlisted");
        }
        if (!baseBranches.isEmpty() && !matchesAny(baseBranches, command.baseBranch(), false)) {
            return Decision.reject("baseBranch is not allowlisted");
        }
        if (!workBranches.isEmpty() && !matchesAny(workBranches, command.workBranch(), false)) {
            return Decision.reject("workBranch is not allowlisted");
        }
        return Decision.allow();
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
