package com.wish.rd.exec.repair.runtime.usage.model;

import java.math.BigDecimal;

/**
 * Versioned redacted runtime measurement for one settled attempt.
 *
 * <p>Consumed by host result recovery, {@code providerAttemptsJson} compatibility
 * fields, and delivery observability aggregation. Never carries prompt or raw
 * private events.
 *
 * @param schemaVersion protocol id
 * @param taskId task id
 * @param stageRunId stage run id
 * @param role allowlisted role
 * @param runtime allowlisted runtime
 * @param provider allowlisted provider id
 * @param modelAlias model alias, never a Prometheus label
 * @param agentStartedAtEpochMillis agent-start or 0
 * @param firstProviderRespondedAtEpochMillis first PROVIDER_RESPONDED or 0
 * @param firstTextAtEpochMillis first non-thinking assistant text or 0
 * @param finishedAtEpochMillis AGENT_SETTLED/RUNTIME_STOPPED or 0
 * @param totalDurationMillis start→finish when both exist
 * @param firstTokenDurationMillis start→first text when trustworthy
 * @param firstProviderResponseDurationMillis start→PROVIDER_RESPONDED
 * @param toolDurationMillis summed tool intervals
 * @param firstTokenAvailable whether TTFT was observed
 * @param firstProviderResponseAvailable whether provider response timing exists
 * @param usageAvailable whether Token/cost was observed
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 * @param cacheReadTokens cache-read tokens
 * @param cacheWriteTokens cache-write tokens
 * @param totalTokens summed tokens
 * @param estimatedCostUsd estimated USD, not a bill
 * @param estimatedCostCurrency currency of estimatedCostUsd
 * @param parseErrorCategory allowlisted parse issue or blank
 * @param usageEventCount distinct usage observations
 * @param providerRetryCount PROVIDER_RETRYING events
 * @param droppedObservations duplicate or invalid lines
 * @param finalized whether the attempt settled
 * @param available whether any valid protocol event was parsed
 */
public record AgentRuntimeMeasurementSummary(
        String schemaVersion,
        String taskId,
        String stageRunId,
        String role,
        String runtime,
        String provider,
        String modelAlias,
        long agentStartedAtEpochMillis,
        long firstProviderRespondedAtEpochMillis,
        long firstTextAtEpochMillis,
        long finishedAtEpochMillis,
        long totalDurationMillis,
        long firstTokenDurationMillis,
        long firstProviderResponseDurationMillis,
        long toolDurationMillis,
        boolean firstTokenAvailable,
        boolean firstProviderResponseAvailable,
        boolean usageAvailable,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        String estimatedCostCurrency,
        String parseErrorCategory,
        int usageEventCount,
        int providerRetryCount,
        long droppedObservations,
        boolean finalized,
        boolean available
) {

    /** Current measurement schema. */
    public static final String SCHEMA_VERSION = "rd-runtime-measurement/v1";

    /**
     * @return an unavailable empty summary
     */
    public static AgentRuntimeMeasurementSummary unavailable(String parseErrorCategory) {
        return new AgentRuntimeMeasurementSummary(
                SCHEMA_VERSION, "", "", "", "unknown", "OTHER", "",
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                false, false, false,
                0L, 0L, 0L, 0L, 0L,
                BigDecimal.ZERO, "USD",
                parseErrorCategory == null ? "" : parseErrorCategory.strip(),
                0, 0, 0L, false, false
        );
    }

    public AgentRuntimeMeasurementSummary {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.strip();
        taskId = safe(taskId);
        stageRunId = safe(stageRunId);
        role = safe(role);
        runtime = normalizeRuntime(runtime);
        provider = safe(provider).isBlank() ? "OTHER" : provider.strip();
        modelAlias = safe(modelAlias);
        estimatedCostUsd = estimatedCostUsd == null || estimatedCostUsd.signum() < 0
                ? BigDecimal.ZERO : estimatedCostUsd;
        estimatedCostCurrency = safe(estimatedCostCurrency).isBlank() ? "USD" : estimatedCostCurrency.strip();
        parseErrorCategory = safe(parseErrorCategory);
        usageEventCount = Math.max(0, usageEventCount);
        providerRetryCount = Math.max(0, providerRetryCount);
        droppedObservations = Math.max(0L, droppedObservations);
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cacheReadTokens = Math.max(0L, cacheReadTokens);
        cacheWriteTokens = Math.max(0L, cacheWriteTokens);
        totalTokens = Math.max(0L, totalTokens);
        totalDurationMillis = Math.max(0L, totalDurationMillis);
        firstTokenDurationMillis = Math.max(0L, firstTokenDurationMillis);
        firstProviderResponseDurationMillis = Math.max(0L, firstProviderResponseDurationMillis);
        toolDurationMillis = Math.max(0L, toolDurationMillis);
        agentStartedAtEpochMillis = Math.max(0L, agentStartedAtEpochMillis);
        firstProviderRespondedAtEpochMillis = Math.max(0L, firstProviderRespondedAtEpochMillis);
        firstTextAtEpochMillis = Math.max(0L, firstTextAtEpochMillis);
        finishedAtEpochMillis = Math.max(0L, finishedAtEpochMillis);
        if (!firstTokenAvailable) {
            firstTokenDurationMillis = 0L;
        }
        if (!firstProviderResponseAvailable) {
            firstProviderResponseDurationMillis = 0L;
        }
        if (!usageAvailable) {
            inputTokens = 0L;
            outputTokens = 0L;
            cacheReadTokens = 0L;
            cacheWriteTokens = 0L;
            totalTokens = 0L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeRuntime(String runtime) {
        String value = safe(runtime).toLowerCase();
        if ("pi".equals(value) || "claude-compat".equals(value) || "unknown".equals(value)) {
            return value;
        }
        if (value.contains("claude")) {
            return "claude-compat";
        }
        if (value.contains("pi")) {
            return "pi";
        }
        return value.isBlank() ? "unknown" : "unknown";
    }
}
