package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditedHandoffTrustTest {

    @Test
    void defaultsToUntrustedWhenHeadMissing() {
        assertEquals("UNTRUSTED", AuditedHandoffTrust.trustForFact(null, "OBSERVED", "jdk 21"));
    }

    @Test
    void marksCompletedFactVerifiedWithAuditRunId() {
        EvidenceRef evidence = new EvidenceRef(
                "audit-fact-9",
                EvidenceSourceKind.HOST_ASSERTION,
                "host-assertion://facts/jdk",
                "sha256:" + "a".repeat(64));
        AuditedTaskState head = new AuditedTaskState(
                "task-1",
                2L,
                "sha256:" + "d".repeat(64),
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                List.of(new AuditedRecord(
                        "CLAIM-review-1-1",
                        AuditedRecordKind.FACT,
                        false,
                        "OBSERVED:jdk 21 is available",
                        AuditedRecordStatus.COMPLETED,
                        List.of(evidence),
                        "review-1",
                        "")),
                "audit-fact-9");
        assertEquals(
                "VERIFIED(auditRunId=audit-fact-9)",
                AuditedHandoffTrust.trustForFact(head, "OBSERVED", "jdk 21 is available"));
    }

    @Test
    void leavesUnpromotedFactUntrusted() {
        AuditedTaskState head = new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                List.of(new AuditedRecord(
                        "CLAIM-review-1-1",
                        AuditedRecordKind.FACT,
                        false,
                        "OBSERVED:jdk 21 is available",
                        AuditedRecordStatus.UNTRUSTED,
                        List.of(),
                        "review-1",
                        "")),
                "");
        assertEquals("UNTRUSTED", AuditedHandoffTrust.trustForFact(head, "OBSERVED", "jdk 21 is available"));
    }
}
