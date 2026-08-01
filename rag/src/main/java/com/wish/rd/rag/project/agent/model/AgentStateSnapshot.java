package com.wish.rd.rag.project.agent.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projected agent state snapshot for prompt injection and audit. */
public record AgentStateSnapshot(
        String protocol,
        long sequence,
        String generatedAt,
        String taskId,
        String stageRunId,
        String role,
        int attemptNo,
        String repoRoot,
        String cwd,
        String branch,
        String revision,
        String runtime,
        List<String> allowedOperations,
        List<String> deniedOperations,
        List<AgentTodoItem> todos,
        List<String> verifiedFactSummaries,
        Map<String, Integer> toolCallCounts,
        String recentErrorFingerprint,
        int consecutiveRepeatCount,
        String resultStatus,
        String blocker,
        int inputBudgetTokens,
        int reservedOutputTokens,
        int consumedInputTokens,
        List<AgentStateCapability> capabilities
) {

    public static final String PROTOCOL = "rd-agent-state/v1";

    public AgentStateSnapshot {
        protocol = protocol == null || protocol.isBlank() ? PROTOCOL : protocol.strip();
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        generatedAt = requireText(generatedAt, "generatedAt");
        taskId = requireText(taskId, "taskId");
        stageRunId = requireText(stageRunId, "stageRunId");
        role = requireText(role, "role");
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        allowedOperations = copyList(allowedOperations);
        deniedOperations = copyList(deniedOperations);
        todos = todos == null ? List.of() : List.copyOf(todos);
        verifiedFactSummaries = copyList(verifiedFactSummaries);
        toolCallCounts = toolCallCounts == null ? Map.of() : Map.copyOf(toolCallCounts);
        consecutiveRepeatCount = Math.max(0, consecutiveRepeatCount);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
