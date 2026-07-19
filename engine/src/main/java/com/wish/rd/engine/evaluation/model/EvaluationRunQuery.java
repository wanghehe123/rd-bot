package com.wish.rd.engine.evaluation.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalized, server-owned filters for an evaluation history query. */
public record EvaluationRunQuery(
        String keyword,
        EvaluationSource source,
        List<String> datasetIds,
        boolean datasetFilterApplied,
        EvaluationRunStatus status,
        String gateStatus,
        String judgeStatus,
        List<String> nonSmokeDatasetIds,
        int page,
        int pageSize
) {
    private static final int MAX_KEYWORD_LENGTH = 200;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> GATE_STATUSES = Set.of("PASSED", "NOT_PASSED", "INCOMPLETE", "PENDING");
    private static final Set<String> JUDGE_STATUSES = Set.of(
            "AVAILABLE", "FAILED", "PARTIAL", "SKIPPED", "NOT_REQUESTED", "PENDING");
    private static final Pattern GATE_STATUS_PATTERN = Pattern.compile("\\\"gateStatus\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern JUDGE_STATUS_PATTERN = Pattern.compile("\\\"judgeStatus\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern NESTED_JUDGE_STATUS_PATTERN = Pattern.compile(
            "\\\"judge\\\"\\s*:\\s*\\{.*?\\\"status\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.DOTALL);

    public EvaluationRunQuery {
        keyword = normalizeKeyword(keyword);
        datasetIds = normalizeIds(datasetIds);
        gateStatus = normalizeStatus(gateStatus, GATE_STATUSES, "gate status");
        judgeStatus = normalizeStatus(judgeStatus, JUDGE_STATUSES, "judge status");
        nonSmokeDatasetIds = normalizeIds(nonSmokeDatasetIds);
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    /** Zero-based offset used by PostgreSQL LIMIT/OFFSET queries. */
    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    /** JavaBean bridge for MyBatis dynamic SQL expression evaluation. */
    public boolean isDatasetFilterApplied() {
        return datasetFilterApplied;
    }

    /*
     * MyBatis evaluates nested values as JavaBean properties in dynamic SQL.
     * Records expose component accessors, but these bridges keep the mapper
     * compatible with the project's current MyBatis/OGNL runtime as well.
     */
    public String getKeyword() {
        return keyword;
    }

    public EvaluationSource getSource() {
        return source;
    }

    public List<String> getDatasetIds() {
        return datasetIds;
    }

    public EvaluationRunStatus getStatus() {
        return status;
    }

    public String getGateStatus() {
        return gateStatus;
    }

    public String getJudgeStatus() {
        return judgeStatus;
    }

    public List<String> getNonSmokeDatasetIds() {
        return nonSmokeDatasetIds;
    }

    /** Applies the same semantic filters in explicit in-memory test mode. */
    public boolean matches(EvaluationRun run) {
        if (run == null) {
            return false;
        }
        if (!keyword.isBlank() && !searchText(run).contains(keyword)) {
            return false;
        }
        if (source != null && run.config().source() != source) {
            return false;
        }
        if (datasetFilterApplied && !datasetIds.contains(run.config().datasetId())) {
            return false;
        }
        if (status != null && run.status() != status) {
            return false;
        }
        return matchesDerivedStatus(gateStatus, gateStatusOf(run))
                && matchesDerivedStatus(judgeStatus, judgeStatusOf(run));
    }

    /** Derives the gate state for both paged PostgreSQL data and memory-mode tests. */
    public static String gateStatusOf(EvaluationRun run) {
        String explicit = jsonValue(run.metricsJson(), GATE_STATUS_PATTERN);
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (run.status() == EvaluationRunStatus.SUCCEEDED) {
            return run.overallPassed() ? "PASSED" : "NOT_PASSED";
        }
        return "";
    }

    /** Derives the Judge state for both paged PostgreSQL data and memory-mode tests. */
    public static String judgeStatusOf(EvaluationRun run) {
        String explicit = jsonValue(run.metricsJson(), JUDGE_STATUS_PATTERN);
        if (explicit.isBlank()) {
            explicit = jsonValue(run.metricsJson(), NESTED_JUDGE_STATUS_PATTERN);
        }
        if (!explicit.isBlank()) {
            return explicit;
        }
        return run.config().judgeProvider() == EvaluationJudgeProvider.NONE ? "NOT_REQUESTED" : "";
    }

    private static boolean matchesDerivedStatus(String requested, String actual) {
        if (requested.isBlank()) {
            return true;
        }
        return "PENDING".equals(requested) ? actual.isBlank() : requested.equals(actual);
    }

    private static String searchText(EvaluationRun run) {
        return String.join(" ",
                safe(run.name()),
                safe(run.runId()),
                safe(run.config().taskId()),
                safe(run.config().datasetId()),
                run.config().source().name()
        ).toLowerCase(Locale.ROOT);
    }

    private static String jsonValue(String metricsJson, Pattern pattern) {
        Matcher matcher = pattern.matcher(safe(metricsJson));
        return matcher.find() ? matcher.group(1).trim().toUpperCase(Locale.ROOT) : "";
    }

    private static String normalizeKeyword(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword must contain at most " + MAX_KEYWORD_LENGTH + " characters");
        }
        return normalized;
    }

    private static String normalizeStatus(String value, Set<String> allowed, String field) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || "ALL".equals(normalized)) {
            return "";
        }
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + normalized);
        }
        return normalized;
    }

    private static List<String> normalizeIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isBlank()) {
                unique.add(normalized);
            }
        }
        return List.copyOf(unique);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
