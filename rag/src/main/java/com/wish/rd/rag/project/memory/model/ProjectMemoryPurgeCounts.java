package com.wish.rd.rag.project.memory.model;

/** Row counts for project-scoped physical purge preview and execution. */
public record ProjectMemoryPurgeCounts(
        int memoryCount,
        int revisionCount,
        int sourceCount,
        int operationCount,
        int legacyLinkCount
) {
    public int totalRowCount() {
        return memoryCount + revisionCount + sourceCount + operationCount + legacyLinkCount;
    }
}
