package com.wish.rd.exec.repair.runtime.usage.model;

import java.math.BigDecimal;

/** Aggregated Pi normalized agent-event usage for one container execution. */
public record AgentEventTokenUsageSnapshot(
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    long totalTokens,
    BigDecimal estimatedCostUsd,
    int usageEventCount,
    boolean finalized,
    boolean available
) {

  public AgentEventTokenUsageSnapshot {
    inputTokens = nonNegative(inputTokens);
    outputTokens = nonNegative(outputTokens);
    cacheReadTokens = nonNegative(cacheReadTokens);
    cacheWriteTokens = nonNegative(cacheWriteTokens);
    totalTokens = nonNegative(totalTokens);
    estimatedCostUsd = estimatedCostUsd == null || estimatedCostUsd.signum() < 0
        ? BigDecimal.ZERO
        : estimatedCostUsd;
    usageEventCount = Math.max(0, usageEventCount);
  }

  private static long nonNegative(long value) {
    return Math.max(0L, value);
  }
}
