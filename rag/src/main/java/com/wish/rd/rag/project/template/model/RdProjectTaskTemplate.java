package com.wish.rd.rag.project.template.model;

import java.util.List;

/** Persistent task template scoped by project and task type. */
public record RdProjectTaskTemplate(
        String projectId,
        String taskType,
        String name,
        String actualBehavior,
        String expectedBehavior,
        String reproductionSteps,
        String affectedScope,
        List<String> acceptanceCriteria,
        String requirementBody,
        String expectedResult,
        long createTimeEpochMillis,
        long updateTimeEpochMillis
) {
    public RdProjectTaskTemplate {
        projectId = requireText(projectId, "projectId");
        taskType = normalizeTaskType(taskType);
        name = safe(name);
        actualBehavior = safe(actualBehavior);
        expectedBehavior = safe(expectedBehavior);
        reproductionSteps = safe(reproductionSteps);
        affectedScope = safe(affectedScope);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        requirementBody = safe(requirementBody);
        expectedResult = safe(expectedResult);
    }

    public static String normalizeTaskType(String taskType) {
        String normalized = safe(taskType).toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("BUG_FIX") && !normalized.equals("REQUIREMENT")) {
            throw new IllegalArgumentException("unsupported taskType: " + taskType);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
