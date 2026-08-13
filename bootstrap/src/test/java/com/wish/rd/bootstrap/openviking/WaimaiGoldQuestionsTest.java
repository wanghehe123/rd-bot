package com.wish.rd.bootstrap.openviking;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金标题本身的结构门。不连真实库；防止把合成 token 或正文摘抄混进评测题。
 */
class WaimaiGoldQuestionsTest {

    private static final Set<String> FORBIDDEN = Set.of(
            "RD_WP0_",
            "PENDING_PAYMENT",
            "markPaid",
            "waimai-dev-secret-key-2024",
            "Payment callback handled but order status remains"
    );

    @Test
    void goldSetHasTwentyGroundedQuestionsWithADeliberateMix() {
        WaimaiGoldQuestionSet set = WaimaiGoldQuestionSet.load();
        assertEquals("7475766492030701568", set.corpusKnowledgeBaseId());
        assertEquals("waimai", set.corpusKnowledgeBaseName());
        assertEquals(45, set.corpusDocumentCount());
        assertEquals(8, set.k());
        assertEquals(20, set.questions().size());
        assertTrue(set.corpusChoice().contains("45"));

        int single = 0;
        int multi = 0;
        int unanswerable = 0;
        Set<String> ids = new HashSet<>();
        for (WaimaiGoldQuestion question : set.questions()) {
            assertTrue(ids.add(question.id()), "duplicate question id " + question.id());
            assertFalse(question.question().isBlank());
            assertFalse(question.justification().isBlank());
            List<String> expected = question.expectedDocumentIds() == null
                    ? List.of() : question.expectedDocumentIds();
            String upper = question.question().toUpperCase(Locale.ROOT);
            for (String token : FORBIDDEN) {
                assertFalse(upper.contains(token.toUpperCase(Locale.ROOT))
                                || question.question().contains(token),
                        question.id() + " must not embed source-unique token " + token);
            }
            switch (question.type()) {
                case "SINGLE_DOCUMENT" -> {
                    single++;
                    assertEquals(1, expected.size(), question.id() + " single-doc must name one document");
                }
                case "MULTI_DOCUMENT" -> {
                    multi++;
                    assertTrue(expected.size() >= 2, question.id() + " multi-doc must span 2+ documents");
                }
                case "UNANSWERABLE" -> {
                    unanswerable++;
                    assertTrue(expected.isEmpty(), question.id() + " unanswerable must have empty expected set");
                }
                default -> throw new AssertionError("unknown type " + question.type());
            }
        }
        assertTrue(single >= 8, "need a majority of single-document lookups, was " + single);
        assertTrue(multi >= 4, "need several multi-document questions, was " + multi);
        assertTrue(unanswerable >= 2 && unanswerable <= 4,
                "need 2-3 unanswerable probes, was " + unanswerable);
    }
}
