package com.wish.rd.engine.project.memory;

import java.util.List;

/** Batch inventory summary for one project. */
public record LegacyExperienceInventoryResult(
        String projectId,
        int examined,
        int eligible,
        int duplicate,
        int ambiguous,
        int rejected,
        int candidatesCreated,
        List<LegacyExperienceInventoryItem> items
) {
    public LegacyExperienceInventoryResult {
        projectId = projectId == null ? "" : projectId.strip();
        examined = Math.max(0, examined);
        eligible = Math.max(0, eligible);
        duplicate = Math.max(0, duplicate);
        ambiguous = Math.max(0, ambiguous);
        rejected = Math.max(0, rejected);
        candidatesCreated = Math.max(0, candidatesCreated);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
