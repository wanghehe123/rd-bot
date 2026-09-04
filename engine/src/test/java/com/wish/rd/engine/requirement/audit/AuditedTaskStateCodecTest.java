package com.wish.rd.engine.requirement.audit;

import com.wish.rd.engine.requirement.policy.CanonicalJsonSha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditedTaskStateCodecTest {

    private final AuditedTaskStateCodec codec = new AuditedTaskStateCodec();

    @Test
    void canonicalJsonIsStableSortedAndCompact() {
        AuditedTaskState sealed = codec.seal(unsigned());
        String first = codec.encodeCanonical(sealed);
        String second = codec.encodeCanonical(unsigned());
        assertEquals(first, second);
        assertEquals(first, CanonicalJsonSha256.canonicalize(first));
        assertFalse(first.contains("\n"));
        assertFalse(first.contains(" : "));
        assertTrue(first.indexOf("\"contractRef\"") < first.indexOf("\"lastAuditRunId\""));
        assertTrue(first.indexOf("\"records\"") < first.indexOf("\"stateVersion\""));
    }

    @Test
    void stateHashIsSha256OfCanonicalPayload() {
        AuditedTaskState sealed = codec.seal(unsigned());
        assertTrue(sealed.stateHash().startsWith("sha256:"));
        assertEquals(7 + 64, sealed.stateHash().length());
        assertEquals(sealed.stateHash(), CanonicalJsonSha256.digest(codec.canonicalPayload(sealed)));
    }

    @Test
    void roundTripDecodeEqualsSealedState() {
        AuditedTaskState sealed = codec.seal(unsigned());
        AuditedTaskState decoded = codec.decode(codec.encodeCanonical(sealed));
        assertEquals(sealed, decoded);
    }

    @Test
    void rejectsCanonicalJsonLargerThan256KiB() {
        String oversized = "x".repeat(AuditedTaskStateCodec.MAX_CANONICAL_BYTES);
        AuditedTaskState huge = new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                List.of(new AuditedRecord(
                        "AC-001",
                        AuditedRecordKind.REQUIREMENT,
                        true,
                        oversized,
                        AuditedRecordStatus.PENDING,
                        List.of(),
                        "",
                        "")),
                "");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codec.seal(huge));
        assertTrue(failure.getMessage().contains("256"));
        assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length
                > AuditedTaskStateCodec.MAX_CANONICAL_BYTES - 1024);
    }

    private static AuditedTaskState unsigned() {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.QA_EVIDENCE,
                "qa-evidence://objects/1",
                "sha256:" + "b".repeat(64));
        return new AuditedTaskState(
                "task-1",
                1L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                List.of(
                        new AuditedRecord(
                                "AC-001",
                                AuditedRecordKind.REQUIREMENT,
                                true,
                                "login works",
                                AuditedRecordStatus.COMPLETED,
                                List.of(evidence),
                                "",
                                ""),
                        new AuditedRecord(
                                "GATE-BUILD",
                                AuditedRecordKind.GATE,
                                true,
                                "build",
                                AuditedRecordStatus.PENDING,
                                List.of(),
                                "",
                                "")),
                "audit-1");
    }
}
