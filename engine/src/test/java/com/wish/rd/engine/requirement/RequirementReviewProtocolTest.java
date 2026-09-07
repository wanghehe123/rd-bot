package com.wish.rd.engine.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementReviewProtocolTest {

    @Test
    void needInfoAsksOperatorInsteadOfFailingClosed() {
        String json = """
                {
                  "decision": "NEED_INFO",
                  "feasibility": "NEED_INFO",
                  "missingInformation": ["上线窗口"],
                  "status": "NEED_INFO"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.ASK_OPERATOR,
                RequirementReviewProtocol.disposition(json));
        assertTrue(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void rejectedAndUnsafeStillFailClosed() {
        String json = """
                {
                  "decision": "REJECTED",
                  "feasibility": "UNSAFE",
                  "missingInformation": [],
                  "status": "FAILED"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.FAIL_CLOSED,
                RequirementReviewProtocol.disposition(json));
        assertFalse(RequirementReviewProtocol.asksOperator(json));
        assertTrue(RequirementReviewProtocol.failsClosed(json));
    }

    @Test
    void approvedProceeds() {
        String json = """
                {
                  "decision": "APPROVED",
                  "feasibility": "CAN_DO",
                  "missingInformation": [],
                  "status": "SUCCESS"
                }
                """;

        assertEquals(RequirementReviewProtocol.Disposition.PROCEED,
                RequirementReviewProtocol.disposition(json));
        assertFalse(RequirementReviewProtocol.asksOperator(json));
        assertFalse(RequirementReviewProtocol.failsClosed(json));
    }
}
