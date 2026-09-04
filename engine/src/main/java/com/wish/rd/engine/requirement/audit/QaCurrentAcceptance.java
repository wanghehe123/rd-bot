package com.wish.rd.engine.requirement.audit;

import java.util.List;

/**
 * One CURRENT QA acceptance result used by {@link DeterministicAuditor}.
 *
 * @param criteriaId frozen {@code AC-*} id; blank for legacy
 * @param criteriaText acceptance text for legacy matching
 * @param status {@code PASSED} / {@code FAILED} / {@code SKIPPED}
 * @param exitCode process exit code
 * @param evidenceRefs persisted QA evidence
 */
public record QaCurrentAcceptance(
        String criteriaId,
        String criteriaText,
        String status,
        int exitCode,
        List<EvidenceRef> evidenceRefs
) {
    public QaCurrentAcceptance {
        criteriaId = criteriaId == null ? "" : criteriaId.strip();
        criteriaText = criteriaText == null ? "" : criteriaText.strip();
        status = status == null ? "" : status.strip();
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
