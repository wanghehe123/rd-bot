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

    private static final int MIN_QUOTE_LENGTH = 25;

    private static final Set<String> FORBIDDEN = Set.of(
            "RD_WP0_",
            "PENDING_PAYMENT",
            "markPaid",
            "waimai-dev-secret-key-2024",
            "Payment callback handled but order status remains"
    );

    @Test
    void goldSetHasGroundedQuestionsWithADeliberateMix() {
        WaimaiGoldQuestionSet set = WaimaiGoldQuestionSet.load();
        assertEquals("7475766492030701568", set.corpusKnowledgeBaseId());
        assertEquals("waimai", set.corpusKnowledgeBaseName());
        assertEquals(69, set.corpusDocumentCount());
        assertEquals(8, set.k());
        assertEquals(38, set.questions().size());
        assertTrue(set.corpusChoice().contains("69"));

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
        assertTrue(unanswerable >= 2 && unanswerable <= 6,
                "need a handful of unanswerable probes, was " + unanswerable);
    }

    /**
     * 后续新增的题必须带原文引用，否则标注无法复核，召回数会静默失真。
     * 字节级子串比对要连真实库，放在 tmp/verify_gold_questions.py；这里只守结构。
     */
    @Test
    void everyQuestionAddedWithQuotesCoversEachAnswerDocument() {
        WaimaiGoldQuestionSet set = WaimaiGoldQuestionSet.load();
        int quoted = 0;
        for (WaimaiGoldQuestion question : set.questions()) {
            List<WaimaiGoldQuestion.SupportingQuote> quotes = question.supportingQuotes() == null
                    ? List.of() : question.supportingQuotes();
            if ("UNANSWERABLE".equals(question.type())) {
                assertTrue(quotes.isEmpty(),
                        question.id() + " unanswerable must not carry supporting quotes");
                continue;
            }
            if (quotes.isEmpty()) {
                continue;
            }
            quoted++;
            Set<String> quotedDocuments = new HashSet<>();
            for (WaimaiGoldQuestion.SupportingQuote quote : quotes) {
                assertTrue(question.expectedDocumentIds().contains(quote.documentId()),
                        question.id() + " quotes unlabelled document " + quote.documentId());
                assertTrue(quote.quote().length() >= MIN_QUOTE_LENGTH,
                        question.id() + " quote for " + quote.documentId() + " is too short to verify");
                quotedDocuments.add(quote.documentId());
            }
            assertEquals(new HashSet<>(question.expectedDocumentIds()), quotedDocuments,
                    question.id() + " must quote every answer document exactly once");
        }
        assertTrue(quoted >= 16, "expected the audited question batch to carry quotes, was " + quoted);
    }
}
