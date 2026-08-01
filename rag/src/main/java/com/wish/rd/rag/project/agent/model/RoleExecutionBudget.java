package com.wish.rd.rag.project.agent.model;

/** Token budget audit fields frozen with the role execution input manifest. */
public record RoleExecutionBudget(
    String model,
    long maxContextTokens,
    long reservedOutputTokens,
    long estimatedInputTokens,
    String estimatorVersion
) {

  public RoleExecutionBudget {
    model = model == null ? "" : model.strip();
    maxContextTokens = Math.max(0L, maxContextTokens);
    reservedOutputTokens = Math.max(0L, reservedOutputTokens);
    estimatedInputTokens = Math.max(0L, estimatedInputTokens);
    estimatorVersion = estimatorVersion == null || estimatorVersion.isBlank()
        ? "chars/4-v1"
        : estimatorVersion.strip();
  }
}
