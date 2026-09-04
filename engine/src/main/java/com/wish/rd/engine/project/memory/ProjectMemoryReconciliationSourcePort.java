package com.wish.rd.engine.project.memory;

import java.util.List;

/** Lists terminal stage evidence that should have a durable memory operation. */
public interface ProjectMemoryReconciliationSourcePort {
    List<ProjectMemoryReconciliationCandidate> listTerminalEvidenceMissingOperation(int limit);
}
