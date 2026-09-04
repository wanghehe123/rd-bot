package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptanceCriteriaIdsTest {

    @Test
    void assignsAcIdsInTheSameOrderAsPiAgentContextStateManager() {
        List<String> criteria = List.of("完整验收 A", "完整验收 B");
        assertEquals(List.of("AC-001", "AC-002"), AcceptanceCriteriaIds.of(criteria));
        assertEquals("AC-001", AcceptanceCriteriaIds.idAt(0));
        assertEquals("AC-002", AcceptanceCriteriaIds.idAt(1));
    }

    @Test
    void rejectsBlankCriteriaAndNonPositiveIndex() {
        assertThrows(IllegalArgumentException.class, () -> AcceptanceCriteriaIds.of(List.of("ok", "  ")));
        assertThrows(IllegalArgumentException.class, () -> AcceptanceCriteriaIds.idAt(-1));
    }

    @Test
    void parsesJsonAndEmitsCanonicalAttachmentPayload() {
        assertEquals(List.of("AC-001", "AC-002"), AcceptanceCriteriaIds.ofJson("[\"完整验收 A\",\"完整验收 B\"]"));
        assertEquals("[\"AC-001\",\"AC-002\"]", AcceptanceCriteriaIds.canonicalJson(List.of("AC-001", "AC-002")));
        assertEquals("[]", AcceptanceCriteriaIds.canonicalJson(List.of()));
        assertTrue(AcceptanceCriteriaIds.promptFrozenSetClause(List.of("AC-001")).contains("AC-001"));
    }
}
