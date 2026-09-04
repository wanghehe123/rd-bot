package com.wish.rd.engine.requirement.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extracts bounded UNTRUSTED claims from executor result JSON. */
class RoleClaimExtractorTest {

    @Test
    void copiesStatusTestStatusNotesAndFactsWithoutExceeding32() {
        String json = """
                {
                  "status": "SUCCESS",
                  "testStatus": "PASSED",
                  "environmentNotes": ["jdk 21", ""],
                  "facts": [
                    {"kind": "DECLARED", "statement": "tests green"},
                    "inferred locally"
                  ]
                }
                """;
        RoleClaimSubject subject = RoleClaimExtractor.fromResultJson(
                "audit-1", "cmd-1", "coding-9", "CODING_AGENT", json, 31L);
        assertEquals(3, subject.claims().size());
        assertEquals("status", subject.claims().get(0).name());
        assertEquals("SUCCESS", subject.claims().get(0).value());
        assertEquals("testStatus", subject.claims().get(1).name());
        assertEquals("PASSED", subject.claims().get(1).value());
        assertEquals("environmentNotes", subject.claims().get(2).name());
        assertEquals("jdk 21", subject.claims().get(2).value());
        assertEquals(2, subject.facts().size());
        assertEquals("tests green", subject.facts().get(0).statement());
        assertEquals("DECLARED", subject.facts().get(0).kind());
        assertEquals("inferred locally", subject.facts().get(1).statement());
    }

    @Test
    void truncatesTo32ClaimsAndIgnoresMalformedJson() {
        StringBuilder json = new StringBuilder("{\"environmentNotes\":[");
        for (int index = 1; index <= 40; index++) {
            if (index > 1) {
                json.append(',');
            }
            json.append("\"note-").append(index).append('"');
        }
        json.append("]}");
        RoleClaimSubject truncated = RoleClaimExtractor.fromResultJson(
                "audit-2", "cmd-2", "coding-9", "CODING_AGENT", json.toString(), 32L);
        assertEquals(RoleClaimSubject.MAX_CLAIMS_PER_ATTEMPT, truncated.claims().size());
        assertTrue(truncated.facts().isEmpty());

        RoleClaimSubject empty = RoleClaimExtractor.fromResultJson(
                "audit-3", "cmd-3", "coding-9", "CODING_AGENT", "not-json", 33L);
        assertTrue(empty.claims().isEmpty());
        assertTrue(empty.facts().isEmpty());
    }
}
