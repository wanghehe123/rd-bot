package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRunTest {

    @Test
    void exposesThreeAxisEnums() {
        assertEquals(
                List.of(AuditCompletion.COMPLETE, AuditCompletion.INCOMPLETE, AuditCompletion.BLOCKED),
                List.of(AuditCompletion.values()));
        assertEquals(
                List.of(AuditIntegrity.CLEAN, AuditIntegrity.SUSPECT, AuditIntegrity.VIOLATION),
                List.of(AuditIntegrity.values()));
        assertEquals(
                List.of(ContractAuditVerdict.ALIGNED, ContractAuditVerdict.UNKNOWN),
                List.of(ContractAuditVerdict.values()));
    }

    @Test
    void rejectsDuplicateCommandIds() {
        AuditRun first = run("cmd-1", List.of(), List.of(), List.of(), List.of());
        AuditRun duplicate = run("cmd-1", List.of("AC-001"), List.of(), List.of(), List.of());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> AuditRun.requireUniqueCommandIds(List.of(first, duplicate)));
        assertTrue(failure.getMessage().contains("cmd-1"));
    }

    @Test
    void boundsVerifiedMissingUntrustedAndBlockers() {
        List<String> tooMany = IntStream.rangeClosed(1, 257).mapToObj(index -> "id-" + index).toList();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run("cmd-v", tooMany, List.of(), List.of(), List.of()))
                .getMessage().contains("verified"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run("cmd-m", List.of(), tooMany, List.of(), List.of()))
                .getMessage().contains("missing"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run("cmd-u", List.of(), List.of(), tooMany, List.of()))
                .getMessage().contains("untrusted"));
        List<String> tooManyBlockers = IntStream.rangeClosed(1, 33).mapToObj(index -> "B-" + index).toList();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run("cmd-b", List.of(), List.of(), List.of(), tooManyBlockers))
                .getMessage().contains("blockers"));
    }

    private static AuditRun run(
            String commandId,
            List<String> verified,
            List<String> missing,
            List<String> untrusted,
            List<String> blockers
    ) {
        return new AuditRun(
                "audit-" + commandId,
                "task-1",
                "stage-1",
                "CODING_AGENT",
                commandId,
                AuditCompletion.INCOMPLETE,
                AuditIntegrity.CLEAN,
                ContractAuditVerdict.ALIGNED,
                verified,
                missing,
                untrusted,
                blockers,
                List.of(),
                1_700_000_000_000L
        );
    }
}
