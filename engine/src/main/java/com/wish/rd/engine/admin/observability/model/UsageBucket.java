package com.wish.rd.engine.admin.observability.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Deduplicated Provider usage for one allowlisted runtime/provider/role tuple.
 *
 * @param runtime allowlisted runtime
 * @param provider allowlisted provider or {@code OTHER}
 * @param role allowlisted role
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 * @param cacheTokens cache-read tokens
 * @param estimatedCostCny estimated CNY, not a bill; {@code NaN} when unavailable
 * @param ttft first-token histogram
 * @param firstResponse first Provider response histogram
 */
public record UsageBucket(
        String runtime,
        String provider,
        String role,
        long inputTokens,
        long outputTokens,
        long cacheTokens,
        double estimatedCostCny,
        @JsonIgnore DurationHistogram ttft,
        @JsonIgnore DurationHistogram firstResponse
) {

    public UsageBucket {
        runtime = runtime == null || runtime.isBlank() ? "unknown" : runtime.strip();
        provider = provider == null || provider.isBlank() ? "OTHER" : provider.strip();
        role = role == null || role.isBlank() ? "CODING_AGENT" : role.strip();
        inputTokens = Math.max(0L, inputTokens);
        outputTokens = Math.max(0L, outputTokens);
        cacheTokens = Math.max(0L, cacheTokens);
        ttft = ttft == null ? DurationHistogram.empty() : ttft;
        firstResponse = firstResponse == null ? DurationHistogram.empty() : firstResponse;
    }

    /**
     * @return true when an estimated CNY figure was observed
     */
    public boolean costAvailable() {
        return !Double.isNaN(estimatedCostCny);
    }
}
