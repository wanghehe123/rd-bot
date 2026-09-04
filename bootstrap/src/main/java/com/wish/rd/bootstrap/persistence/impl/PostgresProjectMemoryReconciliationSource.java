package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.entity.ProjectMemoryReconciliationEvidenceRow;
import com.wish.rd.bootstrap.persistence.mapper.ProjectMemoryReconciliationEvidenceMapper;
import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationCandidate;
import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationDraftFactory;
import com.wish.rd.engine.project.memory.ProjectMemoryReconciliationSourcePort;
import com.wish.rd.engine.requirement.job.RequirementStageFinalizationPort.ProjectMemoryOperationDraft;
import com.wish.rd.rag.project.memory.ProjectMemoryOperationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** PostgreSQL-backed scan for finalized evidence missing deterministic memory operations. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProjectMemoryReconciliationSource implements ProjectMemoryReconciliationSourcePort {
    private final ProjectMemoryReconciliationEvidenceMapper evidenceMapper;
    private final ProjectMemoryOperationStore operationStore;

    public PostgresProjectMemoryReconciliationSource(
            ProjectMemoryReconciliationEvidenceMapper evidenceMapper,
            ProjectMemoryOperationStore operationStore
    ) {
        this.evidenceMapper = evidenceMapper;
        this.operationStore = operationStore;
    }

    @Override
    public List<ProjectMemoryReconciliationCandidate> listTerminalEvidenceMissingOperation(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ProjectMemoryReconciliationEvidenceRow> rows =
                evidenceMapper.listRecentFinalizedTerminalEvidence(limit);
        List<ProjectMemoryReconciliationCandidate> missing = new ArrayList<>();
        for (ProjectMemoryReconciliationEvidenceRow row : rows) {
            if (row.commandId == null || row.projectId == null || row.outcomePlanDigest == null) {
                continue;
            }
            ProjectMemoryOperationDraft draft = ProjectMemoryReconciliationDraftFactory.fromFinalizedCommand(
                    row.commandId.toString(),
                    row.projectId.strip(),
                    row.commandId.toString(),
                    row.outcomePlanDigest.strip(),
                    false);
            if (operationStore.findByKey(ProjectMemoryReconciliationDraftFactory.operationKey(draft)).isPresent()) {
                continue;
            }
            missing.add(new ProjectMemoryReconciliationCandidate(
                    "finalization:" + row.commandId,
                    draft));
            if (missing.size() >= limit) {
                break;
            }
        }
        return missing;
    }
}
