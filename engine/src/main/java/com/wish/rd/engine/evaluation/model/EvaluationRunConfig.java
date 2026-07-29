package com.wish.rd.engine.evaluation.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe, structured configuration submitted by the evaluation management page. */
public record EvaluationRunConfig(
        String name,
        String datasetId,
        EvaluationSource source,
        String environmentId,
        int sampleLimit,
        String baseUrl,
        String ragLogPath,
        int timeoutSeconds,
        EvaluationJudgeProvider judgeProvider,
        int judgeLimit,
        boolean strictMissingRecords,
        String baselineRunId,
        String taskId,
        EvaluationMode mode
) {
    private static final Pattern DATASET_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}\\.jsonl");
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{0,120}");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    public EvaluationRunConfig {
        name = safe(name);
        datasetId = safe(datasetId);
        source = source == null ? EvaluationSource.FIXTURE : source;
        environmentId = safe(environmentId);
        baseUrl = safe(baseUrl);
        ragLogPath = safe(ragLogPath);
        judgeProvider = judgeProvider == null ? EvaluationJudgeProvider.NONE : judgeProvider;
        baselineRunId = safe(baselineRunId);
        taskId = safe(taskId);
        mode = mode == null
                ? (source == EvaluationSource.TASK_RUN ? EvaluationMode.TASK_AUDIT : EvaluationMode.LEGACY_QUALITY)
                : mode;
    }

    /** Backward-compatible constructor for configurations that already include task-run fields. */
    public EvaluationRunConfig(
            String name,
            String datasetId,
            EvaluationSource source,
            String environmentId,
            int sampleLimit,
            String baseUrl,
            String ragLogPath,
            int timeoutSeconds,
            EvaluationJudgeProvider judgeProvider,
            int judgeLimit,
            boolean strictMissingRecords,
            String baselineRunId,
            String taskId
    ) {
        this(name, datasetId, source, environmentId, sampleLimit, baseUrl, ragLogPath, timeoutSeconds,
                judgeProvider, judgeLimit, strictMissingRecords, baselineRunId, taskId, null);
    }

    /** Backward-compatible constructor for persisted and test configurations created before task-run evaluation. */
    public EvaluationRunConfig(
            String name,
            String datasetId,
            EvaluationSource source,
            String environmentId,
            int sampleLimit,
            String baseUrl,
            String ragLogPath,
            int timeoutSeconds,
            EvaluationJudgeProvider judgeProvider,
            int judgeLimit,
            boolean strictMissingRecords,
            String baselineRunId
    ) {
        this(name, datasetId, source, environmentId, sampleLimit, baseUrl, ragLogPath, timeoutSeconds,
                judgeProvider, judgeLimit, strictMissingRecords, baselineRunId, "", null);
    }

    /** Validates all Web-controlled values before a run or process is created. */
    public void validate() {
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("evaluation name must contain 1-120 characters");
        }
        if (!DATASET_PATTERN.matcher(datasetId).matches()) {
            throw new IllegalArgumentException("dataset must be an allowlisted JSONL file name");
        }
        if (source == EvaluationSource.TASK_RUN) {
            if (!"task-run.generated.jsonl".equals(datasetId)) {
                throw new IllegalArgumentException("task-run evaluation must use the server-generated dataset");
            }
            if (!taskId.matches("[0-9]{1,64}")) {
                throw new IllegalArgumentException("task-run evaluation requires a numeric task id");
            }
        } else if (!taskId.isBlank()) {
            throw new IllegalArgumentException("task id is only supported by task-run evaluation");
        }
        if (!LABEL_PATTERN.matcher(environmentId).matches()) {
            throw new IllegalArgumentException("environment id contains unsupported characters");
        }
        if (sampleLimit < 0 || sampleLimit > 10_000) {
            throw new IllegalArgumentException("sample limit must be between 0 and 10000");
        }
        if (judgeLimit < 0 || judgeLimit > 10_000) {
            throw new IllegalArgumentException("judge limit must be between 0 and 10000");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 3_600) {
            throw new IllegalArgumentException("timeout seconds must be between 1 and 3600");
        }
        validateRelativePath(ragLogPath, "RAG log path");
        if (source == EvaluationSource.RAG_HTTP) {
            validateLoopbackUrl(baseUrl);
        }
        if (!baselineRunId.isBlank() && !baselineRunId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("baseline run id contains unsupported characters");
        }
    }

    private static void validateRelativePath(String value, String field) {
        if (value.isBlank()) {
            return;
        }
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("..") || value.contains("\\")) {
            throw new IllegalArgumentException(field + " must be a safe relative path");
        }
    }

    private static void validateLoopbackUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
            String host = safe(uri.getHost()).toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || !LOOPBACK_HOSTS.contains(host)) {
                throw new IllegalArgumentException("RAG base URL must use HTTP(S) on a loopback host");
            }
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("RAG base URL must not contain user info or a fragment");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("RAG base URL is invalid", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
