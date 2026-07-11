package com.wish.rd.engine.draft.model;

import java.util.List;
import java.util.Map;

/** Safe task form fields and attachment summaries supplied to a draft model. */
public record TaskDraftRequest(
        String taskType,
        String projectId,
        Map<String, String> currentValues,
        List<String> materialSummaries
) {
    public TaskDraftRequest {
        taskType = taskType == null ? "" : taskType.strip().toUpperCase(java.util.Locale.ROOT);
        if (!taskType.equals("BUG_FIX") && !taskType.equals("REQUIREMENT")) {
            throw new IllegalArgumentException("unsupported taskType: " + taskType);
        }
        projectId = projectId == null ? "" : projectId.strip();
        currentValues = currentValues == null ? Map.of() : Map.copyOf(currentValues);
        materialSummaries = materialSummaries == null ? List.of() : List.copyOf(materialSummaries);
    }
}
