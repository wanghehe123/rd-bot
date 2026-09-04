package com.wish.rd.engine.requirement.audit;

import java.util.ArrayList;
import java.util.List;

/** Host completion gate over an audited-state head. */
public final class AuditedCompletionGate {

    private final EvidenceRefResolverPort resolver;

    public AuditedCompletionGate(EvidenceRefResolverPort resolver) {
        this.resolver = resolver;
    }

    /**
     * Evaluates whether the head supports writing {@code COMPLETED}.
     *
     * @param head current audited state
     * @param latestRun most recent audit run
     * @param mode gate mode
     * @return decision
     */
    public CompletionGateDecision evaluate(
            AuditedTaskState head,
            AuditRun latestRun,
            AuditedWritebackGateMode mode
    ) {
        if (head == null) {
            throw new IllegalArgumentException("head must not be null");
        }
        if (latestRun == null) {
            throw new IllegalArgumentException("latestRun must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("gate mode must not be null");
        }
        List<String> gaps = new ArrayList<>();
        if (latestRun.integrity() != AuditIntegrity.CLEAN) {
            gaps.add("integrity:" + latestRun.integrity().name());
        }
        if (latestRun.contractAudit() != ContractAuditVerdict.ALIGNED) {
            gaps.add("contractAudit:" + latestRun.contractAudit().name());
        }
        for (AuditedRecord record : head.records()) {
            if (!record.blocking()) {
                continue;
            }
            if (record.status() == AuditedRecordStatus.BLOCKED
                    || record.status() != AuditedRecordStatus.COMPLETED) {
                gaps.add(record.id());
                continue;
            }
            if (record.evidenceRefs().isEmpty()) {
                gaps.add(record.id());
                continue;
            }
            try {
                record.evidenceRefs().forEach(resolver::requireResolvable);
            } catch (RuntimeException dangling) {
                gaps.add(record.id());
            }
        }
        boolean wouldReject = !gaps.isEmpty();
        List<String> capped = gaps.size() > CompletionGateDecision.MAX_GAPS
                ? gaps.subList(0, CompletionGateDecision.MAX_GAPS)
                : gaps;
        boolean enforce = mode == AuditedWritebackGateMode.ENFORCE;
        return new CompletionGateDecision(!enforce || !wouldReject, capped, wouldReject);
    }
}
