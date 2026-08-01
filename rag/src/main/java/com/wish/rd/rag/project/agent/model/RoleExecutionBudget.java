package com.wish.rd.rag.project.agent.model;

/** Token budget audit fields frozen with the role execution input manifest. */
public record RoleExecutionBudget(
    String model,
    long maxContextTokens,
    long reservedOutputTokens,
    long estimatedInputTokens,
    String estimatorVersion
) {

  public static final String UNAVAILABLE_MODEL = "unavailable";
  public static final long UNAVAILABLE_TOKENS = -1L;

  public RoleExecutionBudget {
    model = model == null ? "" : model.strip();
    maxContextTokens = normalizeTokenBudget(maxContextTokens);
    reservedOutputTokens = normalizeTokenBudget(reservedOutputTokens);
    estimatedInputTokens = Math.max(0L, estimatedInputTokens);
    estimatorVersion = estimatorVersion == null || estimatorVersion.isBlank()
        ? "chars/4-v1"
        : estimatorVersion.strip();
  }

  public boolean modelAvailable() {
    return !model.isBlank() && !UNAVAILABLE_MODEL.equalsIgnoreCase(model);
  }

  public boolean contextBudgetAvailable() {
    return maxContextTokens >= 0L;
  }

  public boolean reservedOutputAvailable() {
    return reservedOutputTokens >= 0L;
  }

  private static long normalizeTokenBudget(long value) {
    return value < 0L ? UNAVAILABLE_TOKENS : value;
  }
}
