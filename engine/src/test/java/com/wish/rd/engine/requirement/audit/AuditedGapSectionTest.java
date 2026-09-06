package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditedGapSectionTest {

    private static final String JAVAC = "cannot find symbol Foo";

    @Test
    void nullHeadRendersEmpty() {
        assertEquals("", AuditedGapSection.render(null, lastRun(
                List.of("GATE-BUILD"), List.of(), List.of("CLAIM-1"), List.of("qa-evidence://objects/1"))));
    }

    @Test
    void pendingGateAndUntrustedClaimRenderHostGapWithoutRawFailureText() {
        EvidenceRef completedEvidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://runs/1/artifacts/log",
                "sha256:" + "b".repeat(64));
        AuditedTaskState head = sealed(List.of(
                pending("AC-003", AuditedRecordKind.REQUIREMENT, true, "criterion"),
                pending("GATE-BUILD", AuditedRecordKind.GATE, true, "build"),
                new AuditedRecord(
                        "GATE-STATIC",
                        AuditedRecordKind.GATE,
                        true,
                        "static",
                        AuditedRecordStatus.COMPLETED,
                        List.of(completedEvidence),
                        "",
                        ""),
                new AuditedRecord(
                        "CLAIM-coding-1-1",
                        AuditedRecordKind.FACT,
                        false,
                        "status=SUCCESS",
                        AuditedRecordStatus.UNTRUSTED,
                        List.of(),
                        "coding-1",
                        "")));
        AuditRun lastRun = lastRun(
                List.of("GATE-BUILD", "AC-003"),
                List.of(),
                List.of("CLAIM-coding-1-1"),
                List.of("qa-evidence://objects/build"));

        String section = AuditedGapSection.render(head, lastRun);

        assertTrue(section.startsWith("# 已审计缺口（Host）"), section);
        assertTrue(section.contains("state_version: " + head.stateVersion()), section);
        assertTrue(section.contains("hash: " + head.stateHash()), section);
        assertTrue(section.contains("GATE-BUILD"), section);
        assertTrue(section.contains("AC-003"), section);
        assertTrue(section.contains("CLAIM-coding-1-1"), section);
        assertTrue(section.contains("qa-evidence://objects/build"), section);
        assertFalse(section.contains(JAVAC), section);
        assertTrue(section.contains("RESULT_JSON"), section);
        assertTrue(gapIds(section).size() <= 16, section);
        assertTrue(section.length() <= 2000, section);
    }

    @Test
    void capsIdsAtSixteenAndBodyAtTwoThousandChars() {
        List<AuditedRecord> records = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        IntStream.rangeClosed(1, 20).forEach(index -> {
            String id = "AC-" + String.format("%03d", index);
            records.add(pending(id, AuditedRecordKind.REQUIREMENT, true, "c" + index));
            missing.add(id);
        });
        records.add(new AuditedRecord(
                "GATE-WS",
                AuditedRecordKind.GATE,
                true,
                "blocked",
                AuditedRecordStatus.BLOCKED,
                List.of(),
                "",
                "integrity"));
        List<String> hugeRefs = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "qa-evidence://objects/" + "x".repeat(180) + index)
                .toList();
        AuditedTaskState head = sealed(records);
        String section = AuditedGapSection.render(head, lastRun(missing, List.of("GATE-WS"), List.of(), hugeRefs));

        assertTrue(section.contains("# 已审计缺口（Host）"), section);
        assertTrue(gapIds(section).size() <= 16, section);
        assertTrue(section.length() <= 2000, section);
        assertTrue(section.contains("GATE-WS"), section);
        assertFalse(section.contains(JAVAC), section);
    }

    @Test
    void completedHeadWithoutGapsRendersEmpty() {
        EvidenceRef evidence = new EvidenceRef(
                "audit-1",
                EvidenceSourceKind.HOST_VERIFICATION,
                "host-verification://runs/1/artifacts/log",
                "sha256:" + "b".repeat(64));
        AuditedTaskState head = sealed(List.of(new AuditedRecord(
                "GATE-BUILD",
                AuditedRecordKind.GATE,
                true,
                "build",
                AuditedRecordStatus.COMPLETED,
                List.of(evidence),
                "",
                "")));
        assertEquals("", AuditedGapSection.render(head, lastRun(List.of(), List.of(), List.of(), List.of())));
    }

    private static List<String> gapIds(String section) {
        List<String> ids = new ArrayList<>();
        for (String line : section.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("missing:") && !trimmed.startsWith("blockers:") && !trimmed.startsWith("untrusted:")) {
                continue;
            }
            String values = trimmed.substring(trimmed.indexOf(':') + 1).strip();
            if (values.isBlank() || values.equals("(empty)")) {
                continue;
            }
            for (String id : values.split(",")) {
                String token = id.strip();
                if (!token.isBlank()) {
                    ids.add(token);
                }
            }
        }
        return ids;
    }

    private static AuditedRecord pending(String id, AuditedRecordKind kind, boolean blocking, String text) {
        return new AuditedRecord(
                id, kind, blocking, text, AuditedRecordStatus.PENDING, List.of(), "", "");
    }

    private static AuditedTaskState sealed(List<AuditedRecord> records) {
        return new AuditedTaskStateCodec().seal(new AuditedTaskState(
                "task-1",
                2L,
                "",
                new AuditedContractRef("sha256:" + "c".repeat(64), 3L, 9L),
                records,
                "audit-1"));
    }

    private static AuditRun lastRun(
            List<String> missing,
            List<String> blockers,
            List<String> untrusted,
            List<String> sourceRefs
    ) {
        return new AuditRun(
                "audit-1",
                "task-1",
                "coding-1",
                "HOST_VERIFY",
                "cmd-1",
                blockers.isEmpty() ? AuditCompletion.INCOMPLETE : AuditCompletion.BLOCKED,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                List.of(),
                missing,
                untrusted,
                blockers,
                sourceRefs,
                1L);
    }
}
