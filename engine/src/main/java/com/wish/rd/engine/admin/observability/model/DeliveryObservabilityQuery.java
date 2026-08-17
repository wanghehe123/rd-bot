package com.wish.rd.engine.admin.observability.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable delivery observability query. Omitted {@code projectId} means all projects.
 *
 * <p>Callers must not send a display sentinel such as {@code all}.
 */
public record DeliveryObservabilityQuery(
        String projectId,
        DeliveryObservabilityWindow window,
        Instant now,
        String role,
        String runtime,
        String provider,
        String failureCategory,
        int page,
        int pageSize
) {

    private static final Set<String> ROLES = Set.of(
            "REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT");
    private static final Set<String> RUNTIMES = Set.of("pi", "claude-compat", "unknown");
    private static final Set<String> FAILURES = Set.of(
            "TIMEOUT", "PROVIDER", "VALIDATION", "BUDGET", "LEASE", "CANCELLED",
            "SCOPE_VIOLATION", "UNKNOWN");

    public DeliveryObservabilityQuery {
        projectId = normalizeOptional(projectId);
        if ("all".equalsIgnoreCase(projectId)) {
            throw new IllegalArgumentException("projectId all is not a real project id");
        }
        window = window == null ? DeliveryObservabilityWindow.ONE_DAY : window;
        now = Objects.requireNonNull(now, "now must not be null");
        role = normalizeFilter(role, ROLES, "role");
        runtime = normalizeFilter(runtime, RUNTIMES, "runtime");
        provider = provider == null ? "" : provider.strip();
        failureCategory = normalizeFilter(failureCategory, FAILURES, "failureCategory");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
    }

    /**
     * Creates a default overview query for a project scope.
     *
     * @param projectId concrete project or blank for all
     * @param windowToken window token
     * @param now query clock
     * @return query
     */
    public static DeliveryObservabilityQuery overview(String projectId, String windowToken, Instant now) {
        return new DeliveryObservabilityQuery(
                projectId, DeliveryObservabilityWindow.parse(windowToken), now,
                "", "", "", "", 1, 20);
    }

    /**
     * @return true when every project is in scope
     */
    public boolean allProjects() {
        return projectId.isBlank();
    }

    /**
     * @return inclusive window start
     */
    public Instant windowStart() {
        return now.minus(window.duration());
    }

    /**
     * Rejects a clock that is unreasonably in the future relative to {@code clockNow}.
     *
     * @param clockNow trusted now
     * @param skew allowed skew
     */
    public void rejectFuture(Instant clockNow, Duration skew) {
        Instant limit = clockNow.plus(skew == null ? Duration.ZERO : skew);
        if (now.isAfter(limit)) {
            throw new IllegalArgumentException("query time must not be in the future");
        }
    }

    /**
     * Rejects unbounded pagination.
     *
     * @param maxPageSize configured cap
     */
    public void rejectUnboundedPage(int maxPageSize) {
        if (pageSize > maxPageSize) {
            throw new IllegalArgumentException("pageSize exceeds max " + maxPageSize);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeFilter(String value, Set<String> allowlist, String field) {
        String normalized = normalizeOptional(value);
        if (normalized.isBlank()) {
            return "";
        }
        String candidate = field.equals("runtime")
                ? normalized.toLowerCase(Locale.ROOT)
                : normalized.toUpperCase(Locale.ROOT);
        if (!allowlist.contains(candidate)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value);
        }
        return candidate;
    }
}
