package com.wish.rd.exec.repair.docker.usage.model;

import java.math.BigDecimal;

/**
 * Aggregated Claude Code stream-json usage for a single container execution.
 */
public record ClaudeTokenUsageSnapshot(
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        long totalTokens,
        BigDecimal estimatedCostUsd,
        String sessionId,
        int uniqueAssistantMessages,
        boolean finalized
) {

    public ClaudeTokenUsageSnapshot {
        inputTokens = nonNegative(inputTokens);
        outputTokens = nonNegative(outputTokens);
        cacheCreationInputTokens = nonNegative(cacheCreationInputTokens);
        cacheReadInputTokens = nonNegative(cacheReadInputTokens);
        totalTokens = nonNegative(totalTokens);
        estimatedCostUsd = estimatedCostUsd == null || estimatedCostUsd.signum() < 0
                ? BigDecimal.ZERO
                : estimatedCostUsd;
        sessionId = sessionId == null ? "" : sessionId;
        uniqueAssistantMessages = Math.max(0, uniqueAssistantMessages);
    }

    public boolean available() {
        return uniqueAssistantMessages > 0 || finalized;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
